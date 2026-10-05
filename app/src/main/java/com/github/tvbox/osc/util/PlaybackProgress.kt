package com.github.tvbox.osc.util

import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.PlaybackService
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** "本集播放进度"快照(历史页进度条)+ 观看历史的落库信号源 */
object PlaybackProgress {

    private const val KEY = "playback_progress"

    private const val LIMIT = 300

    private const val MIN_INTERVAL_MS = 5_000L

    private const val MIN_ADVANCE_MS = 1_000

    private const val MAX_STEP_MS = 10_000

    private var lastKey = ""

    private var lastSavedAt = 0L

    private var lastSavedPercent = -1

    private var sampleToken = ""

    private var lastPosition = -1

    private var advancedMs = 0

    private var watchedToken = ""

    private val writer: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "playback-progress") }
    }

    fun key(sourceKey: String?, vodId: String?): String =
        sourceKey.orEmpty() + "|" + vodId.orEmpty()

    fun snapshot(): Map<String, Int> {
        // 无痕:不展示观看痕迹 —— 卡片写着"已观看 60%"、点进去却从头,自相矛盾
        if (HistoryHelper.isIncognito()) return emptyMap()
        return read()
            .mapNotNull { (key, value) ->
                value.toIntOrNull()?.takeIf { it in 0..100 }?.let { key to it }
            }
            .toMap()
    }

    /**
     * 起播点清掉该片残留的百分比:百分比按整片一条(键 = 源|片id),留着会让卡片挂着上一集的进度。
     * [scrollToTop] 为 false 时刷新不置顶列表(起播不是"用户在看历史页")。
     */
    fun onEpisodeStart(scrollToTop: Boolean) {
        val id = currentKey() ?: return
        val cleared = forget(id)
        val event = if (scrollToTop) {
            RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH)
        } else {
            // obj = Boolean.FALSE:历史页据此跳过置顶
            RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH, java.lang.Boolean.FALSE)
        }
        if (cleared || !scrollToTop) EventBus.getDefault().post(event)
    }

    /** 起播点入口(Java 调用) */
    @JvmStatic
    fun onEpisodeStartNoScroll() = onEpisodeStart(false)

    fun onProgress(positionMs: Int, durationMs: Int) {
        val id = currentKey() ?: return
        WatchProgressStore.noteDuration(id, durationMs.toLong())
        // 节流会吞采样,判据必须拿到全部采样
        markWatched(positionMs, durationMs)
        if (WatchProgressRules.decide(positionMs.toLong(), durationMs.toLong()) == WatchDecision.SKIP) return
        val percent = calcPercent(positionMs, durationMs) ?: return
        val now = System.currentTimeMillis()
        if (id == lastKey && (percent == lastSavedPercent || now - lastSavedAt < MIN_INTERVAL_MS)) return
        lastKey = id
        lastSavedAt = now
        lastSavedPercent = percent
        writer.execute { write(id, percent) }
    }

    fun flush(positionMs: Int, durationMs: Int): Boolean {
        val id = currentKey() ?: return false
        markWatched(positionMs, durationMs)
        watchedToken = ""
        sampleToken = ""
        advancedMs = 0
        if (WatchProgressRules.decide(positionMs.toLong(), durationMs.toLong()) == WatchDecision.SKIP) return false
        val percent = calcPercent(positionMs, durationMs) ?: return false
        if (id == lastKey && percent == lastSavedPercent) return false
        if (!write(id, percent)) return false
        lastKey = id
        lastSavedAt = System.currentTimeMillis()
        lastSavedPercent = percent
        return true
    }

    /** 本集播完:百分比落满 100,续播点由内核对完播的清除路径负责(VideoView.onCompletion 写 0) */
    fun markFinished() {
        val id = currentKey() ?: return
        if (id == lastKey && lastSavedPercent == 100) return
        if (!write(id, 100)) return
        lastKey = id
        lastSavedAt = System.currentTimeMillis()
        lastSavedPercent = 100
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
    }

    /** 只认平滑推进:回拖与 seek 跳变(起播起始位置来自上次进度)都不算"在播" */
    fun stepAdvanceMs(positionMs: Int, lastPositionMs: Int): Int =
        (positionMs - lastPositionMs).takeIf { it in 1..MAX_STEP_MS } ?: 0

    fun shouldMarkWatched(advancedMs: Int, token: String, lastToken: String): Boolean =
        token != lastToken && advancedMs >= MIN_ADVANCE_MS

    private fun markWatched(positionMs: Int, durationMs: Int) {
        val vod = App.getInstance().vodInfo ?: return
        if (PlaybackService.peek()?.isLiveMode() == true) return
        val token = key(vod.sourceKey, vod.id) + "#" + vod.playFlag + "#" + vod.playIndex
        if (token != sampleToken) {
            sampleToken = token
            lastPosition = positionMs
            advancedMs = 0
            return
        }
        advancedMs += stepAdvanceMs(positionMs, lastPosition)
        lastPosition = positionMs
        if (!shouldMarkWatched(advancedMs, token, watchedToken)) return
        // 起播位置来自上次进度时一进来就非 0,只有真看进去了才算"看过" —— 与续播点同一判据
        if (!WatchProgressRules.shouldRemember(positionMs.toLong(), durationMs.toLong())) return
        watchedToken = token
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_PLAYBACK_STARTED))
    }

    private fun calcPercent(positionMs: Int, durationMs: Int): Int? {
        if (durationMs <= 0) return null
        return (positionMs.toLong() * 100 / durationMs).toInt().coerceIn(0, 100)
    }

    private fun currentKey(): String? {
        val vod = App.getInstance().vodInfo ?: return null
        if (vod.sourceKey.isNullOrEmpty() || vod.id.isNullOrEmpty()) return null
        return key(vod.sourceKey, vod.id)
    }

    @Synchronized
    private fun write(id: String, percent: Int): Boolean {
        // 无痕不新增观看痕迹;判定放这里(IO 线程/状态变化时),不进每秒 tick
        if (HistoryHelper.isIncognito()) return false
        val map = read()
        map[id] = percent.toString()
        if (map.size > LIMIT) map.keys.take(map.size - LIMIT).forEach { map.remove(it) }
        return KV.put(KEY, map)
    }

    /** 清除不受无痕影响:清的是已有记录,不产生新痕迹 */
    @Synchronized
    private fun remove(id: String): Boolean {
        val map = read()
        if (map.remove(id) == null) return false
        return KV.put(KEY, map)
    }

    /**
     * 与索引同口径:只保留这些 owner 的快照。[LIMIT] 那道是纯安全阀 —— 它按 HashMap 迭代序淘汰,
     * 挑的不是最旧的,可能把刚看的删掉、留下记录已被回收的孤儿。
     */
    fun retain(owners: Set<String>) {
        writer.execute { retainNow(owners) }
    }

    @Synchronized
    private fun retainNow(owners: Set<String>) {
        val map = read()
        val kept = HashMap<String, String>(map.size)
        for ((id, value) in map) {
            if (owners.contains(id)) kept[id] = value
        }
        if (kept.size == map.size) return
        KV.put(KEY, kept)
    }

    /** 用户删历史时的移除入口(owner = 源|片id):主动操作不受无痕拦截 */
    @Synchronized
    fun forget(id: String): Boolean = remove(id)

    @Synchronized
    fun forgetAll() {
        lastKey = ""
        lastSavedPercent = -1
        KV.delete(KEY)
    }

    private fun read(): HashMap<String, String> = KV.get(KEY, HashMap<String, String>())
}
