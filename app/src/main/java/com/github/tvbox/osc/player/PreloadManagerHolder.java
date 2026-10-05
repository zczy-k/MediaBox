package com.github.tvbox.osc.player;

import android.content.Context;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.source.preload.PreloadException;
import androidx.media3.exoplayer.source.preload.PreloadManagerListener;
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.KV;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;

public final class PreloadManagerHolder {
    private static final String TAG = "PreloadManager";

    /** 预载时长(秒)兜底值/边界:设置项「预载时长」20~120 步长 10(第二期参数化) */
    private static final int PRELOAD_SECONDS_DEFAULT = 60;
    private static final int PRELOAD_SECONDS_MIN = 20;
    private static final int PRELOAD_SECONDS_MAX = 120;

    /**
     * 当前预载请求的 headers 快照(预缓存下载源建源时读取):media3 只支持 builder 级工厂,
     * 而 headers 是 per-item 的;本项目恒只预载 1 项,且下载源在任务创建时才构建,快照即当前项。
     */
    private static volatile Map<String, String> sPreloadHeaders = Collections.emptyMap();

    /** 当前预载请求的起始位置（ms,片头跳过/历史进度对齐,预载线程经 TargetPreloadStatusControl 读取） */
    private static volatile long sStartPosMs = 0L;
    /** 当前预载请求的数据时长（ms,设置项「预载时长」,预载线程经 TargetPreloadStatusControl 读取） */
    private static volatile long sRangeMs = PRELOAD_SECONDS_DEFAULT * 1000L;

    private static DefaultPreloadManager sManager;
    /** 预载/播放共享线程(进程级单例,见 preloadLooper) */
    private static HandlerThread sPreloadThread;
    /** key(url+headers) → 预载中的 MediaItem(去重与失效清理用;预缓存完成不移除——磁盘数据要留给播放读盘) */
    private static final Map<String, MediaItem> sRegistry = new HashMap<>();
    /**
     * 本播放页会话内「已预载过的 url → 预载时 headers 签名」(LRU 8):签名一致才允许播放侧用
     * 预缓存写盘时的默认 key 读盘(签名不同是另一份数据,退回后缀 key 链路,宁可 miss 也不误读)。
     */
    private static final Map<String, String> sPreloadTargets =
            new LinkedHashMap<String, String>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 8;
                }
            };
    /** 预载完成回调(第二期 UI 提示「下一集已就绪」;由 PlayContainer 注入,任意线程调用) */
    private static volatile ReadyListener sReadyListener;
    /** 已完成预缓存的 url(拖动/缓冲结束后 UI 重放提示用;清数据/发起新预载即失效) */
    private static volatile String sCompletedUrl;

    /** 预载就绪监听(第二期 UI 提示用) */
    public interface ReadyListener {
        void onPreloadReady(String url);
    }

    private PreloadManagerHolder() {
    }

    /** 预载总开关（设置页「下一集预载」,默认关） */
    public static boolean enabled() {
        return KV.get(HawkConfig.PRELOAD_NEXT_EPISODE, false);
    }

    /**
     * 发起一次预载(磁盘预缓存:流式写共享 SimpleCache,不占 SampleQueue)。同 url+headers 已在预载中时幂等跳过。
     *
     * @param startPosMs 预载起点（对齐片头跳过 max(st, 历史进度),< 0 时按 0 处理）
     */
    public static synchronized void preload(Context context, String url, Map<String, String> headers, long startPosMs) {
        if (!enabled() || url == null || url.isEmpty()) {
            return;
        }
        try {
            DefaultPreloadManager manager = get(context.getApplicationContext());
            String key = keyOf(url, headers);
            if (sRegistry.containsKey(key)) {
                LOG.i("echo-preload-already: " + url);
                return;
            }
            sStartPosMs = Math.max(0L, startPosMs);
            sRangeMs = preloadRangeMs();
            sCompletedUrl = null;
            sPreloadHeaders = headers == null ? Collections.<String, String>emptyMap() : new HashMap<>(headers);
            sPreloadTargets.put(url, headersSignature(headers));
            // 预缓存路径必须显式带内容类型(DownloadHelper 只按 uri/mimeType 推断),见 buildPreloadMediaItem
            MediaItem item = ExoMediaSourceHelper.buildPreloadMediaItem(url, headers);
            sRegistry.put(key, item);
            manager.add(item, 0); // rankingData 仅用于多项排序,本项目恒只预载 1 项
            // 注意:BasePreloadManager.add() 不触发重新排序,必须手动 invalidate 才会真正开始预载
            manager.invalidate();
            LOG.i("echo-preload-start: " + url);
        } catch (Throwable th) {
            LOG.e("echo-preload-error: " + url + " " + th);
        }
    }

    /** 是否存在预载中的条目（仅用于日志/诊断,不参与命中判断） */
    public static synchronized boolean hasActivePreload() {
        return !sRegistry.isEmpty();
    }

    /**
     * 该 url 是否为本会话预载过、且 headers 与预载时一致的目标(第二期磁盘兜底判定):
     * app ExoPlayer.setDataSource 命中时换用 cache 版 MediaSource 从共享 SimpleCache 读盘。
     */
    public static synchronized boolean isPreloadTargetUrl(String url, Map<String, String> headers) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String preloadSignature = sPreloadTargets.get(url);
        return preloadSignature != null && preloadSignature.equals(headersSignature(headers));
    }

    /** 注入预载完成回调（第二期 UI 提示） */
    public static void setReadyListener(ReadyListener listener) {
        sReadyListener = listener;
    }

    /** 已完成目标仍有效时重放一次就绪回调(拖动/缓冲后 UI 需再提示,数据没失效不重下);@return 是否重放 */
    public static boolean replayReadyIfCompleted() {
        String url = sCompletedUrl;
        ReadyListener listener = sReadyListener;
        if (url == null || listener == null) {
            return false;
        }
        LOG.i("echo-preload-ready-replay: " + url);
        try {
            listener.onPreloadReady(url);
        } catch (Throwable th) {
            LOG.e("PreloadManagerHolder", "preload ready replay failed", th);
        }
        return true;
    }

    /** 注销回调：仅当当前回调仍为传入实例时清空（防多播放容器交错销毁时误清后来者的回调） */
    public static synchronized void clearReadyListener(ReadyListener listener) {
        if (listener != null && sReadyListener == listener) {
            sReadyListener = null;
        }
    }

    /** 预载时长(ms):读设置项「预载时长」,越界兜底;每次发起预载时快照,预载线程经 volatile 读取 */
    private static long preloadRangeMs() {
        int seconds = PRELOAD_SECONDS_DEFAULT;
        try {
            seconds = KV.get(HawkConfig.PRELOAD_DURATION, PRELOAD_SECONDS_DEFAULT);
        } catch (Throwable th) {
            LOG.e("PreloadManagerHolder", "preload duration KV read failed, use default", th);
        }
        seconds = Math.max(PRELOAD_SECONDS_MIN, Math.min(PRELOAD_SECONDS_MAX, seconds));
        return seconds * 1000L;
    }

    /** 清空全部预载（切集/换线/换源/暂停策略变化等失效事件）,保留 manager 可复用 */
    public static synchronized void clearAll() {
        sCompletedUrl = null;
        if (sManager == null) {
            return;
        }
        if (!sRegistry.isEmpty()) {
            LOG.i("echo-preload-clear: " + sRegistry.size());
            sRegistry.clear();
        }
        try {
            sManager.reset();
        } catch (Throwable th) {
            LOG.e("echo-preload-clear-error " + th);
        }
    }

    /** 释放 manager（退出播放页）,下次预载重新构建 */
    public static synchronized void release() {
        if (sManager != null) {
            try {
                sManager.release();
            } catch (Throwable th) {
                LOG.e("PreloadManagerHolder", "preload manager release failed", th);
            }
            sManager = null;
        }
        sRegistry.clear();
        sPreloadTargets.clear();
        sPreloadHeaders = Collections.emptyMap();
        sCompletedUrl = null;
    }

    private static DefaultPreloadManager get(Context appContext) {
        if (sManager == null) {
            TargetPreloadStatusControl<Integer, DefaultPreloadManager.PreloadStatus> control =
                    rankingData -> DefaultPreloadManager.PreloadStatus.specifiedRangeCached(sStartPosMs, sRangeMs);
            sManager = new DefaultPreloadManager.Builder(appContext, control)
                    .setMediaSourceFactory(new PreloadMediaSourceFactory(appContext))
                    // cached 状态走 PreCacheHelper 磁盘预缓存:必须注入 Cache,否则 build 时 preCacheHelperFactory
                    // 为 null,开始预缓存即抛 —— 数据不再进 SampleQueue,故不再需要 32MB 内存水位(LoadControl)
                    .setCache(ExoMediaSourceHelper.getSharedCache(appContext))
                    // 预缓存下载只认 builder 级 DataSource.Factory(不读 MediaItem.extras),站点 headers 靠它桥接
                    .setDataSourceFactory(new PreloadDataSourceFactory(appContext))
                    // 预载线程须与播放器 playback looper 同一(PreloadMediaSource 硬校验,见 preloadLooper)
                    .setPreloadLooper(preloadLooper())
                    .build();
            sManager.addListener(new PreloadManagerListener() {
                @Override
                public void onCompleted(MediaItem mediaItem) {
                    String url = mediaItem == null || mediaItem.localConfiguration == null
                            ? null : mediaItem.localConfiguration.uri.toString();
                    sCompletedUrl = url;
                    ReadyListener listener = sReadyListener;
                    LOG.i("echo-preload-complete: " + url + ", listener=" + (listener != null));
                    if (listener != null && url != null) {
                        try {
                            listener.onPreloadReady(url);
                        } catch (Throwable th) {
                            LOG.e("PreloadManagerHolder", "preload ready callback failed", th);
                        }
                    }
                }

                @Override
                public void onError(PreloadException exception) {
                    // PreloadException.toString() 只有类名,不带底层 IO/manifest 原因,必须显式打 cause
                    LOG.e("echo-preload-error: " + exception + ", cause=" + exception.getCause());
                }
            });
        }
        return sManager;
    }

    public static synchronized Looper preloadLooper() {
        if (sPreloadThread == null || !sPreloadThread.isAlive()) {
            sPreloadThread = new HandlerThread("avbox-preload", android.os.Process.THREAD_PRIORITY_AUDIO);
            sPreloadThread.start();
        }
        return sPreloadThread.getLooper();
    }

    /** url + 规范化(headers) 作为命中 key,headers 逐项一致才命中 */
    private static String keyOf(String url, Map<String, String> headers) {
        return new StringBuilder(url).append('\n').append(headersSignature(headers)).toString();
    }

    /** 规范化 headers 签名(排序 + trim + 大小写不敏感,逐项以 ';' 分隔);无 headers 为空串 */
    private static String headersSignature(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        Map<String, String> sorted = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                sorted.put(entry.getKey().trim(), entry.getValue().trim());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            sb.append(entry.getKey()).append(':').append(entry.getValue()).append(';');
        }
        return sb.toString();
    }

    /** 预缓存下载源:建源时取当前预载项的 headers 快照(恒只预载 1 项),不桥接则站点请求 403 */
    private static final class PreloadDataSourceFactory implements DataSource.Factory {
        private final Context appContext;

        PreloadDataSourceFactory(Context context) {
            appContext = context;
        }

        @Override
        public DataSource createDataSource() {
            return ExoMediaSourceHelper.getInstance(appContext)
                    .createDataSourceFactory(sPreloadHeaders)
                    .createDataSource();
        }
    }

    /**
     * 预载侧 MediaSource 工厂:与播放侧同源——直接复用 ExoMediaSourceHelper.getMediaSource,
     * headers 从 MediaItem 的 requestMetadata.extras 取回（buildMediaItem 写入）。
     */
    private static final class PreloadMediaSourceFactory implements MediaSource.Factory {
        private final Context appContext;

        PreloadMediaSourceFactory(Context context) {
            appContext = context;
        }

        @Override
        public MediaSource createMediaSource(MediaItem mediaItem) {
            String uri = mediaItem.localConfiguration != null
                    ? mediaItem.localConfiguration.uri.toString()
                    : mediaItem.mediaId;
            Map<String, String> headers = ExoMediaSourceHelper.getHeadersFrom(mediaItem);
            // cached 状态不往 SampleQueue 灌数据,该源只是 holder 的壳(onMediaSourceUpdated 会替换成新源)
            return ExoMediaSourceHelper.getInstance(appContext).getMediaSource(uri, headers, true);
        }

        @Override
        public int[] getSupportedTypes() {
            return new int[]{
                    C.CONTENT_TYPE_OTHER,
                    C.CONTENT_TYPE_HLS,
                    C.CONTENT_TYPE_DASH,
                    C.CONTENT_TYPE_RTSP};
        }

        @Override
        public MediaSource.Factory setDrmSessionManagerProvider(androidx.media3.exoplayer.drm.DrmSessionManagerProvider provider) {
            // 预载不做 DRM(可预载判定已排除解析源/DRM 场景)
            return this;
        }

        @Override
        public MediaSource.Factory setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy policy) {
            return this;
        }
    }
}
