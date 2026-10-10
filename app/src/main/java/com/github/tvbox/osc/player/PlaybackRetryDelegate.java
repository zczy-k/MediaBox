package com.github.tvbox.osc.player;

import android.os.SystemClock;
import android.text.TextUtils;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.EpisodeMatcher;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.util.LOG;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * 重试与换线策略:播完后的同址重播、硬解→软解回退、自动切内核、下一条线路,
 * 以及取流超时/失败/换线超时三处入口的处置。视图交互一律经 {@link PlaybackViewBridge}。
 */
final class PlaybackRetryDelegate {

    interface Host {
        PlaybackAttemptState attemptState();

        PlaybackViewBridge view();

        JSONObject playerCfg();

        VodInfo vod();

        VodInfo.VodSeries currentSeries(String flag, int index);

        /** 换线前记下"接着看"的位置(新线路的进度键不同,靠它接着看;必须在改 playFlag 之前调用) */
        void rememberProgressForSwitch();

        String webPlayUrl();

        HashMap<String, String> webHeaderMap();

        boolean resolverHasFoundUrls();

        void resolverConsumeFoundUrl();

        void play(boolean reset);

        void playUrl(String url, HashMap<String, String> headers);

        void stopParse();

        void initParseLoadFound();

        void cancelPlayRequest();

        void cancelPlayTimeout();

        boolean isPlaybackStarted();

        /** 总闸下本次是否允许跨内容复用内核(内核在、未报错)。重试阶梯的"先释放再重播"以它为准;软解回退不查(改解码必须重建) */
        boolean isCrossContentReuseAllowed();

        void stopMusicSessionForFailedPlayback();
    }

    private final Host host;

    // -------------------- 自适应画质:升档状态(2026-10-10 第一批) --------------------

    /** 升档出发地快照:回滚目标(已验证可播的线路/集/实测档) */
    private String upgradeOriginFlag = "";

    private int upgradeOriginIndex = -1;

    private int upgradeOriginHeight = 0;

    /** 升档时刻(uptimeMillis);0 = 无升档在途 */
    private long upgradedAtElapsed = 0L;

    /** 起播成功时刻(稳定期计时起点);0 = 未标记 */
    private long episodeStartElapsed = 0L;

    private int upgradesDone = 0;

    /** 回滚后锁定:本集会话不再升档(防"升→卡→降→再升"振荡) */
    private boolean upgradeLockedThisSession = false;

    PlaybackRetryDelegate(Host host) {
        this.host = host;
    }

    /** 自动重试回滚:把"自动切成别的内核"还原成用户配置(只改内存态 + 通知 UI) */
    private void restoreAutoSwitchedPlayer() {
        PlaybackAttemptState st = host.attemptState();
        if (st.autoSwitchedPlayerType < 0) return;
        st.setReleaseIntent(true);
        try {
            LOG.i("echo-autoRetry restore player: " + host.playerCfg().optInt("pl", -1) + " -> " + st.autoSwitchedPlayerType);
            host.playerCfg().put("pl", st.autoSwitchedPlayerType);
            PlaybackViewBridge view = host.view();
            if (view != null) view.applyPlayerConfig(host.playerCfg());
        } catch (Throwable th) {
            LOG.e("PlaybackController", th);
        } finally {
            st.autoSwitchedPlayerType = -1;
        }
    }

    /**
     * 自动切"硬解→软解"的回滚(与 restoreAutoSwitchedPlayer 同语义):把 cfg.exo 还原成用户设置的值(只改内存)。
     *
     * <p>⚠️ 判定只看 {@code autoSwitchedDecodeOld}(确有"自动软解"可回滚),**不能**看
     * {@code hasAutoSwitchedDecode} —— 后者同时兼任"用户显式选过解码 ⇒ 本次播放不再自动回退"的**阻断标记**
     * (见 {@code setAllowDecodeFallback},此时 autoSwitchedDecodeOld 为 null);按它判定会在
     * 换线/超时等失败路径上把用户的阻断一并清掉,此后自动软解又会把用户显式选的硬解顶掉。
     */
    private void restoreAutoSwitchedDecode() {
        PlaybackAttemptState st = host.attemptState();
        if (st.autoSwitchedDecodeOld == null) return;
        st.hasAutoSwitchedDecode = false;
        try {
            JSONObject cfg = host.playerCfg();
            if (cfg != null) {
                LOG.i("echo-autoRetry restore decode: " + cfg.optString(st.autoSwitchedDecodeKey, "") + " -> " + st.autoSwitchedDecodeOld);
                cfg.put(st.autoSwitchedDecodeKey, st.autoSwitchedDecodeOld);
                // 与 restoreAutoSwitchedPlayer 同款:同步覆盖层 UI(解码按钮文案/状态读的是 cfg)
                PlaybackViewBridge view = host.view();
                if (view != null) view.applyPlayerConfig(cfg);
            }
        } catch (Throwable th) {
            LOG.e("PlaybackController", th);
        } finally {
            st.autoSwitchedDecodeOld = null;
        }
    }

    private int exoLastErrorKind() {
        try {
            PlaybackViewBridge view = host.view();
            AbstractPlayer live = (view == null) ? null : view.mediaPlayer();
            if (live instanceof ExoPlayer) return ((ExoPlayer) live).lastErrorKind();
        } catch (Throwable ignored) {
            LOG.d("PlaybackController", "exo error kind probe failed");
        }
        return ExoPlayer.ERROR_KIND_UNKNOWN;
    }

    /**
     * 起播失败的"硬解→软解"回退(每次播放最多触发一次,软解态在本次会话内持续)。
     *
     * <p>为什么必须做在**内核内部**而不是靠阶梯里的"换线路":设备硬解不了的格式(HEVC 10bit/高 profile、
     * 老设备 AV1 等)最有效的兜底是软解 —— EXO 走系统软件解码器(c2.android.*),命中率远高于换一路硬解源。
     *
     * <p>改动的是 {@code cfg.exo}(由 PlayerHelper 下发到 media3 的视频解码选择器,
     * 见 ExoPlayer.EXO_VIDEO_CODEC_SELECTOR)。
     *
     * <p>只改本次会话的内存配置({@code playerCfg}),**不落播放记录**
     * (落库侧由 playerCfgForPersist 兜底剔除),换线时由 {@link #restoreAutoSwitchedDecode()} 回滚,
     * 用户显式点解码按钮时作废。换集不还原(会话内持续用软解),
     * 但阻断标记会随 beginNewPlay 复位,即新一集仍可获得一次回退机会。
     */
    private boolean trySoftDecodeFallback() {
        PlaybackAttemptState st = host.attemptState();
        JSONObject cfg = host.playerCfg();
        if (st.hasAutoSwitchedDecode || cfg == null) return false;
        // 外部播放器(pl 10~14)没有内核内软解路径,不适用
        if (cfg.optInt("pl", 2) >= 10) return false;
        final String decodeKey = "exo";
        if (!"硬解码".equals(cfg.optString(decodeKey, ""))) return false; // i18n: keep —— 已经是软解,不再回退
        if (TextUtils.isEmpty(host.webPlayUrl())) return false;          // 没拿到可播地址(解析/嗅探失败)不适用
        String oldDecode = cfg.optString(decodeKey, "");
        try {
            cfg.put(decodeKey, "软解码"); // i18n: keep
        } catch (Throwable th) {
            return false;
        }
        LOG.i("echo-autoRetry hard->soft decode: " + host.webPlayUrl());
        st.autoSwitchedDecodeOld = oldDecode;
        st.autoSwitchedDecodeKey = decodeKey;
        st.hasAutoSwitchedDecode = true;
        // 覆盖层"解码"按钮的文案读的是 cfg,这里同步一次(与自动切内核一致,见 restoreAutoSwitchedPlayer)
        PlaybackViewBridge view = host.view();
        if (view != null) view.applyPlayerConfig(cfg);
        host.stopParse();
        host.initParseLoadFound();
        // ⚠️ 刻意**不再**弹「硬解失败,已切换软解重试」。
        // 理由(产品决策):自动软解是内部兜底动作,用户既不关心、也无法干预 ——
        // 弹出来只是盖在画面上干扰观看。真要确认当前解码方式,覆盖层的「解码」按钮
        // 已经实时显示(上面 applyPlayerConfig 就是同步它),无需另开一个瞬时提示。
        if (view != null) view.releasePlayer();
        if (view != null) host.playUrl(host.webPlayUrl(), host.webHeaderMap());
        return true;
    }

    /**
     * 起播后(已 PREPARED/PLAYING 过)报错的兜底:此前 errorWithRetry 对这类错误静默吞掉 ——
     * 无提示、不重试,表现为"黑屏死在那"(源上游不稳,播放中重拉 m3u8
     * 播放列表拿到网关 HTML 错误页)。这里自动"同内核同地址重播"一次;已试过或无可播地址时
     * 返回 false,由页面给可见提示。
     *
     * <p>复位点:{@code beginNewPlay}(换集/换线/换源)与 {@code resetAutoRetryState}
     * (手动重播/切内核/切解码/换解析)—— 用户的主动自救动作之后允许再兜一次底。
     */
    boolean retryAfterStartedError() {
        PlaybackAttemptState st = host.attemptState();
        if (st.hasRetriedAfterStart) return false;
        if (TextUtils.isEmpty(host.webPlayUrl())) return false;
        st.hasRetriedAfterStart = true;
        st.hasRetriedSameUrlOnBoot = true;
        LOG.i("echo-autoRetry retry after started error: " + host.webPlayUrl());
        PlaybackViewBridge view = host.view();
        if (view != null && view.isPageAlive()) {
            final PlaybackViewBridge aliveView = view;
            view.runOnUi(() -> aliveView.toast(PlaybackController.str(R.string.player_play_error_retry)));
        }
        host.stopParse();
        host.initParseLoadFound();
        // 复位"已起播"标记:重播若在起播前就再次失败,后续 errorWithRetry 应走 autoRetry 阶梯
        // (切内核/换线)而不是再次落入 started=true 的兜底分支(与 PlayContainer.replay 的复位一致)
        st.playbackStarted = false;
        if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer();
        if (view != null) host.playUrl(host.webPlayUrl(), host.webHeaderMap());
        return true;
    }

    /**
     * 命中预解析缓存的起播失败兜底(直链可能已过期):丢弃该结果并立即重新取流同集一次。
     * 没有这一步会先走重播/换线阶梯(含 20s 起播超时),比不预解析更慢。
     */
    private boolean retryWithFreshResolve(String reason) {
        PlaybackAttemptState st = host.attemptState();
        if (!st.usedPreloadedResult) return false;
        st.usedPreloadedResult = false;
        LOG.i("echo-preload-stale: re-resolve current episode (" + reason + ")");
        st.playbackStarted = false;
        host.stopParse();
        host.initParseLoadFound();
        PlaybackViewBridge view = host.view();
        // 同址同内容的兜底重播:预热总闸下不重建内核(与"点击即停"路径同一许可)
        if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer();
        host.play(false);
        return true;
    }

    /**
     * 自动重试(播放出错/超时后):依次尝试 ①嗅探到的新地址 ②原样重播当前地址(每轮一次,吸收网络/源抖动)
     * ③硬解→软解重播当前地址(每次播放一次;EXO 明确报网络/容器解析类错误时跳过)
     * ④切换播放内核重播当前地址 ⑤下一条线路。
     *
     * @return true = 已发起重试;false = 无路可走(调用方负责提示与收尾)
     */
    boolean autoRetry() {
        PlaybackAttemptState st = host.attemptState();
        if (retryWithFreshResolve("autoRetry")) return true;
        long currentTime = System.currentTimeMillis();
        if (currentTime - st.lastRetryTime > 60_000) {
            LOG.i("echo-reset-autoRetryCount");
            st.resetAutoRetryLadder();
        }
        st.lastRetryTime = currentTime;
        // ConcurrentLinkedQueue.size() 是 O(n) 遍历且弱一致(并发 add 时可能读到 0);isEmpty() 为 O(1) 且更准确
        if (host.resolverHasFoundUrls()) {
            host.resolverConsumeFoundUrl();
            return true;
        }
        int exoErrorKind = exoLastErrorKind();
        // 记下判据:阶梯后面可能一路换线/换源都失败,收口时只剩这里能区分"断网"与"片源废"
        st.lastFailureNetwork = exoErrorKind == ExoPlayer.ERROR_KIND_NETWORK;
        if (exoErrorKind != ExoPlayer.ERROR_KIND_DECODE
                && !st.hasRetriedSameUrlOnBoot && !TextUtils.isEmpty(host.webPlayUrl())) {
            st.hasRetriedSameUrlOnBoot = true;
            LOG.i("echo-autoRetry replay same url before decode fallback: " + host.webPlayUrl());
            host.stopParse();
            host.initParseLoadFound();
            PlaybackViewBridge view = host.view();
            // ②同址重播:预热总闸下不重建内核(软解回退是例外,见 trySoftDecodeFallback —— 改解码必须重建)
            if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer();
            if (view != null) host.playUrl(host.webPlayUrl(), host.webHeaderMap());
            return true;
        }
        // ③ 硬解→软解:解码类起播失败覆盖面最广的兜底,排在换内核之前(先保住用户选的内核)
        if (exoErrorKind != ExoPlayer.ERROR_KIND_NETWORK && trySoftDecodeFallback()) return true;
        if (host.webPlayUrl() != null) {
            if (st.allowSwitchPlayer && !st.hasAutoSwitchedPlayer) {
                LOG.i("echo-autoRetry switch player and replay current url");
                int playerType = host.playerCfg().optInt("pl", -1);
                PlaybackViewBridge view = host.view();
                boolean switchSkipped = view != null && view.switchPlayerKernel();
                st.hasAutoSwitchedPlayer = true;
                st.allowSwitchPlayer = false;
                if (!switchSkipped) {
                    st.autoSwitchedPlayerType = playerType;
                    host.stopParse();
                    host.initParseLoadFound();
                    // ④切内核重播:本分支下重建标记随切换链路置位,复用判定已在起播点兜住;此处只统一"是否先释放"口径
                    if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer();
                    if (view != null) host.playUrl(host.webPlayUrl(), host.webHeaderMap());
                    return true;
                }
            }
            LOG.i("echo-autoRetry current url failed after player switch, try next line");
            return tryNextLineIfEnabled();
        }
        return tryNextLineIfEnabled();
    }

    /**
     * 换线开关判断(已在尝试换线时先回滚内核与解码方式)。
     *
     * <p>换线被禁用时**不能**直接 return false:换源兜底挂在 `tryNextLine` 走完所有线路之后
     * （见 `tryNextLine` 里 nextFlag == null 的分支），禁用换线就等于把"线路耗尽"这个事件一起掐掉 ——
     * 外部表现是线路挂了既不换线也不换源,只弹播放失败。全屏时运行时闸 `allowAutoSwitchLine`
     * 会被 `PlaybackController.setAutoSwitchLineEnabled(false)` 关掉,所以这条通路必须独立于开关存在。
     */
    boolean tryNextLineIfEnabled() {
        restoreAutoSwitchedPlayer();
        restoreAutoSwitchedDecode();
        PlaybackAttemptState st = host.attemptState();
        if (st.allowAutoSwitchLine && KV.get(HawkConfig.AUTO_SWITCH_LINE, true)) {
            // 先降档再顺序换线:起播失败最常见的原因就是"这一档太高",按实测高度降最省事
            if (tryDowngradeLine()) return true;
            return tryNextLine();
        }
        LOG.i("echo-autoRetry line switching disabled, hand over to source fallback");
        st.resetAutoRetryLadder();
        st.linesExhausted();
        return handOverToSourceFallback();
    }

    /**
     * 线路耗尽、把处置交回换源链。
     *
     * <p>换源同样给一句阶段反馈(不含源名),否则用户只看到"正在获取播放信息"一直转,
     * 分不清后台是在重试当前地址、还是在换线路、还是已经换到别的来源去了。
     */
    private boolean handOverToSourceFallback() {
        showStageTip(R.string.player_trying_other_source);
        PlaybackViewBridge view = host.view();
        return view != null && view.onLinesExhausted();
    }

    /**
     * 阶段反馈:换线/换源这类"后台在推进"的时刻给一句提示,让等待不再是死转圈。
     *
     * <p>**绝不带源名或线路名** —— 换源/换线已自动化,暴露"现在用的是哪个站/哪条线路"
     * 既是多余信息,也与错误文案已做的源身份脱敏口径不一致(见 SourceIdentityMask)。
     */
    private void showStageTip(int resId) {
        PlaybackViewBridge view = host.view();
        if (view == null || !view.isPageAlive()) return;
        final PlaybackViewBridge aliveView = view;
        view.runOnUi(() -> aliveView.showTip(PlaybackController.str(resId), true, false));
    }

    /**
     * 当前线路的**实测**高度(像素);拿不到返回 0。
     *
     * <p>只认内核上报的真实尺寸,不从 flag 名或 URL 猜 —— 降档的方向判断错了比不降更糟。
     */
    private int currentMeasuredHeight() {
        PlaybackViewBridge view = host.view();
        if (view == null) return 0;
        try {
            AbstractPlayer mediaPlayer = view.mediaPlayer();
            if (!(mediaPlayer instanceof ExoPlayer)) return 0;
            for (TrackInfoBean video : ((ExoPlayer) mediaPlayer).getTrackInfo().getVideo()) {
                if (video != null && video.height > 0) return video.height;
            }
        } catch (Throwable ignored) {
            LOG.d("PlaybackController", "measured height unavailable");
        }
        return 0;
    }

    /**
     * 换到**实测分辨率更低**的一条(卡顿/起播失败时的第一跳)。
     *
     * <p>与 {@link #tryNextLine()} 的区别只在"选哪一条":按站点顺序换可能换到同样高的档,白折腾一次起播;
     * 按实测高度降则直击"分辨率超过网络/设备能力"这个真因。两者都失败才落到换线 → 换源。
     *
     * <p>候选来自实测记忆,所以**没记忆的集降不了档**(这是刻意的:没数据不猜)。
     */
    private boolean tryDowngradeLine() {
        PlaybackAttemptState st = host.attemptState();
        VodInfo vod = host.vod();
        if (vod == null || vod.seriesMap == null || vod.seriesMap.isEmpty()) return false;
        if (TextUtils.isEmpty(vod.playFlag) || TextUtils.isEmpty(vod.sourceKey)) return false;
        List<String> lineFlags = EpisodeMatcher.lineFlagsInDisplayOrder(vod);
        int currentHeight = currentMeasuredHeight();
        if (currentHeight <= 0) {
            LOG.i("echo-downgrade: current height unknown, skip");
            return false;
        }
        List<VideoQualityPolicy.Variant> measured =
                VideoQualityMemory.lookupAll(vod.sourceKey, vod.id, lineFlags);
        String target = LineQualitySelector.pickDowngrade(measured, vod.playFlag, currentHeight, st.triedLineFlags);
        if (target == null) return false;
        List<VodInfo.VodSeries> targetList = vod.seriesMap.get(target);
        if (targetList == null || targetList.isEmpty()) return false;
        VodInfo.VodSeries currentSeries = host.currentSeries(vod.playFlag, Math.max(vod.playIndex, 0));
        int nextIndex = EpisodeMatcher.sameEpisodeIndex(currentSeries, targetList, vod.playIndex);
        return switchLineTo(target, nextIndex, "echo-downgrade " + currentHeight + "p",
                R.string.player_trying_other_line);
    }

    /**
     * 切到指定 flag:进度继承、集名匹配、标记已试,三件事降档与换线共用,避免两条路径行为漂移。
     */
    private boolean switchLineTo(String targetFlag, int nextIndex, String logPrefix, int tipResId) {
        PlaybackAttemptState st = host.attemptState();
        VodInfo vod = host.vod();
        if (vod == null || TextUtils.isEmpty(vod.playFlag)) return false;
        st.triedLineFlags.add(vod.playFlag);
        LOG.i(logPrefix + ": switch line " + vod.playFlag + " -> " + targetFlag);
        // 给一句"在动"的阶段反馈,但**不带线路名/序号**:名字会泄露用的是哪条线路,
        // 而序号在换源后会重置回 1,反而让人以为"怎么又从头开始"。
        showStageTip(tipResId);
        // 换线不换集:记下当前位置,新线路的进度键不同,靠它接着看。
        // ⚠️ 必须在改 playFlag/playIndex **之前** —— 记下的那条要带走的是旧线路的集名。
        host.rememberProgressForSwitch();
        vod.playFlag = targetFlag;
        vod.playIndex = nextIndex;
        st.onLineSwitched();
        host.play(false);
        return true;
    }

    /** 切到"下一条未尝试过且有剧集"的线路,集号按集名匹配(换线不换集) */
    boolean tryNextLine() {
        PlaybackAttemptState st = host.attemptState();
        VodInfo vod = host.vod();
        if (vod == null || vod.seriesMap == null || vod.seriesMap.isEmpty()) {
            st.linesExhausted();
            return false;
        }
        String currentFlag = vod.playFlag;
        int currentIndex = Math.max(vod.playIndex, 0);
        VodInfo.VodSeries currentSeries = host.currentSeries(currentFlag, currentIndex);
        if (!TextUtils.isEmpty(currentFlag)) {
            st.triedLineFlags.add(currentFlag);
        }
        List<String> lineFlags = EpisodeMatcher.lineFlagsInDisplayOrder(vod);
        int currentLineIndex = EpisodeMatcher.lineFlagIndex(lineFlags, currentFlag);
        int startLineIndex = currentLineIndex >= 0 ? currentLineIndex + 1 : 0;
        String nextFlag = null;
        int nextIndex = 0;
        for (int i = startLineIndex; i < lineFlags.size(); i++) {
            String flag = lineFlags.get(i);
            List<VodInfo.VodSeries> seriesList = vod.seriesMap.get(flag);
            if (!st.triedLineFlags.contains(flag) && seriesList != null && !seriesList.isEmpty()) {
                nextFlag = flag;
                nextIndex = EpisodeMatcher.sameEpisodeIndex(currentSeries, seriesList, currentIndex);
                break;
            }
        }
        if (nextFlag == null) {
            LOG.i("echo-autoRetry all lines exhausted");
            st.linesExhausted();
            return handOverToSourceFallback();
        }
        return switchLineTo(nextFlag, nextIndex, "echo-autoRetry switch line",
                R.string.player_trying_other_line);
    }

    // -------------------- 自适应画质:线内升档 + 回滚(2026-10-10 第一批) --------------------

    /** 新内容边界(beginNewPlay):升档计数/会话锁/出发地快照全部作废 */
    void resetUpgradeState() {
        upgradeOriginFlag = "";
        upgradeOriginIndex = -1;
        upgradeOriginHeight = 0;
        upgradedAtElapsed = 0L;
        episodeStartElapsed = 0L;
        upgradesDone = 0;
        upgradeLockedThisSession = false;
    }

    /** 起播成功(markPlaybackStarted):稳定期计时起点 */
    void noteEpisodeStart() {
        if (episodeStartElapsed == 0L) episodeStartElapsed = SystemClock.elapsedRealtime();
    }

    /**
     * 看门狗报"网络持续富余" → 线内升档(换更高实测档线路,换线不换集)。
     *
     * <p>门控全部在 [QualityGovernor.canUpgrade]:画质档位(SPEED_FIRST 永不升/AUTO ≥2 档/
     * QUALITY_FIRST ≥1 档)、稳定期(≥60s)、每集额度(≤2 次)、会话锁。
     * 候选只来自实测记忆( pickUpgrade,升得最少单步走),不探测不猜。
     */
    void handleNetworkPlentiful() {
        PlaybackAttemptState st = host.attemptState();
        if (upgradeLockedThisSession) {
            LOG.i("echo-quality upgrade skip: session locked");
            return;
        }
        if (upgradedAtElapsed != 0L
                && QualityGovernor.inRollbackWindow(SystemClock.elapsedRealtime(), upgradedAtElapsed)) {
            LOG.i("echo-quality upgrade skip: in rollback-observe window");
            return;
        }
        VodInfo vod = host.vod();
        if (vod == null || TextUtils.isEmpty(vod.playFlag) || TextUtils.isEmpty(vod.sourceKey)) return;
        if (vod.seriesMap == null || vod.seriesMap.isEmpty()) return;
        int currentHeight = currentMeasuredHeight();
        if (currentHeight <= 0) return;
        List<String> lineFlags = EpisodeMatcher.lineFlagsInDisplayOrder(vod);
        List<VideoQualityPolicy.Variant> measured =
                VideoQualityMemory.lookupAll(vod.sourceKey, vod.id, lineFlags);
        String target = LineQualitySelector.pickUpgrade(measured, vod.playFlag, currentHeight, st.triedLineFlags);
        if (target == null) {
            // 区分"记忆里就没有更高档"与"有但都试过了"(2026-10-10):前者值得补探测,
            // 后者补探测也无济于事 —— 候选都被 tried 排除,说明这集的更高档已经试过并失败过
            long untriedHigher = measured.stream()
                    .filter(v -> v.getFlag() != null && !v.getFlag().isEmpty() && !v.getFlag().equals(vod.playFlag)
                            && v.getHeight() > currentHeight && !st.triedLineFlags.contains(v.getFlag()))
                    .count();
            LOG.i("echo-quality upgrade skip: no higher measured tier in memory"
                    + " (untriedHigher=" + untriedHigher + ")");
            // 第二批:网络富余但无候选 → 请详情页补探测未测线路(幂等,只探直连型未测者),
            // 填上记忆后下一次富余检查(≥30s 后)才有资格做升档决策
            if (untriedHigher == 0 && DeviceCapability.QualityMode.current().getShouldProbeOnFirstWatch()
                    && vod.seriesMap != null && vod.seriesMap.size() > 1) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PROBE_MISSING_LINES));
                LOG.i("echo-quality probe-request posted");
            }
            return;
        }
        int targetHeight = 0;
        for (VideoQualityPolicy.Variant v : measured) {
            if (target.equals(v.getFlag())) {
                targetHeight = v.getHeight();
                break;
            }
        }
        long sinceStart = episodeStartElapsed == 0L ? -1L : SystemClock.elapsedRealtime() - episodeStartElapsed;
        if (!QualityGovernor.canUpgrade(DeviceCapability.QualityMode.current(), currentHeight, targetHeight,
                sinceStart, upgradesDone, upgradeLockedThisSession ? QualityGovernor.tierOf(upgradeOriginHeight) : -1)) {
            LOG.i("echo-quality upgrade skip: gate denied cur=" + currentHeight
                    + " target=" + targetHeight + " done=" + upgradesDone);
            return;
        }
        List<VodInfo.VodSeries> targetList = vod.seriesMap.get(target);
        if (targetList == null || targetList.isEmpty()) return;
        VodInfo.VodSeries currentSeries = host.currentSeries(vod.playFlag, Math.max(vod.playIndex, 0));
        int nextIndex = EpisodeMatcher.sameEpisodeIndex(currentSeries, targetList, vod.playIndex);
        // 拍照出发地:回滚窗口内的失败先回到这里(已验证可播)
        upgradeOriginFlag = vod.playFlag;
        upgradeOriginIndex = vod.playIndex;
        upgradeOriginHeight = currentHeight;
        upgradedAtElapsed = SystemClock.elapsedRealtime();
        upgradesDone++;
        switchLineTo(target, nextIndex, "echo-quality upgrade " + currentHeight + "p",
                R.string.player_quality_upgrading);
    }

    /**
     * 升档失败回滚:升档后 [QualityGovernor.ROLLBACK_WINDOW_MS] 内出现失败/判劣质,
     * 第一优先切回出发地 —— 目标画质**确定**(升级前的档),不让通用链漫游到未知档。
     *
     * @return true = 已回滚接管;false = 无升档在观察期,走通用失败链
     */
    private boolean maybeRollbackUpgrade(String why) {
        PlaybackAttemptState st = host.attemptState();
        if (upgradedAtElapsed == 0L) return false;
        if (!QualityGovernor.inRollbackWindow(SystemClock.elapsedRealtime(), upgradedAtElapsed)) {
            // 窗口已过:清快照,此后失败按普通卡顿处理
            upgradedAtElapsed = 0L;
            return false;
        }
        String flag = upgradeOriginFlag;
        int index = upgradeOriginIndex;
        upgradedAtElapsed = 0L;
        if (TextUtils.isEmpty(flag)) return false;
        upgradeLockedThisSession = true;
        LOG.i("echo-quality upgrade-rollback(" + why + "): back to " + flag
                + ", upgrades locked this session");
        // 出发地在升档时被 switchLineTo 记为"已试",回滚要先摘掉这个标记
        st.triedLineFlags.remove(flag);
        switchLineTo(flag, index, "echo-quality upgrade-rollback",
                R.string.player_trying_other_line);
        return true;
    }

    /**
     * 播放质量看门狗判定"当前线路 / 片源持续卡顿"时的处置。
     *
     * <p>策略:
     * <ol>
     *   <li>开了「自动换线」→ 先在同一片源内换下一条线路(代价小,优先);</li>
     *   <li>没开「自动换线」、或同源线路已轮完 → 换到下一个片源;</li>
     *   <li>片源与线路都轮完 → 明确报播放失败,不再无休止地换。</li>
     * </ol>
     *
     * <p>用户手动点过线路({@code userPickedLine})时不覆盖其选择,直接走换源。
     *
     * @return true = 已接管(换线或换源);false = 全部轮完,已报失败
     */
    boolean handlePlaybackTooSlow() {
        PlaybackAttemptState st = host.attemptState();
        PlaybackViewBridge view = host.view();
        if (view == null) return false;
        // 升档失败回滚优先(2026-10-10):升档观察期内判劣质,先回出发地(已验证可播、档位已知),
        // 不走"降档→站序→换源"漫游到未知档 —— 升档场景的最优解是"回到出发地"
        if (maybeRollbackUpgrade("too-slow")) return true;
        // 卡顿时同源换线是代价最小的一跳,故这里**故意不看** allowAutoSwitchLine ——
        // 它由全屏决定,本意是"别拿换集打断正在看的画面";可卡顿意味着画面已经废了,
        // 再看它等于把最便宜的一跳也关掉,只剩"重新搜索 + 重取详情"这种重跳。
        boolean lineFirst = !st.userPickedLine && KV.get(HawkConfig.AUTO_SWITCH_LINE, true);
        if (lineFirst) {
            LOG.i("echo-quality: try downgrade line first");
            // 先降档:卡顿最常见的真因就是"当前档超过网络/设备能力",降一档比换线更对症
            if (tryDowngradeLine()) return true;
            LOG.i("echo-quality: no lower tier in memory, try next line");
            if (tryNextLine()) return true;
        }
        LOG.i("echo-quality: try next source");
        if (handOverToSourceFallback()) return true;
        LOG.i("echo-quality: all sources and lines exhausted");
        reportExhausted();
        return false;
    }

    /**
     * 彻底无路可走时的收口提示。
     *
     * <p>为什么必须分流:断网/线路被墙时若统一报"片源不可用",用户会一直点换源而不知道该查网络;
     * 反过来把片源自身失效报成"网络错误"同样误导。判据取重试阶梯里最后一次读到的 EXO 错误类型。
     */
    private void reportExhausted() {
        host.stopMusicSessionForFailedPlayback();
        boolean network = host.attemptState().lastFailureNetwork;
        showErrorTip(PlaybackController.str(
                network ? R.string.toast_network_error : R.string.player_play_failed_all));
    }

    void handleResolvePlayUrlTimeout() {
        PlaybackAttemptState st = host.attemptState();
        if (maybeRollbackUpgrade("resolve-timeout")) return;
        if (retryWithFreshResolve("resolveTimeout")) return;
        LOG.i("echo-resolvePlayUrl timeout, try next line");
        host.cancelPlayRequest();
        host.stopParse();
        if (st.userPickedLine) {
            st.userPickedLine = false;
            host.stopMusicSessionForFailedPlayback();
            showErrorTip(PlaybackController.str(R.string.player_get_url_timeout));
            return;
        }
        if (!tryNextLineIfEnabled()) reportExhausted();
    }

    void handleResolvePlayUrlFailed(String err) {
        PlaybackAttemptState st = host.attemptState();
        if (maybeRollbackUpgrade("resolve-failed")) return;
        LOG.i("echo-resolvePlayUrl failed, try next line: " + err);
        host.cancelPlayRequest();
        host.stopParse();
        if (st.userPickedLine) {
            st.userPickedLine = false;
            host.cancelPlayTimeout();
            host.stopMusicSessionForFailedPlayback();
            showErrorTip(err);
            return;
        }
        if (tryNextLineIfEnabled()) return;
        host.cancelPlayTimeout();
        reportExhausted();
    }

    void handleSwitchLinePlayTimeout() {
        PlaybackAttemptState st = host.attemptState();
        PlaybackViewBridge view = host.view();
        int state = view == null ? -1 : view.currentPlayState();
        LOG.i("echo-switchLinePlay timeout state: " + state + ", started: " + st.playbackStarted);
        if (host.isPlaybackStarted()) {
            host.cancelPlayTimeout();
            if (view != null) view.hideTipOnUiThread();
            return;
        }
        LOG.i("echo-switchLinePlay timeout, try next line");
        host.stopParse();
        if (st.hasAutoSwitchedPlayer) {
            if (!tryNextLineIfEnabled()) reportExhausted();
            return;
        }
        if (!autoRetry()) reportExhausted();
    }

    private void showErrorTip(String err) {
        PlaybackViewBridge view = host.view();
        if (view != null) view.showTip(err, false, true);
    }
}
