package com.github.tvbox.osc.ui.activity

import kotlin.math.ceil

/**
 * **按比例动态扩池**判据(纯函数,可 JVM 单测,2026-10-13)。
 *
 * <p>## 解决的现场
 * 详情页换源/聚合搜索此前对候选源**固定截断**([DetailViewModel.fallbackPoolCap],默认 120):
 * 源集合有 300+ 源时,其余源**永不参与搜索** ⇒ 冷门片明明有源却搜不到。
 *
 * <p>用户提出的方案:不是固定值,而是**按源总数比例**逐轮扩大,直到全量 —— 因为源数量因人而异,
 * 固定值要么不够(冷门片)、要么过多(小订阅白排队)。
 *
 * <p>## 轮次比例(用户指定)
 * <pre>
 *   第 1 轮 = 1/3       第 2 轮 = 1/2
 *   第 3 轮 = 2/3       第 4 轮 = 100%(全量,终结)
 * </pre>
 * 触发条件:**上一轮全部源收口且 0 命中** ⇒ 进入下一轮(扩大池)。
 * 终结:全量轮仍 0 命中 ⇒ 调用方提示"未找到可用片源"(见 [isFinalRound])。
 *
 * <p>## 为什么单调不减是硬要求
 * 每轮必须在**上一轮的全部源**之上**追加**新源(不能重新从大池子里按新比例取样)
 * —— 否则"第 2 轮"可能把"第 1 轮已搜过的源"排除在外,出现"搜过的又被搜/有的永远搜不到"。
 * [poolSizeForRound] 因此返回的是**累计**要搜的源数(单调不减),调用方按它切前 N 个。
 *
 * <p>纯逻辑,无 IO / Android 依赖 —— 由 `ExpandPoolPolicyTest` 锁行为。
 */
object ExpandPoolPolicy {

    /** 比例档位(分子/分母):1/3 → 1/2 → 2/3 → 全量。终结轮由 [FINAL_ROUND] 表示。 */
    private val RATIOS: List<Pair<Int, Int>> = listOf(1 to 3, 1 to 2, 2 to 3)

    /** 终结轮编号:该轮用全量;再往后仍无果即宣告"未找到"。 */
    const val FINAL_ROUND = 4

    /** 第 1 轮(初始)编号 */
    const val FIRST_ROUND = 1

    /**
     * 第 [round] 轮**累计**应搜的源数(单调不减,封顶 [total])。
     *
     * @param total 可用源总数(过滤后);<=0 返回 0
     * @param round 轮次(从 [FIRST_ROUND] 起;[FINAL_ROUND] 及以后 = 全量)
     * @return 该轮累计要搜的源数,∈ [0, total]
     */
    @JvmStatic
    fun poolSizeForRound(total: Int, round: Int): Int {
        if (total <= 0) return 0
        val normalized = maxOf(round, FIRST_ROUND)
        if (normalized >= FINAL_ROUND) return total
        val (num, den) = RATIOS[normalized - 1]
        // 向上取整:源数很少时(如 5 个源、1/3)也要至少推出 2 个,否则"扩池"在小订阅上退化为"不扩"
        val size = ceil(total.toDouble() * num / den).toInt()
        return size.coerceIn(1, total)
    }

    /**
     * 是否已是终结轮(全量轮)。调用方据此决定:全量轮仍 0 命中 ⇒ 提示"未找到可用片源"。
     */
    @JvmStatic
    fun isFinalRound(round: Int): Boolean = maxOf(round, FIRST_ROUND) >= FINAL_ROUND

    /**
     * 本轮结束后是否应进入下一轮扩池(纯判据)。
     *
     * <p>条件:**本轮已全部收口** 且 **0 命中** 且 **还有更多源没搜**(未到全量)。
     * 三者缺一不可:
     * <ul>
     *   <li>未收口 ⇒ 还有源在跑,别急着扩(会与在途结果打架);</li>
     *   <li>已有命中 ⇒ 目的达成,不必扩(冷门片才需要扩);</li>
     *   <li>已到全量 ⇒ 没得扩了,由 [isFinalRound] 走"未找到"终态。</li>
     * </ul>
     *
     * @param hitCount   本轮候选命中数(详情页 fallbackCandidates 里属于本轮的条目数)
     * @param roundDone  本轮是否已全部收口(搜索 all-done)
     * @param curSize    本轮搜的源数(累计)
     * @param total      源总数
     */
    @JvmStatic
    fun shouldExpand(hitCount: Int, roundDone: Boolean, curSize: Int, total: Int): Boolean {
        if (!roundDone) return false
        if (hitCount > 0) return false
        return curSize < total
    }
}