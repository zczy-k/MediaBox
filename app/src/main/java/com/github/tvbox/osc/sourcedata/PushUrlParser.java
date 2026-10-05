package com.github.tvbox.osc.sourcedata;

import android.text.TextUtils;

import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.HeaderGuard;
import com.github.tvbox.osc.util.LOG;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Iterator;

/**
 * 推送源(push://)URL 的解析与相关结果合成。
 *
 * <p>推送链接把标记头(urlencoded 的 JSON)用 {@code @Headers=...@} 夹在 URL 里:摘出来、URL 还原干净。
 * 标记头同样要过 {@link HeaderGuard}(会进 OkGo 与本地 m3u8 净化)。
 */
final class PushUrlParser {
    private PushUrlParser() {
    }

    static final String PUSH_AGENT = "push_agent";
    static final String PUSH_FALLBACK = "push_fallback";
    static final String PUSH_HEADERS_MARKER = "@Headers=";

    static boolean isPushFallback(String sourceKey, SourceBean sourceBean) {
        return PUSH_FALLBACK.equals(sourceKey) || (sourceBean != null && PUSH_AGENT.equals(sourceBean.getKey()) && sourceBean.getType() == -1);
    }

    static boolean isCastPushUrl(String url) {
        return !TextUtils.isEmpty(url) && url.contains(PUSH_HEADERS_MARKER);
    }


    static JSONObject createPushPlayResult(String rawUrl, PushUrl pushUrl, String progressKey, String subtitleKey, String playFlag) {
        try {
            JSONObject result = new JSONObject();
            result.put("key", rawUrl);
            result.put("proKey", progressKey);
            result.put("subtKey", subtitleKey);
            result.put("flag", playFlag);
            result.put("parse", 0);
            result.put("url", pushUrl.url);
            mergePushHeaders(result, pushUrl);
            return result;
        } catch (Throwable th) {
            LOG.e("SourceViewModel", th);
            return null;
        }
    }


    static void mergePushHeaders(JSONObject result, PushUrl pushUrl) {
        if (result == null || pushUrl == null || pushUrl.headers.isEmpty()) return;
        try {
            JSONObject header = result.optJSONObject("header");
            if (header == null) header = result.optJSONObject("headers");
            if (header == null) header = new JSONObject();
            for (String key : pushUrl.headers.keySet()) {
                header.put(key, pushUrl.headers.get(key));
            }
            result.put("header", header);
        } catch (Throwable th) {
            LOG.e("SourceViewModel", "merge push headers failed", th);
        }
    }

    static PushUrl createPushUrl(String rawUrl) {
        PushUrl pushUrl = new PushUrl();
        pushUrl.url = rawUrl == null ? "" : rawUrl;
        return pushUrl;
    }

    static PushUrl parsePushUrl(String rawUrl) {
        PushUrl pushUrl = createPushUrl(rawUrl);
        parseMarkedHeaders(pushUrl);
        return pushUrl;
    }

    private static boolean parseMarkedHeaders(PushUrl pushUrl) {
        String marker = PUSH_HEADERS_MARKER;
        int start = pushUrl.url.indexOf(marker);
        if (start < 0) return false;
        int valueStart = start + marker.length();
        int end = pushUrl.url.indexOf('@', valueStart);
        if (end < 0) return false;
        try {
            String text = URLDecoder.decode(pushUrl.url.substring(valueStart, end), "UTF-8");
            JSONObject json = new JSONObject(text);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = json.optString(key, "");
                if (TextUtils.isEmpty(key)) continue;
                // push 标记头同样会进 OkGo 与本地 m3u8 净化:非法字符挡在入口
                if (!HeaderGuard.isSendable(key, value)) {
                    LOG.i("echo-push-header-skip:" + key);
                    continue;
                }
                pushUrl.headers.put(key, value);
            }
            pushUrl.url = pushUrl.url.substring(0, start) + pushUrl.url.substring(end + 1);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static final class PushUrl {
        String url = "";
        HashMap<String, String> headers = new HashMap<>();
    }

}
