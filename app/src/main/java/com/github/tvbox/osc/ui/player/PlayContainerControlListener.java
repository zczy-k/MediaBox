package com.github.tvbox.osc.ui.player;

import android.text.TextUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.controller.VodControlListener;
import com.github.tvbox.osc.player.state.DanmuSettingSheetState;
import com.github.tvbox.osc.util.DanmuHelper;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.WatchProgressStore;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONObject;
import java.util.HashMap;

final class PlayContainerControlListener implements VodControlListener {

    private final PlayContainer container;

    PlayContainerControlListener(PlayContainer container) {
        this.container = container;
    }

    @Override
    public void showDanmuSetting() {
        if (!container.isAttached()) return;
        container.mController.getUiState().setDanmuSettingSheet(new DanmuSettingSheetState(() -> {
            container.openDanmuSearchSheet();
            return kotlin.Unit.INSTANCE;
        }, () -> {
            DanmuHelper.reset();
            container.applyDanmuSettings(true);
            return kotlin.Unit.INSTANCE;
        }));
    }

    @Override
    public boolean toggleDanmu() {
        return container.danmuLoadController != null && container.danmuLoadController.toggle();
    }

    @Override
    public void showEpisodes() {
        if (container.pageHost != null) container.pageHost.showEpisodeSheet();
    }

    @Override
    public void searchDanmuUi(boolean longClick) {
        VodInfo.VodSeries series = container.scheduler.vod() == null ? null : container.scheduler.currentSeries(container.scheduler.vod().playFlag, container.scheduler.vod().playIndex);
        ApiConfig.get().searchDanmuUi(container.scheduler.vod() == null ? "" : container.scheduler.vod().name, series == null ? "" : series.name, longClick);
    }

    @Override
    public void playNext(boolean rmProgress) {
        String preProgressKey = container.scheduler.progressKey();
        String preOwner = container.scheduler.progressOwner();
        container.playNext(rmProgress);
        if (rmProgress && preProgressKey != null)
            WatchProgressStore.clear(preOwner, preProgressKey);
    }

    @Override
    public void playPre() {
        container.playPrevious();
    }

    @Override
    public void changeParse(ParseBean pb) {
        container.scheduler.resetAutoRetryState();
        container.scheduler.clearTriedLines();
        container.scheduler.doParse(pb);
    }

    @Override
    public void updatePlayerCfg() {
        JSONObject persistCfg = container.scheduler.playerCfgForPersist();
        if (persistCfg == null) return;
        container.scheduler.vod().playerCfg = persistCfg.toString();
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, persistCfg));
    }

    @Override
    public void replay(boolean replay) {
        container.reviveEngineIfReleased();
        container.scheduler.resetAutoRetryState();
        container.scheduler.clearTriedLines();
        container.scheduler.setPlaybackStarted(false);
        if(replay){
            container.playViaScheduler(true);
        }else {
            container.replayCurrentAddress();
        }
    }

    @Override
    public void errReplay() {
        // ⚠️ 2026-10-18:这里的文案必须反映**页面侧的真实状态**,不能再是无条件的裸报错。
        //
        // 真机《怦然心动》:源池 317、自动链只问了 106 个,9 个候选全失败,
        // 而此时换源链**还在正常跑**(剩余 211 个源还没轮上),屏幕上却是「视频播放出错」——
        // 用户看到的是一个既不说发生了什么、也不说系统还在不在干活的错误提示。
        //
        // 现在按页面侧状态二分:
        //   - 还在找源 ⇒「正在查找其他片源…」(告诉他在等,别急着退)
        //   - 已确认无源 ⇒「该影片暂无有效片源」(告诉他结论,别反复点重播)
        // 判据来自 [PageHost.isSearchingOtherSources],由详情页给出(直播页默认 false)。
        int resId = container.pageHost != null && container.pageHost.isSearchingOtherSources()
                ? R.string.player_switching_source
                : R.string.player_no_valid_source;
        container.errorWithRetry(container.getContext().getString(resId), false);
    }

    @Override
    public void closeSubtitles() {
        container.closeSubtitles();
    }

    @Override
    public void selectSubtitle() {
        try {
            container.selectMySubtitle();
        } catch (Exception e) {
            LOG.e("PlayContainer", e);
        }
    }

    @Override
    public void selectAudioTrack() {
        container.selectMyAudioTrack();
    }

    @Override
    public void selectVideoTrack() {
        container.selectMyVideoTrack();
    }

    @Override
    public void prepared() {
        container.initSubtitleView();
        if (container.mVideoView != null) container.mVideoView.prepared();
        container.startDanmuIfReady();
    }
    @Override
    public void clickCast() {
        container.showCastDialog();
    }

    @Override
    public void setAllowSwitchPlayer(boolean isAllow){container.scheduler.setAllowSwitchPlayer(isAllow);}

    @Override
    public void setAllowDecodeFallback(boolean isAllow){container.scheduler.setAllowDecodeFallback(isAllow);}
}
