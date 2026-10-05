package com.github.tvbox.osc.bean;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.KV;

import org.json.JSONException;
import org.json.JSONObject;

import xyz.doikki.videoplayer.player.VideoView;

public class LivePlayerManager {
    JSONObject defaultPlayerConfig = new JSONObject();
    JSONObject currentPlayerConfig;

    public void init(VideoView videoView) {
        try {
            defaultPlayerConfig.put("exo", KV.get(HawkConfig.EXO_DECODE, "硬解码")); // i18n: keep
            defaultPlayerConfig.put("pr", KV.get(HawkConfig.PLAY_RENDER, 1));
            defaultPlayerConfig.put("sc", KV.get(HawkConfig.LIVE_PLAY_SCALE, 0));
        } catch (JSONException e) {
            LOG.e("LivePlayerManager", e);
        }
        getDefaultLiveChannelPlayer(videoView);
    }

    public void getDefaultLiveChannelPlayer(VideoView videoView) {
        PlayerHelper.updateCfg(videoView, defaultPlayerConfig);
        try {
            currentPlayerConfig = new JSONObject(defaultPlayerConfig.toString());
        } catch (JSONException e) {
            LOG.e("LivePlayerManager", e);
        }
    }

    /**
     * BugReview:异步回调(如代理配置加载)可能在 mVideoView 已释放(init 未执行)时触达播放链路,
     * currentPlayerConfig 此时为 null;统一回落到 defaultPlayerConfig,杜绝 NPE(2026-09-10 22:34 崩溃)
     */
    private JSONObject currentOrDefaultConfig() {
        return currentPlayerConfig != null ? currentPlayerConfig : defaultPlayerConfig;
    }

    /** 直播「播放解码」档位下标:0=硬解 1=软解(取值"直播配置 → 缺省全局 EXO_DECODE") */
    public int getLivePlayerType() {
        String decode = currentOrDefaultConfig().optString("exo", KV.get(HawkConfig.EXO_DECODE, "硬解码")); // i18n: keep
        return "软解码".equals(decode) ? 1 : 0; // i18n: keep
    }

    public int getLivePlayerScale() {
        return currentOrDefaultConfig().optInt("sc", 0);
    }

    public void changeLivePlayerType(VideoView videoView, int playerType) {
        JSONObject playerConfig;
        try {
            playerConfig = new JSONObject(currentOrDefaultConfig().toString());
        } catch (JSONException e) {
            playerConfig = new JSONObject();
        }
        try {
            String decode = playerType == 1 ? "软解码" : "硬解码"; // i18n: keep
            playerConfig.put("exo", decode); // i18n: keep
            defaultPlayerConfig.put("exo", decode); // i18n: keep
        } catch (JSONException e) {
            LOG.e("LivePlayerManager", e);
        }
        PlayerHelper.updateCfg(videoView, playerConfig);
        currentPlayerConfig = playerConfig;
    }

    public void changeLivePlayerScale(@NonNull VideoView videoView, int playerScale){
        videoView.setScreenScaleType(playerScale);
        KV.put(HawkConfig.LIVE_PLAY_SCALE, playerScale);

        JSONObject playerConfig;
        try {
            playerConfig = new JSONObject(currentOrDefaultConfig().toString());
        } catch (JSONException e) {
            playerConfig = new JSONObject();
        }
        try {
            playerConfig.put("sc", playerScale);
            defaultPlayerConfig.put("sc", playerScale);
        } catch (JSONException e) {
            LOG.e("LivePlayerManager", e);
        }

        currentPlayerConfig = playerConfig;
    }
}
