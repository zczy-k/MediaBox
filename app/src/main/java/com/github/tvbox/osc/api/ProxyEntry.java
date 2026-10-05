package com.github.tvbox.osc.api;

import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.Proxy;

import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/** /proxy 请求路由:按 do 类型分发到 jar/js/py 爬虫,直播源改走当前直播爬虫 */
final class ProxyEntry {
    private final ApiConfig owner;
    private final SpiderLoader spiderLoader;
    private String currentPlaySourceKey = "";

    ProxyEntry(ApiConfig owner, SpiderLoader spiderLoader) {
        this.owner = owner;
        this.spiderLoader = spiderLoader;
    }

    Object[] proxyLocal(Map<String, String> param) {
        SourceBean source = getCurrentProxySource(param);
        String api = source.getApi();

        String siteKey = param.get("siteKey");
        String action = param.get("do");

        boolean isJs = "js".equals(action);
        boolean isPy = "py".equals(action);
        boolean isLive = KV.get(HawkConfig.PLAYER_IS_LIVE, false);
        boolean isApiJs = api.contains(".js");
        boolean isApiPy = api.contains(".py");

        boolean canUseType3 = !TextUtils.isEmpty(siteKey)
                && source.getType() == 3
                && !isJs
                && !isPy
                && !isLive
                && !isApiJs
                && !isApiPy;

        if (canUseType3) {
            try {
                Spider spider = owner.getCSP(source);

                // spider.proxy 单独兜底:它抛异常不应吞掉下面的 jar/direct 兜底路径
                Object[] result = null;
                try {
                    result = spider.proxy(param);
                } catch (Throwable th) {
                    LOG.e("echo-proxy-route: spider.proxy error, fallback | " + th);
                }
                if (result != null) return result;
                LOG.e("echo-proxy-route: spider.proxy null, try proxyInvokeJar");

                result = spiderLoader.proxyInvokeJar(param);
                if (result != null) return result;
                LOG.e("echo-proxy-route: proxyInvokeJar null, try proxyDirect");

                result = proxyDirect(param);
                if (result != null) return result;
                LOG.e("echo-proxy-route: proxyDirect null, give up");

                return null;
            } catch (Throwable th) {
                LOG.e("echo-proxy-route: type3 route error | " + th + " | msg=" + th.getMessage());
                return null;
            }
        }

        if (isJs) {
            return spiderLoader.proxyInvokeJs(param);
        }

        if (isLive) {
            String liveApi = spiderLoader.getCurrentLiveSpider() != null ? spiderLoader.getCurrentLiveSpider() : "";

            if (liveApi.contains(".py")) {
                return spiderLoader.proxyInvokePy(param, spiderLoader.getCurrentLivePyKey());
            }
            if (liveApi.contains(".js")) {
                return spiderLoader.proxyInvokeJs(param);
            }
            return spiderLoader.proxyInvokeJar(param);
        }

        if (isPy) {
            return spiderLoader.proxyInvokePy(param, getCurrentPyKey());
        }

        if (isApiPy) {
            return spiderLoader.proxyInvokePy(param, getCurrentPyKey());
        }

        return spiderLoader.proxyInvokeJar(param);
    }

    private Object[] proxyDirect(Map<String, String> param) {
        try {
            String url = param.get("url");
            if (TextUtils.isEmpty(url)) return null;
            url = URLDecoder.decode(url, "UTF-8");
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
            if (!DefaultConfig.isVideoFormat(url)) return null;
            if (url.contains(".m3u8")) {
                param.put("url", url);
                param.put("go", "live");
                param.put("type", "m3u8");
                return Proxy.itv(param);
            }
            return null;
        } catch (Throwable th) {
            LOG.e("echo-proxy direct fallback error: " + th.getMessage());
            return null;
        }
    }

    private SourceBean getCurrentProxySource(Map<String, String> param) {
        String siteKey = param.get("siteKey");
        if (TextUtils.isEmpty(siteKey)) {
            siteKey = currentPlaySourceKey;
            if (!TextUtils.isEmpty(siteKey)) param.put("siteKey", siteKey);
        }
        SourceBean sourceBean = TextUtils.isEmpty(siteKey) ? null : owner.getSource(siteKey);
        return sourceBean == null ? owner.getHomeSourceBean() : sourceBean;
    }

    void setCurrentPlaySourceKey(String sourceKey) {
        currentPlaySourceKey = sourceKey == null ? "" : sourceKey;
    }

    private String getCurrentPyKey() {
        SourceBean sourceBean = getCurrentProxySource(new HashMap<String, String>());
        if (sourceBean.getApi().contains(".py")) {
            if (!sourceBean.getKey().equals(spiderLoader.getCurrentPyKey())) {
                spiderLoader.setCurrentPyKey(sourceBean.getKey());
                spiderLoader.pySpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt());
            }
            return spiderLoader.getCurrentPyKey();
        }
        return spiderLoader.getCurrentPyKey();
    }
}
