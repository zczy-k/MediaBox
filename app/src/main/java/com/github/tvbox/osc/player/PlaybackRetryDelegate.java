package com.github.tvbox.osc.player;

import android.text.TextUtils;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.util.EpisodeMatcher;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.util.LOG;

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

        String progressKey();

        long getSavedProgress(String url);

        void inheritProgressFrom(String key, long position);

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
        if (view != null && view.isPageAlive()) {
            final PlaybackViewBridge aliveView = view;
            view.runOnUi(() -> aliveView.toast(PlaybackController.str(R.string.player_decode_fallback_tip)));
        }
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

    /** 自动换线开关判断(已在尝试换线时先回滚内核与解码方式) */
    boolean tryNextLineIfEnabled() {
        restoreAutoSwitchedPlayer();
        restoreAutoSwitchedDecode();
        if (host.attemptState().allowAutoSwitchLine && KV.get(HawkConfig.AUTO_SWITCH_LINE, false)) return tryNextLine();
        LOG.i("echo-autoRetry line switching disabled");
        host.attemptState().resetAutoRetryLadder();
        return false;
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
        PlaybackViewBridge view = host.view();
        if (nextFlag == null) {
            LOG.i("echo-autoRetry all lines exhausted");
            st.linesExhausted();
            return view != null && view.onLinesExhausted();
        }
        final String flagToSwitch = nextFlag;
        final String preProgressKey = host.progressKey();
        final long savedProgress = TextUtils.isEmpty(preProgressKey) ? 0 : host.getSavedProgress(preProgressKey);
        final long preProgress = Math.max(savedProgress, view == null ? 0 : view.currentPosition());
        LOG.i("echo-autoRetry switch line: " + vod.playFlag + " -> " + flagToSwitch);
        if (view != null && view.isPageAlive()) {
            view.runOnUi(() -> host.view().toast(PlaybackController.str(R.string.player_switch_line, flagToSwitch)));
        }
        vod.playFlag = flagToSwitch;
        vod.playIndex = nextIndex;
        st.onLineSwitched();
        host.inheritProgressFrom(preProgressKey, preProgress);
        host.play(false);
        return true;
    }

    void handleResolvePlayUrlTimeout() {
        PlaybackAttemptState st = host.attemptState();
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
        if (!tryNextLineIfEnabled()) {
            host.stopMusicSessionForFailedPlayback();
            showErrorTip(PlaybackController.str(R.string.player_get_url_timeout));
        }
    }

    void handleResolvePlayUrlFailed(String err) {
        PlaybackAttemptState st = host.attemptState();
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
        host.stopMusicSessionForFailedPlayback();
        showErrorTip(err);
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
            if (!tryNextLineIfEnabled()) {
                host.stopMusicSessionForFailedPlayback();
                showErrorTip(PlaybackController.str(R.string.player_play_timeout));
            }
            return;
        }
        if (!autoRetry()) {
            host.stopMusicSessionForFailedPlayback();
            showErrorTip(PlaybackController.str(R.string.player_play_timeout));
        }
    }

    private void showErrorTip(String err) {
        PlaybackViewBridge view = host.view();
        if (view != null) view.showTip(err, false, true);
    }
}
