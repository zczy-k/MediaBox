package com.github.tvbox.osc.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * 影片"权威总集数"记忆(P-完整性方案,2026-10-09;2026-10-09 深夜 v1.0.26 重构确认机制)。
 *
 * <p>## 解决什么
 * "源不完整"判定的参照系缺失:任何单一源都不会告诉你"这部片**应该**有多少集"。
 * 本记忆按**归一化标题**记录权威集数 —— 集数是片的属性,不是源的属性,跨源共用一份。
 *
 * <p>## 确认机制(v1.0.26 重构,两源印证)
 * 真机实测(《花开锦绣》36 集被标"缺37集")暴露单源确认的缺陷:某源的 seriesMap 把
 * 36 集正片 + 1 个纯序号命中的附加条目(花絮/下一部预告)铺在一起,可数集数=37,
 * **单源无法自证第 37 项是正片** —— 只增不减的记忆被单源污染,且永不自愈。
 *
 * <p>因此改为**两源印证**:
 * <ul>
 *   <li>每个实测集数记录"实测过它的**独立源**"集合;</li>
 *   <li>某集数被 **≥2 个独立源**实测到 ⇒ 确认为权威(确认后仍只增不减);</li>
 *   <li>只有单源观测的集数停留在"待确认"—— 不用于面板标注/排序降权。</li>
 * </ul>
 * 单源污染的代价从"永久误标"降为"不生效";真有多源一致的集数,确认自动跟上。
 *
 * <p>## 其余铁律(不变)
 * 实绩抬升(只有详情 seriesMap 实测可数集数说话)、上限 [AUTHORITY_MAX]、
 * count ≤ 1(电影/单集/集名不可数)不参与。
 *
 * <p>## 旧数据迁移
 * 旧格式(纯 int 值)读出后视为"单源观测"—— **含本日污染的 37 也一并失效**,
 * 需第二源重新印证;宁保守不延续污染。
 */
object CompletenessMemory {

    const val AUTHORITY_MAX = 200

    private const val KEY = "completeness_memory"
    private const val MAX_TITLES = 500
    private const val MAX_SEEN_ENTRIES = 8
    private const val MAX_SOURCES_PER_COUNT = 8
    /** 确认所需的最少独立源数 */
    private const val CONFIRM_SOURCES = 2

    /** 读取某片**已确认**的权威集数;0 = 未确认(不用于标注/降权) */
    fun authority(titleKey: String): Int {
        if (titleKey.isEmpty()) return 0
        return try {
            JSONObject(KV.get(KEY, "")).optJSONObject(titleKey)?.optInt("c", 0) ?: 0
        } catch (th: Throwable) {
            0
        }
    }

    /**
     * 记录一次详情实绩:[count] 由 [sourceKey] 实测得到。
     * 同一源重复实测去重;某集数被 ≥[CONFIRM_SOURCES] 个独立源实测 ⇒ 确认为权威。
     */
    fun record(titleKey: String, count: Int, sourceKey: String?) {
        if (titleKey.isEmpty() || count <= 1) return
        val capped = count.coerceAtMost(AUTHORITY_MAX)
        val src = sourceKey.orEmpty().ifEmpty { "unknown" }
        try {
            val root = try {
                JSONObject(KV.get(KEY, ""))
            } catch (th: Throwable) {
                JSONObject()
            }
            val entry = migrate(root.optJSONObject(titleKey), root.optInt(titleKey, 0))
            // seen: 集数 → 实测过它的独立源集合
            val seen = entry.optJSONObject("s") ?: JSONObject()
            val srcArr = seen.optJSONArray(capped.toString()) ?: JSONArray()
            for (i in 0 until srcArr.length()) {
                // 同一源重复实测:集数没变,无需重记
                if (srcArr.optString(i) == src) {
                    seen.put(capped.toString(), srcArr)
                    entry.put("s", seen)
                    root.put(titleKey, entry)
                    KV.put(KEY, root.toString())
                    return
                }
            }
            if (srcArr.length() < MAX_SOURCES_PER_COUNT) srcArr.put(src)
            seen.put(capped.toString(), srcArr)
            // 裁剪 seen:只留集数最大的若干桶(确认权威只需要最大值附近的印证)
            while (seen.length() > MAX_SEEN_ENTRIES) {
                val smallest = seen.keys().asSequence()
                    .minByOrNull { it.toIntOrNull() ?: 0 } ?: break
                seen.remove(smallest)
            }
            entry.put("s", seen)
            // 确认:该集数被足够多的独立源实测,且大于当前已确认值(只增不减)
            val confirmed = entry.optInt("c", 0)
            if (srcArr.length() >= CONFIRM_SOURCES && capped > confirmed) {
                entry.put("c", capped)
                LOG.i("echo-completeness confirmed title=$titleKey count=$capped sources=${srcArr.length()}")
            }
            root.put(titleKey, entry)
            // 容量上限:超出时淘汰已确认集数最少的一批(参考价值最低)
            if (root.length() > MAX_TITLES) {
                val keep = root.keys().asSequence()
                    .map { k -> k to (root.optJSONObject(k)?.optInt("c", 0) ?: 0) }
                    .sortedByDescending { it.second }
                    .take(MAX_TITLES * 3 / 4)
                val trimmed = JSONObject()
                keep.forEach { (k, _) -> root.optJSONObject(k)?.let { trimmed.put(k, it) } }
                KV.put(KEY, trimmed.toString())
            } else {
                KV.put(KEY, root.toString())
            }
        } catch (th: Throwable) {
            LOG.i("echo-completeness write-failed: " + th.message)
        }
    }

    /**
     * 旧格式迁移:旧值是纯 int(单源、无源信息)—— 视为"单源观测"降级为待确认,
     * **旧污染(如 37)自动失效**;新格式原样返回。
     */
    private fun migrate(obj: JSONObject?, legacyInt: Int): JSONObject {
        if (obj != null) return obj
        val entry = JSONObject()
        if (legacyInt > 0) {
            entry.put("c", 0)
            val seen = JSONObject()
            seen.put(legacyInt.toString(), JSONArray())
            entry.put("s", seen)
        }
        return entry
    }
}
