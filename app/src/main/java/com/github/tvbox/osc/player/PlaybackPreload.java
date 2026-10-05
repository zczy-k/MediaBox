package com.github.tvbox.osc.player;

import com.github.tvbox.osc.sourcedata.SourceViewModel;

import org.json.JSONObject;

import xyz.doikki.videoplayer.player.VideoView;

/**
 * 预载调度:何时喂快照、何时取结果、何时作废(目标评估时机与冷却期都在 {@link PreloadCoordinator})。
 * 快照由页面侧组装(需要页面上下文与真实内核实例)。
 */
final class PlaybackPreload {

    interface Host {
        PlaybackViewBridge view();

        /** 预载协调器需要取流实例;为空时宿主先建(见 PlaybackController.initFetch) */
        SourceViewModel sourceViewModel();

        void ensureFetch();

        /** 预载结果命中:标记来源(失败时走重新取流)并把结果喂给取流链路 */
        void onPreloadedResult(JSONObject info);
    }

    private final Host host;

    private PreloadCoordinator preloadCoordinator;
    private PreloadManagerHolder.ReadyListener preloadReadyListener;

    PlaybackPreload(Host host) {
        this.host = host;
    }

    /** 建立预载协调器与"下一集已就绪"回调(页面 init 时调用一次,须在取流实例建立之后) */
    void init() {
        if (host.sourceViewModel() == null) host.ensureFetch();
        preloadCoordinator = new PreloadCoordinator(host.sourceViewModel());
        preloadReadyListener = new PreloadManagerHolder.ReadyListener() {
            @Override
            public void onPreloadReady(String url) {
                PlaybackViewBridge view = host.view();
                if (view == null || !view.isPageAlive()) return;
                view.runOnUi(() -> {
                    PlaybackViewBridge current = host.view();
                    if (current != null) current.showPreloadReadyTip();
                });
            }
        };
        PreloadManagerHolder.setReadyListener(preloadReadyListener);
    }

    /**
     * 播放状态变化驱动预载评估(页面状态回调里调用):
     * STATE_PLAYING 正片稳定 → 延迟评估;STATE_BUFFERING 弱网 → 让路(清数据 + 冷却);
     * STATE_BUFFERED 缓冲结束 → 补一次评估(dkplayer 的 STATE_PLAYING 只在首帧发一次,不补枪则拖一次进度条就永久停摆)。
     */
    void onPlayerState(int playState) {
        if (preloadCoordinator == null) return;
        PlaybackViewBridge view = host.view();
        // 无页面(仅引擎)时快照为空:跳过评估(预载需要页面上下文与集信息)
        if (view == null) return;
        if (playState == VideoView.STATE_PLAYING || playState == VideoView.STATE_BUFFERED) {
            preloadCoordinator.scheduleEvaluate(view.buildPreloadSnapshot());
        } else if (playState == VideoView.STATE_BUFFERING) {
            preloadCoordinator.onMainPlayerBuffering();
        }
    }

    /**
     * 起播前消费预载结果。
     *
     * @return true = 命中并已把结果交给取流链路(调用方不要再发起取流)
     */
    boolean consumeResult(String progressKey) {
        if (preloadCoordinator == null) return false;
        JSONObject preResult = preloadCoordinator.consumeResult(progressKey);
        if (preResult != null) {
            // 直链可能已过期:标记来源,失败时走 retryWithFreshResolve 重取一次
            host.onPreloadedResult(preResult);
            return true;
        }
        // 未复用 = 切到的不是预载目标集(或缓存过期):预载数据失效,清掉
        preloadCoordinator.dropPreloadData();
        return false;
    }

    /** 切集/换线/换源/重播:作废在途预解析与预载数据(稳定播放后重新评估) */
    void invalidate() {
        if (preloadCoordinator != null) preloadCoordinator.invalidate();
    }

    /** 页面销毁:停协调器 + 注销就绪回调(防页面销毁后回调/Toast 残留) */
    void destroy() {
        PreloadManagerHolder.clearReadyListener(preloadReadyListener);
        preloadReadyListener = null;
        if (preloadCoordinator != null) {
            preloadCoordinator.destroy();
            preloadCoordinator = null;
        }
    }
}
