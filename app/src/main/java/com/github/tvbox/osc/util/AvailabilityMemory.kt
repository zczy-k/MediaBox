package com.github.tvbox.osc.util

import androidx.compose.runtime.mutableStateOf
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.player.VideoQualityMemory

/**
 * 「已确认无资源」的持久标记 —— 方案 B 的第二层(点空回写)。
 *
 * <p>## 它解决什么
 *
 * <p>{@link AvailabilityHeuristic} 只能在列表阶段识别"站点自己说了没资源"的情况;
 * 剩下那些**列表看不出来、点进去才发现是空详情**的影片,靠本类兜底:
 * 用户点空一次 → 标记 → 该片从此不再出现在首页与搜索结果里。
 *
 * <p>这比"预请求每张卡片详情"便宜得多:零额外请求,而且用户只会点自己真想看的那几张。
 *
 * <p>## 为什么必须持久化
 *
 * <p>否则用户每次搜索同一片,都要再点一次空详情 —— 体验上等于没修。标记要跨会话保留,
 * 让"踩过的坑"不用再踩第二次。
 *
 * <p>## 存储与自愈
 *
 * <p>走 [VideoQualityMemory] 同款存储(MMKV + JSON + TTL + LRU),避免另起一套。
 *
 * <p>⚠️ **TTL 是必需的,不是可选优化**:源会补资源、站会复活。一部片子今天没资源、
 * 三个月后补上了,如果标记永不过期,用户就**永久看不到**这部片子。
 * TTL 让标记自然衰减,过期后重新参与展示。
 *
 * <p>容量按"用户踩过的坑数"估:正常用户一年踩不到几百个空详情,3000 条足够。
 */
object AvailabilityMemory {

    /** 保留条数上限(同 [VideoQualityMemory] 的 MAX 量级)。超出按最旧淘汰。 */
    private const val MAX_ENTRIES = 3000

    /** 标记有效期:90 天。超过这个时长即便还没补资源,也重新给用户一次机会。 */
    private const val TTL_DAYS = 90L

    private const val TTL_MS = TTL_DAYS * 24L * 60L * 60L * 1000L

    /** 站点侧偶尔会"假空"(网络抖动返回空详情),单次标记要冷却后才允许写入,避免误杀。 */
    private const val WRITE_COOLDOWN_MS = 30L * 60L * 1000L

    private fun now() = System.currentTimeMillis()

    /**
     * 标记集合的**版本号** —— 每次成功写入标记自增,供 UI 侧挂进缓存 key。
     *
     * <p>## 为什么需要它
     *
     * <p>UI 侧过滤标记的写法一度是`remember(results) { activeMarks() }` —— 缓存 key
     * 只挂 `results`。问题是**标记不是在搜索页写的,而是在详情页写的**:用户点开一部片子、
     * 发现是空详情、标记落盘,再返回搜索页。此时 `results` 一个字节都没变,
     * 于是 `remember` 命中缓存、拿的还是**旧标记集合**,新标记当场丢失。
     *
     * <p>今天能"碰巧对",是因为 [com.github.tvbox.osc.ui.activity.SearchViewModel] 收到
     * `TYPE_VOD_UNAVAILABLE` 广播后会调 [com.github.tvbox.osc.ui.activity.SearchViewModel.refreshAvailability]
     * 改写 `results.value` ⇒ `remember(results)` 失效重建。但那是**依赖广播恰好把
     * results 改掉了**,不是设计上正确 —— 一旦过滤后长度没变(或走的是渲染层过滤、
     * ViewModel 层没改),缓存就不会失效。
     *
     * <p>所以正确口径是:**缓存 key 必须同时挂 `results` 和本版本号**。
     * 标记一变版本号就变,`remember` 必然失效,不存在"漏看新标记"的可能。
     *
     * <p>⚠️ **它必须是 Compose 的 [mutableStateOf],不能是普通 Int 字段**。
     * 普通字段只是"值变了",Compose 观察不到 —— 于是"标记变了但 results 没变"时
     * 根本不会触发重组,`remember` 自然也不会重算,挂进 key 等于白挂。
     * 这正是 v1.0.33 那一版埋下的隐患:看着对了,实际仍依赖广播恰好改了 results。
     * 改成 state 之后,读它的 composable 会在版本号变化时自动重组。
     *
     * <p>写入只发生在 [markUnavailable] 里,而调用方是 `viewModelScope.launch`
     * (Main 派发器),所以不存在跨线程写 Compose 状态的问题。
     */
    private val marksRevisionState = mutableStateOf(0)

    /** 见 [marksRevisionState]。读它会建立 Compose 订阅关系。 */
    val marksRevision: Int get() = marksRevisionState.value

    private fun bumpRevision() {
        marksRevisionState.value = marksRevisionState.value + 1
    }

    /**
     * 标记"这部片子当前没有资源"。
     *
     * <p>带写入冷却:同一 `站点|片id` 30 分钟内只记一次。防的是**假空** ——
     * 详情请求因网络抖动/站点超时返回空是很常见的(实测 js_douban 一天就出现多次
     * `echo-detail-empty-state`),无冷却标记会让好片被误判没资源。
     *
     * @return true 表示本次真的写入了;false 表示因冷却期被跳过。
     */
    @JvmStatic
    fun markUnavailable(sourceKey: String?, vodId: String?, siteName: String? = null): Boolean {
        val key = AvailabilityHeuristic.markOf(sourceKey, vodId)
        if (key.isEmpty()) return false
        val current = VideoQualityMemory.lookupAvailability(key)
        val t = now()
        if (current != null && t - current.markedAt < WRITE_COOLDOWN_MS) return false
        VideoQualityMemory.recordAvailability(key, t, siteName.orEmpty())
        // ⚠️ 必须在写盘成功**之后**才推进版本号:UI 侧靠它触发 remember 失效,
        // 顺序反了会让 UI 重建时读到还没落盘的旧集合。
        bumpRevision()
        LOG.i("echo-unavailable marked key=" + key + " rev=" + marksRevision)
        return true
    }

    /** 该片是否已被标记为无资源(且标记未过期)。 */
    @JvmStatic
    fun isMarkedUnavailable(sourceKey: String?, vodId: String?): Boolean {
        val key = AvailabilityHeuristic.markOf(sourceKey, vodId)
        if (key.isEmpty()) return false
        val e = VideoQualityMemory.lookupAvailability(key) ?: return false
        return now() - e.markedAt < TTL_MS
    }

    /** 当前有效的标记集合,供 [AvailabilityHeuristic.mightBeUnavailable] 批量判定。 */
    @JvmStatic
    fun activeMarks(): Set<String> =
        VideoQualityMemory.activeAvailability(TTL_MS).mapTo(HashSet()) { it.key }

    /**
     * 批量过滤列表:去掉"看起来没资源"的条目。
     *
     * <p>这是给 UI 用的便捷入口 —— 一次拿到标记集合再判,避免每张卡片都去查存储。
     */
    @JvmStatic
    fun filterPlayable(videos: List<Movie.Video>): List<Movie.Video> {
        if (videos.isEmpty()) return videos
        val marks = activeMarks()
        return videos.filterNot { AvailabilityHeuristic.mightBeUnavailable(it, marks) }
    }
}