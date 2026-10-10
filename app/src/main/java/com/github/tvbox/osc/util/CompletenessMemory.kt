package com.github.tvbox.osc.util

import org.json.JSONObject

/**
 * 影片"跨源参考集数集合"记忆(P-完整性第二阶段 A2,2026-10-10;v3 重构)。
 *
 * <p>## 从"权威 max 集数"到"参考集数集合"
 * v1 存储的是**单值权威**(该片应有 N 集),两个盲区:
 * <ul>
 *   <li>识别不了**中间缺集**(第 1-8、10-12 集看不出缺第 9 集);</li>
 *   <li>单源污染(花絮被命名为纯序号)会永久抬高权威 —— 《花开锦绣》37 集误标的根因。</li>
 * </ul>
 * v3 改为存储**每个源的正片集号集合**(区间编码),确认/覆盖率/缺失全部由
 * [EpisodeSetMath] 在读取时按集合语义计算:
 * <ul>
 *   <li>参考集合 = 各源实测**并集**;</li>
 *   <li>可信参考集 = 被 **≥2 个独立源**实测到的集号(单源观测不进入缺集断言) ——
 *       天堂源的"第 37 项花絮"因只有单源观测,自动失效;</li>
 *   <li>某源的缺失 = 可信参考集 − 该源集合。</li>
 * </ul>
 *
 * <p>## 边界(文档 19)
 * 所有源共享同一上游时,污染会因"多源一致"而确认 —— 这是数据本身的局限,记录在案;
 * 集名不可数的源("TC中字"等)产不出正片集合,对该片整体不判定(权威缺失 ⇒ 不降权不标注)。
 */
object CompletenessMemory {

    const val AUTHORITY_MAX = 200

    private const val KEY = "completeness_sets"
    private const val MAX_TITLES = 500

    /** 记录一个源的 REGULAR 正片集号集合(区间编码落盘;空集合不记) */
    fun record(titleKey: String, sourceKey: String, regular: Set<Int>) {
        if (titleKey.isEmpty() || sourceKey.isEmpty()) return
        val capped = regular.filter { it in 1..AUTHORITY_MAX }
        if (capped.isEmpty()) return
        try {
            val root = try {
                JSONObject(KV.get(KEY, ""))
            } catch (th: Throwable) {
                JSONObject()
            }
            val entry = root.optJSONObject(titleKey) ?: JSONObject()
            entry.put(sourceKey, EpisodeSetMath.encode(capped))
            root.put(titleKey, entry)
            // 容量上限:超出时淘汰"已见最大集数最小"的片(参考价值最低)
            if (root.length() > MAX_TITLES) {
                val keep = root.keys().asSequence()
                    .map { k ->
                        val e = root.optJSONObject(k)
                        val maxEp = e?.keys()?.asSequence()
                            ?.maxOfOrNull { EpisodeSetMath.decode(e.optString(it)).maxOrNull() ?: 0 } ?: 0
                        k to maxEp
                    }
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

    /** 读取该片各源的正片集号集合;无记录返回空 map */
    fun snapshot(titleKey: String): Map<String, Set<Int>> {
        if (titleKey.isEmpty()) return emptyMap()
        return try {
            val entry = JSONObject(KV.get(KEY, "")).optJSONObject(titleKey)
                ?: return emptyMap()
            val out = HashMap<String, Set<Int>>()
            for (src in entry.keys()) {
                val set = EpisodeSetMath.decode(entry.optString(src))
                if (set.isNotEmpty()) out[src] = set
            }
            out
        } catch (th: Throwable) {
            LOG.i("echo-completeness read-failed: " + th.message)
            emptyMap()
        }
    }

    /** 可信参考集:≥2 个独立源实测到的集号(面板标注/降权只信它) */
    fun confirmedSet(titleKey: String): Set<Int> =
        EpisodeSetMath.confirmed(snapshot(titleKey).values)
}
