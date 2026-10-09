package com.github.tvbox.osc.util

import org.json.JSONObject

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
 * </ul>
 *
 * <p>## ⚠️ 为什么用 JSON 字符串而不是 KV 泛型读(2026-10-09 真机踩坑)
 * 最初实现用 `KV.get<HashMap<String, Int>>`,真机上 read/write 全部
 * `Double cannot be cast to Integer` 崩掉 —— KV 的 Gson 还原把 Int 变 Double,
 * 内层泛型强转必炸(与 SearchSettings.currentSelection 注释警告的是同一类陷阱,
 * 且该注释明确指出泛型内层还原需要 KVKeySpec 登记)。改用 JSON 字符串存取
 * (org.json 的 optInt 保类型,SearchResultCache 同款方案)后彻底规避。
 */
object CompletenessMemory {

    const val AUTHORITY_MAX = 200

    private const val KEY = "completeness_memory"
    private const val MAX_TITLES = 500

    /** 读取某片的权威集数;0 = 未知 */
    fun authority(titleKey: String): Int {
        if (titleKey.isEmpty()) return 0
        return try {
            JSONObject(KV.get(KEY, "")).optInt(titleKey, 0)
        } catch (th: Throwable) {
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
            val root = try {
                JSONObject(KV.get(KEY, ""))
            } catch (th: Throwable) {
                JSONObject()
            }
            val capped = count.coerceAtMost(AUTHORITY_MAX)
            if (capped <= root.optInt(titleKey, 0)) return
            root.put(titleKey, capped)
            // 容量上限:超出时淘汰集数最少的一批(权威低的片参考价值最低)
            if (root.length() > MAX_TITLES) {
                val keep = root.keys().asSequence()
                    .map { k -> k to root.optInt(k, 0) }
                    .sortedByDescending { it.second }
                    .take(MAX_TITLES * 3 / 4)
                val trimmed = JSONObject()
                keep.forEach { (k, v) -> trimmed.put(k, v) }
                KV.put(KEY, trimmed.toString())
            } else {
                KV.put(KEY, root.toString())
            }
        } catch (th: Throwable) {
            LOG.i("echo-completeness write-failed: " + th.message)
        }
    }
}
