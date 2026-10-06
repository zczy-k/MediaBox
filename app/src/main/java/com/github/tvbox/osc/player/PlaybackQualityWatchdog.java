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
        if (bad) {
            badStreak++;
        } else {
            badStreak = 0;
        }

        LOG.i("echo-quality: advanced=" + advanced + " elapsed=" + elapsed
                + " badStreak=" + badStreak + " state=" + state);

        if (badStreak >= BAD_STREAK_TO_SWITCH && now - lastTriggeredAt >= COOLDOWN_MS) {
            lastTriggeredAt = now;
            badStreak = 0;
            LOG.i("echo-quality: too slow, trigger switch");
            host.onPlaybackTooSlow();
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
