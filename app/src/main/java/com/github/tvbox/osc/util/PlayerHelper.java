package com.github.tvbox.osc.util;

import com.github.tvbox.osc.util.LOG;
import android.app.Activity;
import android.content.Context;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.player.ExoMediaPlayerFactory;
import com.github.tvbox.osc.player.ExoPlayer;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.render.SurfaceRenderViewFactory;
import com.github.tvbox.osc.player.thirdparty.Kodi;
import com.github.tvbox.osc.player.thirdparty.MXPlayer;
import com.github.tvbox.osc.player.thirdparty.ReexPlayer;
import com.github.tvbox.osc.player.thirdparty.VlcPlayer;
import com.github.tvbox.osc.util.KV;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import xyz.doikki.videoplayer.player.PlayerFactory;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.RenderViewFactory;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

public class PlayerHelper {
    public static void updateCfg(VideoView videoView, JSONObject playerCfg) {
        updateCfg(videoView,playerCfg,-1);
    }

    /** forcePlayerType 为历史遗留(内核对仅剩 EXO,不再有可强制的目标),保留入参以稳定既有调用方 */
    public static void updateCfg(VideoView videoView, JSONObject playerCfg,int forcePlayerType) {
        int renderType = KV.get(HawkConfig.PLAY_RENDER, 1);
        String exoDecode = KV.get(HawkConfig.EXO_DECODE, "硬解码"); // i18n: keep
        int scale = KV.get(HawkConfig.PLAY_SCALE, 0);
        try {
            renderType = playerCfg.getInt("pr");
            scale = playerCfg.getInt("sc");
        } catch (JSONException e) {
            LOG.e("PlayerHelper", e);
        }
        // exo 键单独用 optString 读(2026-09-17):不塞进上面的 try —— 该 try 遇第一个缺失键即中断,
        // 老播放记录/直播配置没有 exo 键时会把后面的 sc 一起吞掉
        exoDecode = playerCfg.optString("exo", exoDecode);
        // EXO 解码方式下发(2026-09-17):进程级静态位,与 videoView 实例无关(故不放在下面的判空块里),
        // 每次起播前按"本剧配置 → 全局设置"的有效值推一次
        boolean exoDecodeChanged = applyExoDecode(exoDecode);
        PlayerFactory playerFactory = ExoMediaPlayerFactory.create();
        RenderViewFactory renderViewFactory = null;
        switch (renderType) {
            case 0:
            default:
                renderViewFactory = TextureRenderViewFactory.create();
                break;
            case 1:
                renderViewFactory = SurfaceRenderViewFactory.create();
                break;
        }
        if(videoView!=null){
            videoView.setPlayerFactory(playerFactory);
            if (videoView instanceof MyVideoView) {
                // EXO 解码方式变了且当前还活着一个 EXO 内核(换集复用路径):media3 不会重选解码器,
                // 只改静态位不生效 —— 标记本次起播必须重建内核(见 MyVideoView.consumeKernelRebuildRequired)
                if (exoDecodeChanged && ((MyVideoView) videoView).getMediaPlayer() instanceof ExoPlayer) {
                    ((MyVideoView) videoView).requireKernelRebuild();
                    LOG.i("echo-exo-decode-changed: rebuild kernel on next start");
                }
            }
            videoView.setRenderViewFactory(renderViewFactory);
            videoView.setScreenScaleType(scale);
        }
    }

    private static boolean applyExoDecode(String exoDecode) {
        boolean prefer = "软解码".equals(exoDecode); // i18n: keep
        if (ExoPlayer.isPreferSoftwareDecode() == prefer) return false;
        ExoPlayer.setPreferSoftwareDecode(prefer);
        return true;
    }

    /**
     * 存活内核**已生效**的解码方式是否与 cfg 目标值一致;静态位只在起播链路下发,而 media3 不给复用内核重选解码器 ——
     * 不一致就只能重建内核(D6 同片接管这类不走起播的路径据此判断)。
     */
    public static boolean isExoDecodeApplied(JSONObject playerCfg) {
        String exoDecode = playerCfg == null ? null : playerCfg.optString("exo", "硬解码"); // i18n: keep
        return isExoDecodeApplied(exoDecode, ExoPlayer.isPreferSoftwareDecode());
    }

    /** 上一条的口径本体(exo 值只认"软解码",缺键/空串按硬解);独立出来供 JVM 单测锁真值表 */
    static boolean isExoDecodeApplied(String exoDecode, boolean preferSoftwareDecode) {
        return "软解码".equals(exoDecode) == preferSoftwareDecode; // i18n: keep
    }

    public static boolean isLocalProxyUrl(String url) {
        if (url == null) return false;
        return url.startsWith("http://127.0.0.1") || url.startsWith("https://127.0.0.1")
                || url.startsWith("http://localhost") || url.startsWith("https://localhost");
    }

    public static HashMap<String, String> extractPlayHeaders(JSONObject playResult) {
        if (playResult == null) return null;
        HashMap<String, String> headers = new HashMap<>();
        appendJsonHeaders(headers, playResult.opt("header"));
        appendJsonHeaders(headers, playResult.opt("headers"));
        return headers.isEmpty() ? null : headers;
    }

    /** 合并单个 header(s) 字段:接受 JSONObject 或 JSON 文本;非法内容静默跳过(保持旧行为) */
    public static void appendJsonHeaders(HashMap<String, String> headers, Object rawHeaders) {
        if (headers == null || rawHeaders == null || rawHeaders == JSONObject.NULL) return;
        try {
            JSONObject json = null;
            if (rawHeaders instanceof JSONObject) {
                json = (JSONObject) rawHeaders;
            } else if (rawHeaders instanceof String) {
                String text = ((String) rawHeaders).trim();
                if (!TextUtils.isEmpty(text)) {
                    json = new JSONObject(text);
                }
            }
            if (json == null) return;
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!TextUtils.isEmpty(key)) {
                    headers.put(key, json.optString(key, ""));
                }
            }
        } catch (Throwable th) {
            LOG.e("PlayerHelper", "play headers parse failed", th);
        }
    }

    /** 播放器名;每次调用重取文案(不缓存字符串 —— 缓存会让切语言后停在旧语言) */
    public static String getPlayerName(int playType) {
        switch (playType) {
            case 10:
                return str(R.string.player_mx);
            case 11:
                return str(R.string.player_reex);
            case 12:
                return str(R.string.player_kodi);
            case 13:
                return str(R.string.player_nearby_tvbox);
            case 14:
                return str(R.string.player_vlc);
            default:
                return str(R.string.player_exo);
        }
    }

    public static HashMap<Integer, String> getPlayersInfo() {
        HashMap<Integer, String> playersInfo = new HashMap<>();
        for (int type : new int[]{2, 10, 11, 12, 13, 14}) {
            playersInfo.put(type, getPlayerName(type));
        }
        return playersInfo;
    }

    private static HashMap<Integer, Boolean> mPlayersExistInfo = null;

    public static void invalidatePlayersExistInfo() {
        mPlayersExistInfo = null;
    }

    public static HashMap<Integer, Boolean> getPlayersExistInfo() {
        if (mPlayersExistInfo == null) {
            HashMap<Integer, Boolean> playersExist = new HashMap<>();
            playersExist.put(2, true);
            playersExist.put(10, MXPlayer.getPackageInfo() != null);
            playersExist.put(11, ReexPlayer.getPackageInfo() != null);
            playersExist.put(12, Kodi.getPackageInfo() != null);
            playersExist.put(13, RemoteTVBox.getAvalible() != null);
            playersExist.put(14, VlcPlayer.getPackageInfo() != null);
            mPlayersExistInfo = playersExist;
        }
        return mPlayersExistInfo;
    }

    public static Boolean getPlayerExist(int playType) {
        HashMap<Integer, Boolean> playersExistInfo = getPlayersExistInfo();
        if (playersExistInfo.containsKey(playType)) {
            return playersExistInfo.get(playType);
        } else {
            return false;
        }
    }

    public static ArrayList<Integer> getExistPlayerTypes() {
        HashMap<Integer, Boolean> playersExistInfo = getPlayersExistInfo();
        ArrayList<Integer> existPlayers = new ArrayList<>();
        for(Integer playerType : playersExistInfo.keySet()) {
            if (playersExistInfo.get(playerType)) {
                existPlayers.add(playerType);
            }
        }
        return existPlayers;
    }

    public static Boolean runExternalPlayer(int playerType, Activity activity, String url, String title, String subtitle, HashMap<String, String> headers) {
        return runExternalPlayer(playerType, activity, url, title, subtitle, headers);
    }

    public static Boolean runExternalPlayer(int playerType, Activity activity, String url, String title, String subtitle, HashMap<String, String> headers, long progress) {
        boolean callResult = false;
        switch (playerType) {
            case 10: {
                callResult = MXPlayer.run(activity, url, title, subtitle, headers);
                break;
            }
            case 11: {
                callResult = ReexPlayer.run(activity, url, title, subtitle, headers);
                break;
            }
            case 12: {
                callResult = Kodi.run(activity, url, title, subtitle, headers);
                break;
            }
            case 13: {
                callResult = RemoteTVBox.run(activity, url, title, subtitle, headers);
                break;
            }
            case 14: {
                callResult = VlcPlayer.run(activity, url, title, subtitle, progress);
                break;
            }
        }
        return callResult;
    }

    public static String getRenderName(int renderType) {
        if (renderType == 1) {
            return "SurfaceView";
        } else {
            return "TextureView";
        }
    }

    /** 画面缩放名;每次调用重取文案(不缓存字符串 —— 缓存会让切语言后停在旧语言) */
    public static String getScaleName(int screenScaleType) {
        switch (screenScaleType) {
            case VideoView.SCREEN_SCALE_16_9:
                return "16:9";
            case VideoView.SCREEN_SCALE_4_3:
                return "4:3";
            case VideoView.SCREEN_SCALE_MATCH_PARENT:
                return str(R.string.player_scale_fill);
            case VideoView.SCREEN_SCALE_ORIGINAL:
                return str(R.string.player_scale_origin);
            case VideoView.SCREEN_SCALE_CENTER_CROP:
                return str(R.string.player_scale_crop);
            default:
                return str(R.string.common_default);
        }
    }

    private static String str(int resId) {
        Context app = AppContextHolder.context();
        return app == null ? "" : LanguageManager.INSTANCE.localized(app).getString(resId);
    }

    public static String getDisplaySpeed(long speed,boolean show) {
        if(speed > 1048576)
            return new DecimalFormat("#.00").format(speed / 1048576d) + "MB/s";
        else if(speed > 1024)
            return (speed / 1024) + "KB/s";
        else
            return speed > 0?speed + "B/s":(show?"0B/s":"");
    }
}
