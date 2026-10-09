package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.Movie

/**
 * 「这部片子现在有没有资源」的**列表阶段粗筛**。
 *
 * <p>## 为什么需要它
 *
 * <p>产品要求:没有实际线路/源的影片,海报不该出现在首页与搜索结果里 ——
 * 用户点进去才发现是空详情,属于误导。
 *
 * <p>## 为什么只能是"粗筛"
 *
 * <p>`Movie.Video` 上能反映可用性的字段只有 [Movie.Video.urlBean],而**列表接口
 * (搜索/首页)通常不返回它** —— 只有详情接口才带播放地址。所以列表渲染的那一刻,
 * App 手上根本没有判断依据,拿不到"能不能播"的可靠答案。
 *
 * <p>于是分两层:
 * <ol>
 *   <li>**本类(粗筛)**:用列表接口**确实带回来**的字段(note / state / type)识别
 *       "站点自己都说了没资源"。零额外请求,零延迟。</li>
 *   <li>**点空回写**:用户真点进去发现是空详情,标记该片不可用并从列表移除,
 *       下次不再出现(见 {@code AvailabilityMemory})。彻底但需要一次点击。</li>
 * </ol>
 *
 * <p>## 为什么要"零误杀"
 *
 * <p>粗筛一旦误判,把**有资源**的卡片藏了,用户永远看不到这部片子 —— 比"多显示一张
 * 没资源的海报"严重得多。所以口径极保守:[mightBeUnavailable] 只在站点**明确
 * 说了没有**时才返回 true,任何拿不准的情况一律放过。宁可漏杀,不可错杀。
 *
 * <p>这不是"待确认"字段,而是站点用来告知用户的现成文案,各站措辞高度一致。
 */
object AvailabilityHeuristic {

    /**
     * 站点自述"暂无资源"的文案。命中即认为不可用。
     *
     * <p>覆盖各站常见措辞:「暂无资源」「暂无片源」「暂无更新」「未收录」「已下架」
     * 「已失效」「失效」「维护中」等。刻意只收**语义明确**的词 ——
     * 「更新至20集」这类含"更新"的正常描述绝不能命中,所以没有收录"更新"。
     */
    private val UNAVAILABLE_PATTERNS = listOf(
        "暂无资源", "暂无片源", "暂无影片", "暂无视频", "暂无更新", "暂无合集",
        "未收录", "未上映", "暂无入库",
        "已下架", "已失效", "已失效!", "已删除", "已封锁", "已内卷",
        "该资源已", "资源已", "播放失败",
        "维护中", "站点维护", "暂停服务", "停止服务", "停止更新", "永久维护",
        "censored", "404", "not found", "已过期",
        // ↓ 2026-10-09(P1-B):英文站的自述措辞。同样只收语义明确的短语 ——
        //   "Coming Soon"是站点明确说"现在没有可播内容",属于本筛语义;
        //   刻意不收 "unavailable" 单词本身:它可能出现在正常描述里,短语形式已够用。
        "no video", "no videos", "no resources", "no resource", "no content",
        "not available", "coming soon", "temporarily unavailable",
    )

    /** 命中即认为不可用。纯 ASCII/中文小写比较,避免大小写漏判。 */
    private fun hitUnavailable(raw: String?): Boolean {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return false
        return UNAVAILABLE_PATTERNS.any { s.contains(it) }
    }

    /**
     * 这条列表项**看起来**没有资源。
     *
     * <p>返回 true 只表示"列表阶段就该把海报藏掉";返回 false 表示"看不出来,
     * 先显示,等用户点进去由点空回写兜底"。
     *
     * @param video 列表接口返回的影片条目
     * @param unavailableMarks 已标记为不可用的 `站点|片id` 集合(点空回写累积的),传空集即可
     */
    @JvmStatic
    @JvmOverloads
    fun mightBeUnavailable(video: Movie.Video?, unavailableMarks: Set<String> = emptySet()): Boolean {
        val v = video ?: return true
        // 点空回写优先:已经确认过没资源的,直接排除,不再看文案
        val key = markOf(v.sourceKey, v.id)
        if (key.isNotEmpty() && unavailableMarks.contains(key)) return true
        // 站点自述没资源 —— 这是唯一能在列表阶段白拿到的判定依据
        if (hitUnavailable(v.state)) return true
        if (hitUnavailable(v.note)) return true
        return false
    }

    /** 生成"站点|片id"标记;[AvailabilityMemory] 与调用方共用同一口径。 */
    @JvmStatic
    fun markOf(sourceKey: String?, vodId: String?): String {
        val s = sourceKey.orEmpty()
        val i = vodId.orEmpty()
        return if (s.isEmpty() || i.isEmpty()) "" else "$s|$i"
    }
}