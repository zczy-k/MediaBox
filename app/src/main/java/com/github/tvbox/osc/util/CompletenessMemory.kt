package com.github.tvbox.osc.util

/**
 * 影片"权威总集数"记忆(P-完整性方案,2026-10-09)。
 *
 * <p>## 解决什么
 * "源不完整"判定的参照系缺失:任何单一源都不会告诉你"这部片**应该**有多少集"。
 * 本记忆按**归一化标题**记录权威集数 —— 集数是片的属性,不是源的属性,跨源共用一份。
 *
 * <p>## 三条铁律(防污染)
 * <ol>
 *   <li>**只增不减**:权威一旦达到 N,永不回落(完结剧固定,更新剧单调上涨);</li>
 *   <li>**实绩抬升**:只有"详情 seriesMap 实测的可数集数"(经 [EpisodeTotals.episodeCount]
 *       口径)才允许抬升权威 —— 源站 note 虚报"全50集"但实际只有 3 集,永远抬不动;</li>
 *   <li>**上限 [AUTHORITY_MAX]**:异常数据(把两季合并铺开、解析错乱)封顶,不参与无界增长。</li>
 * </ol>
 *
 * <p>## 冷启动
 * 首次打开的片没有权威记录 —— 调用方([SourceCompletenessPolicy])对"权威未知"一律
 * **不判定、不降权**,排序退化为既有行为;权威随使用累积,越常用的片越准。
 */
object CompletenessMemory {

    const val AUTHORITY_MAX = 200

    private const val KEY = "completeness_memory"
    private const val MAX_TITLES = 500

    /** 读取某片的权威集数;0 = 未知 */
    fun authority(titleKey: String): Int {
        if (titleKey.isEmpty()) return 0
        return try {
            val map = KV.get<HashMap<String, Int>>(KEY, HashMap()) ?: return 0
            map[titleKey] ?: 0
        } catch (th: Throwable) {
            LOG.i("echo-completeness read-failed: " + th.message)
            0
        }
    }

    /**
     * 用详情实绩抬升权威:只增不减、上限 [AUTHORITY_MAX]。
     * count ≤ 1 的不记(单集/电影不参与完整性判定)。
     */
    fun record(titleKey: String, count: Int) {
        if (titleKey.isEmpty() || count <= 1) return
        try {
            val map = try {
                KV.get<HashMap<String, Int>>(KEY, HashMap()) ?: HashMap()
            } catch (th: Throwable) {
                HashMap()
            }
            val capped = count.coerceAtMost(AUTHORITY_MAX)
            val old = map[titleKey] ?: 0
            if (capped <= old) return
            map[titleKey] = capped
            // 容量上限:超出时淘汰最旧的 1/4(无时间戳,靠插入序不可靠,直接整表裁剪到上限内)
            if (map.size > MAX_TITLES) {
                val keep = map.entries.sortedByDescending { it.value }.take(MAX_TITLES * 3 / 4)
                val trimmed = HashMap<String, Int>(keep.size)
                keep.forEach { trimmed[it.key] = it.value }
                KV.put(KEY, trimmed)
            } else {
                KV.put(KEY, map)
            }
        } catch (th: Throwable) {
            LOG.i("echo-completeness write-failed: " + th.message)
        }
    }
}
