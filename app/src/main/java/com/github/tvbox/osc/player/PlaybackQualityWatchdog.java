package com.github.tvbox.osc.player;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.github.tvbox.osc.util.LOG;

import xyz.doikki.videoplayer.player.VideoView;

/**
 * 播放质量看门狗:识别"能播、但很慢 / 一直卡"的线路或片源,驱动换线 / 换源。
 *
 * <p><b>为什么用"位置推进量"而不是网速或重缓冲计数</b>:
 * <ul>
 *   <li>网速({@code tcpSpeed})在缓冲充足时天然为 0 —— 拿它判劣质会把"已经缓冲好、播得很顺"误判成卡顿;</li>
 *   <li>重缓冲计数是内核私有(ExoPlayer.rebufferCount),换内核就没了,不能作为通用判据;</li>
 *   <li>位置推进量是内核无关的:每 {@link #SAMPLE_INTERVAL_MS} 读一次 {@code currentPosition},
 *       推进量明显小于经过时间即说明"该走没走" —— 缓冲中、慢速、卡死三种情况一并覆盖。</li>
 * </ul>
 *
 * <p><b>只在"应该前进"的状态下判定</b>:暂停 / 播完 / 空闲时位置本就不动,计入会把用户按暂停误判成卡顿。
 *
 * <p><b>滞回</b>:① 要连续 {@link #BAD_STREAK_TO_SWITCH} 次采样都劣质才触发(单次抖动不算);
 * ② 触发后 {@link #COOLDOWN_MS} 内不再触发 —— 给新线路 / 新片源留出起播与缓冲时间,
 * 否则会在"刚切过去还没起来"时被再次判劣质,连环换下去。
 */
final class PlaybackQualityWatchdog {

    interface Host {
        /** 当前播放状态(dkplayer {@code VideoView.STATE_*}) */
        int playState();

        /** 当前播放位置(毫秒;无播放器时 -1) */
        long position();

        /** 判定为"持续劣质"时回调(实现方决定换线还是换源) */
        void onPlaybackTooSlow();

        /** 已缓冲未播时长(毫秒);内核拿不到返回 -1。网络富余信号用(升档判定) */
        long bufferedAheadMs();

        /** 网络持续富余(连续满速推进 + 缓冲余量达标)时回调;是否真的升档由 QualityGovernor 门控 */
        void onNetworkPlentiful();
    }

    /** 采样间隔 */
    static final long SAMPLE_INTERVAL_MS = 5_000L;

    /** 连续多少次劣质采样后触发(3 × 5s = 15s) */
    static final int BAD_STREAK_TO_SWITCH = 3;

    /** 触发后的冷却期:期间照常采样但不再触发 */
    static final long COOLDOWN_MS = 30_000L;

    /**
     * 单次采样内位置推进不足"经过时间 × 本比例"即视为劣质。
     * 取 0.5 而非 0.9:弱网下起播前几拍会慢,给一点余量;真卡死的推进量接近 0,0.5 足以区分。
     */
    private static final float MIN_PROGRESS_RATIO = 0.5f;

    // -------------------- 网络富余检测(2026-10-10 自适应画质:升档信号) --------------------

    /** 单次采样内推进 ≥ 经过时间 × 本比例 视为"满速" */
    private static final float GOOD_PROGRESS_RATIO = 0.95f;

    /** 连续多少次满速采样后进入富余判定(3 × 5s = 15s,与劣质滞回对称) */
    private static final int GOOD_STREAK_TO_UPGRADE = 3;

    /** 缓冲余量达标线:已缓冲未播 ≥ 20s 才认为升过去不会马上饿死 */
    private static final long HEADROOM_MIN_MS = 20_000L;

    private int goodStreak = 0;

    private long lastPlentifulAt = 0L;

    private final Host host;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable sampler = new Runnable() {
        @Override
        public void run() {
            sample();
        }
    };

    private boolean running;

    private long lastPosition = -1L;

    private long lastSampleAt = 0L;

    private int badStreak = 0;

    private long lastTriggeredAt = 0L;

    PlaybackQualityWatchdog(Host host) {
        this.host = host;
    }

    /** 开始监控(幂等)。进入可播状态时调用。 */
    void start() {
        if (running) return;
        running = true;
        lastPosition = -1L;
        lastSampleAt = SystemClock.uptimeMillis();
        badStreak = 0;
        goodStreak = 0;
        handler.removeCallbacks(sampler);
        handler.postDelayed(sampler, SAMPLE_INTERVAL_MS);
    }

    /** 停止监控(幂等)。暂停 / 出错 / 播完 / 页面销毁时调用。 */
    void stop() {
        running = false;
        badStreak = 0;
        lastPosition = -1L;
        handler.removeCallbacks(sampler);
    }

    /**
     * 内容 / 线路 / 片源边界:清掉累计的劣质次数与位置基准。
     * <b>不动冷却期</b> —— 冷却的意义正是给刚切过去的线路留出起播时间。
     */
    void reset() {
        badStreak = 0;
        goodStreak = 0;
        lastPosition = -1L;
        lastSampleAt = SystemClock.uptimeMillis();
    }

    /** 播放状态变化:只有"应该前进"的状态才继续跑,其余停表 */
    void onPlayState(int playState) {
        if (isExpectedToAdvance(playState)) {
            start();
        } else {
            stop();
        }
    }

    private void sample() {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        int state = host.playState();

        // 状态已经不该前进(暂停/播完/出错):停表,且不把这段静默计入劣质
        if (!isExpectedToAdvance(state)) {
            stop();
            return;
        }

        long pos = host.position();
        if (pos < 0) {
            // 位置读不到(内核刚重建):只重置基准,不判定
            lastPosition = -1L;
            lastSampleAt = now;
            scheduleNext();
            return;
        }
        if (lastPosition < 0L) {
            lastPosition = pos;
            lastSampleAt = now;
            scheduleNext();
            return;
        }

        long advanced = pos - lastPosition;
        long elapsed = Math.max(1L, now - lastSampleAt);
        lastPosition = pos;
        lastSampleAt = now;

        boolean bad = advanced < (long) (elapsed * MIN_PROGRESS_RATIO);
        // ⚠️ 先存旧值:下面要判断"是否从 0 变正 / 从正变 0"这类边界翻转,
        // 自增之后就再也分不出"翻转"与"持续"了。
        int streakBefore = badStreak;
        if (bad) {
            badStreak++;
            goodStreak = 0;
        } else {
            badStreak = 0;
            // 网络富余累计:推进接近满速才计入(0.5~0.95 之间是"在动但不敢说富余")
            if (advanced >= (long) (elapsed * GOOD_PROGRESS_RATIO)) {
                goodStreak++;
            } else {
                goodStreak = 0;
            }
        }

        // ⚠️ 诊断日志只在**判定结果变化**时打印,不再每次采样都打。
        //
        // 为什么:采样间隔 5 秒,一次播放 2 小时 = 1440 条。此前无条件打印,
        // 把真正的信号("too slow, trigger switch" / 换源结论)淹没在噪音里 ——
        // 实测一天累积 1164 条。前缀 echo-quality 已登记进 FILE_LOG_PREFIXES,
        // 落的是文件日志,占空间、拖慢排查;真机日志里要看这条只能靠翻。
        //
        // 什么样的变化值得记:
        //   1. badStreak 从 0 变正(开始劣化)/ 从正变 0(恢复) —— 边界翻转
        //   2. 累计到触发阈值(即将换源)
        // 其余"又sample 了一次、进度正常"一律不打。
        boolean flippedToBad = bad && streakBefore == 0;
        boolean flippedToGood = !bad && streakBefore > 0;
        boolean nearTrigger = badStreak == BAD_STREAK_TO_SWITCH;
        if (flippedToBad || flippedToGood || nearTrigger) {
            LOG.i("echo-quality: advanced=" + advanced + " elapsed=" + elapsed
                    + " badStreak=" + badStreak + " state=" + state
                    + " mark=" + (flippedToBad ? "degrade-start"
                    : flippedToGood ? "recovered" : "will-switch"));
        }

        if (badStreak >= BAD_STREAK_TO_SWITCH && now - lastTriggeredAt >= COOLDOWN_MS) {
            lastTriggeredAt = now;
            badStreak = 0;
            LOG.i("echo-quality: too slow, trigger switch");
            host.onPlaybackTooSlow();
        }

        // 网络富余 → 升档检查(2026-10-10):与劣质同款滞回 + 同款冷却,
        // 冷却与 lastTriggeredAt 共用 —— 刚因卡顿换过线,不该立刻又升档来回折腾
        if (goodStreak >= GOOD_STREAK_TO_UPGRADE
                && host.bufferedAheadMs() >= HEADROOM_MIN_MS
                && now - lastPlentifulAt >= COOLDOWN_MS) {
            lastPlentifulAt = now;
            goodStreak = 0;
            LOG.i("echo-quality: network plentiful, check upgrade");
            host.onNetworkPlentiful();
        }

        scheduleNext();
    }

    private void scheduleNext() {
        if (!running) return;
        handler.removeCallbacks(sampler);
        handler.postDelayed(sampler, SAMPLE_INTERVAL_MS);
    }

    /**
     * 该状态下播放位置"本应前进":正在播、正在缓冲,以及**正在准备**。
     *
     * <p>为什么必须含 {@code STATE_PREPARING}:用户报的"详情出来了、画面一直转圈、也不自动换源换线"
     * 正是卡在准备阶段。此前只看 PLAYING/BUFFERING,准备阶段直接 {@code stop()} 停表 ——
     * 于是一个永远 prepare 不出来的流既不会被质量看门狗发现,而换线超时那条路又要先过
     * {@code isPlaybackStarted()}(位置/状态判据)才肯切换。两道闸都放行,用户就只能一直等。
     *
     * <p>准备阶段位置恒为 0,连续 3 拍(15s)推进不足即触发换线/换源;正常的起播远快于这个窗口。
     */
    private static boolean isExpectedToAdvance(int playState) {
        return playState == VideoView.STATE_PLAYING
                || playState == VideoView.STATE_BUFFERING
                || playState == VideoView.STATE_PREPARING;
    }
}
