package com.github.tvbox.osc.player

/**
 * 换线时按**线路健康度**重排候选(2026-10-12)。
 *
 * <p>## 为什么需要单独一个类,而不是塞进 [LineQualitySelector]
 * [LineQualitySelector] 管的是"画质优选"(按实测清晰度排序),本类管的是
 * "有效性降权"(把近期失败/慢的线路排后)。两者判据不同、数据来源不同
 * (一个读 [com.github.tvbox.osc.player.VideoQualityMemory],一个读 [LineHealthMemory]),
 * 混在一起会让"按画质选"和"按健康度排"互相干扰。
 *
 * <p>## 硬约束(与 LineHealthPolicy 同源)
 * **只排后,永不剔除**。参数名用 [LineHealthSelector.reorder] 而不是 filter ——
 * 调用方拿到的仍是**全部**候选,只是顺序变了。
 * `tryNextLine()` 本来就会把所有线路试完才 exhausted,所以顺序调整不会漏试,
 * 但把坏线提前会让用户多等几次起播失败。
 */
object LineHealthSelector {

    /**
     * 按健康度重排候选线路:**稳定排序,分数相同者保持原序**。
     *
     * <p>为什么必须稳定:候选列表本身已由 `EpisodeMatcher.lineFlagsInDisplayOrder`
     * 按站点展示顺序排好 —— 那是用户/站点约定的顺序。没有确凿健康证据时
     * **不应改动它**,否则"明明没坏却换了顺序"会让用户觉得线路在乱跳。
     *
     * <p>⚠️ 本方法读 KV(KV 在 unit test 下不可用),所以它自己**不做单测**;
     * 纯排序逻辑在 [LineHealthPolicy.reorderByScore],那里可单测。
     *
     * @param flags 调用方已按展示顺序排好的候选(可含空串,内部会过滤)
     * @param keys 与 flags 一一对应的 [LineHealthMemory.keyOf] 结果(调用方已构造)
     * @return 与 flags 等长的重排结果
     */
    @JvmStatic
    fun reorder(flags: List<String>, keys: List<String>, now: Long = System.currentTimeMillis()): List<String> {
        if (flags.size < 2) return flags
        if (keys.size != flags.size) return flags   // 契约不符:不动顺序(宁可不优化,不可排错)
        val scores = LineHealthMemory.orderScores(keys.filter { it.isNotEmpty() }, now)
        // 全零 ⇒ 没有记忆可读,原样返回(省一次排序)
        if (scores.values.none { it != 0 }) return flags
        return LineHealthPolicy.reorderByScore(flags, keys) { k -> scores[k] ?: 0 }
    }
}
