package com.github.tvbox.osc.player.usecase;

import android.os.Handler;
import android.os.Looper;

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
 *
 * <p><b>2026-10-16 止血：本类的唯一契约是"一定要把播放权交回去"。</b>
 * 真机取证（《给阿嬷的情书》，v1.1.54）：三次进入净化链，零条后续分支日志 ——
 * 请求发出去就没回来过，既没报"无广告"也没报"请求错误"。而起播超时定时器是在
 * {@code playUrl} 入口 arm 的、算的是"这条线路起播失败"，于是净化悬着的那 7 秒
 * 直接把预算吃光，用户看到的就是<b>「打开去广告 → 这一集播不了」</b>。
 * 所以从现在起：
 * <ul>
 *   <li>整条链有一把 {@link #PURIFY_TOTAL_TIMEOUT_MS} 的死线，到点<b>交回原链</b>（不清洗、直连播放）；</li>
 *   <li>所有出口都必须经过 {@link #settle}，一次调用只认第一次交回 —— 迟到的回包不许再起播一次；</li>
 *   <li>新一轮开始后，旧一轮的回调一律作废（它连"要播哪一集"都已经不是当前的了）。</li>
 * </ul>
 * 直说代价：命中清洗的概率本来就很低（清洗用的是 OkGo，和播放内核不是同一个 TLS 客户端，
 * 恰恰是最需要洗的那批站拒绝它），所以这里的收益不是"洗得更准"，是<b>"开着不再扣播放"</b>。
 */
public final class M3u8PurifyUseCase {

    /**
     * 一次净化的**总**上限（master 清单要拉两次，所以是把整条链算在内的一把死线）。
     *
     * <p>为什么是 3 秒：线路起播预算实测 4~17 秒，净化再吃掉 7 秒等于让"开着清洗"这一档
     * 必然超时。3 秒是能洗成（同站清单一般 200~600ms 回包）又不至于把用户耐心吃掉的宽度。
     * 超过它就直接播原链 —— 用户看上片比洗掉几条广告重要。
     */
    private static final long PURIFY_TOTAL_TIMEOUT_MS = 3000L;

    public interface Callback {
        void startPlayUrl(String url, HashMap<String, String> headers);

        void onM3u8ProxyUrl(String proxyUrl, String sourceUrl);
    }

    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 正在进行的一轮净化（一次只跑一轮；新一轮开始时旧一轮当场作废） */
    private Run currentRun;

    public M3u8PurifyUseCase(Callback callback) {
        this.callback = callback;
    }

    /** 一轮净化的收口状态：原始地址（fail-open 就交回它）、守卫、有没有已经交回过 */
    private static final class Run {
        final String sourceUrl;
        final HashMap<String, String> headers;
        final long startedAt = android.os.SystemClock.elapsedRealtime();
        boolean finished;
        Runnable guard;

        Run(String sourceUrl, HashMap<String, String> headers) {
            this.sourceUrl = sourceUrl;
            this.headers = headers;
        }
    }

    public void playM3u8(final String url, final HashMap<String, String> headers) {
        final Run run = new Run(url, headers);
        synchronized (this) {
            invalidateCurrentRun();
            currentRun = run;
        }
        if (url.contains("url=")) {
            handOffRaw(run, "url-param-skip");
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
        armGuard(run);
        OkGo.<String>get(url)
                .tag("m3u8-1")
                .headers(okGoHeaders)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String content = response.body();
                        LOG.i("echo-m3u8-purify req=1 cost=" + elapsed(run) + "ms");
                        if (content == null || !content.startsWith("#EXTM3U")) {
                            // 不是 m3u8（直链 mp4 / 网关返回了别的东西）：没什么可洗，交回原链
                            handOffRaw(run, "not-m3u8");
                            return;
                        }
                        if (M3u8PurifyRules.isMasterPlaylist(content)) {
                            String forwardUrl = extractForwardUrl(url, content);
                            if (forwardUrl.isEmpty()) {
                                // 认不出能跟的下一档 ⇒ 把 master **原样**交回内核，由内核自己选档。
                                // 绝不能拿 master 去 purify：那是把"哪一档"的清单当"哪些片段"的清单洗。
                                handOffRaw(run, "master-unfollowable");
                                return;
                            }
                            fetchAndProcessForwardUrl(run, forwardUrl, okGoHeaders);
                        } else {
                            LOG.i("echo-m3u8-purify branch=direct-list");
                            processM3u8Content(run, url, content);
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
                        handOffRaw(run, "fetch-error-1");
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
                    if (M3u8PurifyRules.isFollowableVariant(targetLine)) {
                        return resolveForwardUrl(baseUrl, targetLine);
                    }
                }
            }
        }
        return "";
    }

    private void processM3u8Content(Run run, String url, String content) {
        String basePath = getBasePath(url);
        String purified = M3u8.purify(basePath, content);
        // 2026-09-13:只在真正走代理时才写入内容槽 —— 无广告(走直链)的集不写,
        // 避免把在播集的槽位冲掉;proxyUrl 带本次生成的键(?k=),服务端按键取内容
        // 2026-10-16:「洗没洗」改成本次调用的行数差,不读 M3u8.currentAdCount(静态、跨调用会被覆盖)
        int removed = M3u8PurifyRules.removedCount(content, purified);
        if (removed <= 0) {
            handOffRaw(run, purified == null ? "purify-null" : "no-ad");
            return;
        }
        String key = RemoteServer.putM3u8Content(purified);
        String proxyUrl = ControlManager.get().getAddress(true) + "proxyM3u8?k=" + key;
        // ⚠️ 这条日志是「命中广告分支」的唯一可观测点,别删。
        // 判读口径:settle=proxy 出现 = 真的洗到了并交给本地代理;settle=raw 才是退回原链。
        handOffProxy(run, proxyUrl, removed, key);
    }

    private void fetchAndProcessForwardUrl(final Run run, final String forwardUrl, HttpHeaders okGoHeaders) {
        OkGo.<String>get(forwardUrl)
                .tag("m3u8-2")
                .headers(okGoHeaders)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String content = response.body();
                        LOG.i("echo-m3u8-purify req=2 cost=" + elapsed(run) + "ms");
                        if (content == null || !content.startsWith("#EXTM3U")) {
                            handOffRaw(run, "forward-not-m3u8");
                            return;
                        }
                        processM3u8Content(run, forwardUrl, content);
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        LOG.e("echo-m3u8请求错误2: " + response.getException());
                        handOffRaw(run, "fetch-error-2");
                    }
                });
    }

    // -------------------- 出口（一次调用只认第一次） --------------------

    /** 交回**原链**播放：不清洗。这是全链所有异常/未知/无广告分支的统一出口。 */
    private void handOffRaw(Run run, String reason) {
        if (!settle(run)) return;
        LOG.i("echo-m3u8-purify settle=raw reason=" + reason + " cost=" + elapsed(run) + "ms");
        callback.startPlayUrl(run.sourceUrl, run.headers);
    }

    /** 交回**代理清单**播放（洗过的），同时把「代理地址 + 原始地址」报给播放器侧的归属表 */
    private void handOffProxy(Run run, String proxyUrl, int removed, String key) {
        if (!settle(run)) return;
        LOG.i("echo-m3u8-purify settle=proxy removed=" + removed + " proxy=" + (key != null)
                + " cost=" + elapsed(run) + "ms");
        callback.onM3u8ProxyUrl(proxyUrl, run.sourceUrl);
        callback.startPlayUrl(proxyUrl, run.headers);
    }

    /**
     * 抢占"本次调用的唯一一次交回"。三种情况返回 false（调用方必须什么都不做）：
     * ①已经交回过（守卫先到、回包后到 → 不许起播两次）；
     * ②这一轮已经不是当前轮（切集/换线后旧回调迟到 → 它要播的那一集早不是当前了）；
     * ③run 为 null。
     */
    private boolean settle(Run run) {
        if (run == null) return false;
        synchronized (this) {
            if (run.finished || run != currentRun) return false;
            run.finished = true;
            if (run.guard != null) mainHandler.removeCallbacks(run.guard);
            return true;
        }
    }

    /** 到点必须把播放权交回去 —— 本类存在的止血核心 */
    private void armGuard(Run run) {
        run.guard = new Runnable() {
            @Override
            public void run() {
                handOffRaw(run, "watchdog-" + PURIFY_TOTAL_TIMEOUT_MS + "ms");
            }
        };
        mainHandler.postDelayed(run.guard, PURIFY_TOTAL_TIMEOUT_MS);
    }

    /** 作废当前轮：撤守卫、旧回包从此不再有权起播 */
    private void invalidateCurrentRun() {
        if (currentRun != null) {
            currentRun.finished = true;
            if (currentRun.guard != null) mainHandler.removeCallbacks(currentRun.guard);
        }
    }

    private long elapsed(Run run) {
        return android.os.SystemClock.elapsedRealtime() - run.startedAt;
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
