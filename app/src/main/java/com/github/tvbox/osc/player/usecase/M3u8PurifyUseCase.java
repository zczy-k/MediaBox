package com.github.tvbox.osc.player.usecase;

import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.M3u8;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.HttpHeaders;
import com.lzy.okgo.model.Response;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * m3u8 去广告用例（从 VodController:1667-1834 剥离，Compose 化改造 阶段 0）。
 * <p>
 * 职责：拉取 m3u8 → 识别重定向（#EXT-X-STREAM-INF）→ M3u8.purify 去广告 →
 * 命中广告时走本地代理播放，否则回退直链。纯网络/解析逻辑，与 UI 无关。
 */
public final class M3u8PurifyUseCase {

    public interface Callback {
        void startPlayUrl(String url, HashMap<String, String> headers);

        void onM3u8ProxyUrl(String proxyUrl, String sourceUrl);
    }

    private final Callback callback;

    /**
     * ⚠️ 构造参数里的 `Context` 已随「已移除视频广告 N 条」弹窗一并去掉。
     *
     * <p>本类自述"纯网络/解析逻辑,与 UI 无关",而 Context 此前**只为那一个 Toast 服务**
     * (经 {@code context.getApplicationContext()})。提示删掉后它就彻底没有消费者了 ——
     * 留着一个不用的 Context 参数只会让人误以为这里还要碰 UI。
     * 顺带清掉的孤儿:私有 helper `str()` 与 `R`/`App`/`LanguageManager` 三个 import
     * (它们的唯一消费者也是那个 Toast)。
     */
    public M3u8PurifyUseCase(Callback callback) {
        this.callback = callback;
    }

    public void playM3u8(final String url, final HashMap<String, String> headers) {
        if (url.contains("url=")) {
            callback.startPlayUrl(url, headers);
            return;
        }
        OkGo.getInstance().cancelTag("m3u8-1");
        OkGo.getInstance().cancelTag("m3u8-2");
        final HttpHeaders okGoHeaders = new HttpHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                okGoHeaders.put(entry.getKey(), entry.getValue());
            }
        }
        OkGo.<String>get(url)
                .tag("m3u8-1")
                .headers(okGoHeaders)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String content = response.body();
                        if (!content.startsWith("#EXTM3U")) {
                            callback.startPlayUrl(url, headers);
                            return;
                        }
                        String forwardUrl = extractForwardUrl(url, content);
                        if (forwardUrl.isEmpty()) {
                            LOG.i("echo-m3u81-to-play");
                            processM3u8Content(url, content, headers);
                        } else {
                            fetchAndProcessForwardUrl(forwardUrl, headers, okGoHeaders, url);
                        }
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LOG.e("echo-m3u8请求错误1: " + response.getException());
                        callback.startPlayUrl(url, headers);
                    }
                });
    }

    private String extractForwardUrl(String baseUrl, String content) {
        // 不要给 limit(2026-09-12 修复 Bug):原为 split(..., 50),limit>0 时最后一个元素是"第 50 行到文末"的整块,
        // 于是第一个 #EXT-X-STREAM-INF 出现在第 50 行之后会被漏识别(master 被当媒体列表),第 49 行则会拼出跨行畸形 URL
        String[] lines = content.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // 只需要找接下来的几行
                for (int j = i + 1; j < lines.length; j++) {
                    String targetLine = lines[j].trim();
                    if (targetLine.isEmpty()) continue;
                    if (isValidM3u8Line(targetLine)) {
                        return resolveForwardUrl(baseUrl, targetLine);
                    }
                }
            }
        }
        return "";
    }

    private boolean isValidM3u8Line(String line) {
        return !line.startsWith("#") && (line.endsWith(".m3u8") || line.contains(".m3u8?"));
    }

    private void processM3u8Content(String url, String content, HashMap<String, String> headers) {
        String basePath = getBasePath(url);
        String purified = M3u8.purify(basePath, content);
        // 2026-09-13:只在真正走代理时才写入内容槽 —— 无广告(走直链)的集不写,
        // 避免把在播集的槽位冲掉;proxyUrl 带本次生成的键(?k=),服务端按键取内容
        if (purified == null || M3u8.currentAdCount == 0) {
            LOG.i("echo-m3u8内容解析：未检测到广告");
            callback.startPlayUrl(url, headers);
        } else {
            String key = RemoteServer.putM3u8Content(purified);
            String proxyUrl = ControlManager.get().getAddress(true) + "proxyM3u8?k=" + key;
            callback.onM3u8ProxyUrl(proxyUrl, url);
            callback.startPlayUrl(proxyUrl, headers);
            // ⚠️ 这条日志是「命中广告分支」的唯一可观测点,别删。
            // 用户报「去广告弹窗还在」时,靠它能立刻分清两种情况:
            //   ① 本行出现 → 命中广告分支确实在跑(那么弹窗只可能来自别处);
            //   ② 本行不出现 → 根本没走到这条分支,弹窗与去广告无关。
            // 没有它就只能靠猜,而"猜"在本项目已经吃过多次亏。
            LOG.i("echo-m3u8命中广告 removed=" + M3u8.currentAdCount + " proxy=" + (key != null));
            // ⚠️ 刻意**不再**弹「已移除视频广告 N 条」。
            // 理由(产品决策):去广告是**本来就该做**的事,不是需要用户知晓的"成果" ——
            // 弹出来只是在每次起播时干扰一下画面。命中广告与否,看播放列表长度就能感知,
            // 不需要一条瞬时提示来宣告。
        }
    }

    private void fetchAndProcessForwardUrl(final String forwardUrl, final HashMap<String, String> headers,
                                           HttpHeaders okGoHeaders, final String fallbackUrl) {
        OkGo.<String>get(forwardUrl)
                .tag("m3u8-2")
                .headers(okGoHeaders)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String content = response.body();
                        LOG.i("echo-m3u82-to-play");
                        processM3u8Content(forwardUrl, content, headers);
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LOG.e("echo-重定向 m3u8 请求错误: " + response.getException());
                        callback.startPlayUrl(fallbackUrl, headers);
                    }
                });
    }

    private String getBasePath(String url) {
        int ilast = url.lastIndexOf('/');
        return url.substring(0, ilast + 1);
    }

    private String resolveForwardUrl(String baseUrl, String line) {
        try {
            // 使用 URL 构造器自动解析相对路径
            URL base = new URL(baseUrl);
            URL resolved = new URL(base, line);
            return resolved.toString();
        } catch (MalformedURLException e) {
            // 出现异常时可以记录日志，并返回原始 line
            LOG.e("echo-resolveForwardUrl异常: " + e.getMessage());
            return line;
        }
    }
}
