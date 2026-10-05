package com.github.tvbox.osc.player.usecase;

import android.os.Build;
import android.webkit.WebView;

import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.VideoParseRuler;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * WebView 脚本嗅探 / BOM 代理用例（从 VodController:1668-1679、1852-1879 剥离，
 * Compose 化改造 阶段 0）。纯工具逻辑，与 UI 无关。
 */
public final class WebParseUseCase {

    /**
     * 尝试去 bom：非本地代理地址的 m3u8 链接改走本地 BOM 代理。
     */
    public String getWebPlayUrlIfNeeded(String webPlayUrl) {
        if (webPlayUrl != null && !webPlayUrl.contains("127.0.0.1:9978") && webPlayUrl.contains(".m3u8")) {
            try {
                String urlEncode = URLEncoder.encode(webPlayUrl, "UTF-8");
                LOG.i("echo-BOM-------");
                return ControlManager.get().getAddress(true) + "proxy?go=bom&url=" + urlEncode;
            } catch (UnsupportedEncodingException e) {
                LOG.e("WebParseUseCase", e);
            }
        }
        return webPlayUrl;
    }

    /**
     * 按源配置的 clickSelector / 域名脚本在 WebView 上执行 JS（嗅探辅助点击）。
     */
    public void evaluateScript(SourceBean sourceBean, String url, WebView web_view) {
        String clickSelector = sourceBean.getClickSelector().trim();
        clickSelector = clickSelector.isEmpty() ? VideoParseRuler.getHostScript(url) : clickSelector;
        if (!clickSelector.isEmpty()) {
            String selector;
            if (clickSelector.contains(";") && !clickSelector.endsWith(";")) {
                String[] parts = clickSelector.split(";", 2);
                if (!url.contains(parts[0])) {
                    return;
                }
                selector = parts[1].trim();
            } else {
                selector = clickSelector.trim();
            }
            // 构造点击的 JS 代码
            String js = selector;
            LOG.i("echo-javascript:" + js);
            if (web_view != null) {
                //4.4以上才支持这种写法
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    web_view.evaluateJavascript(js, null);
                } else {
                    web_view.loadUrl("javascript:" + js);
                }
            }
        }
    }
}
