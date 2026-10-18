package com.github.tvbox.osc.ui.activity

import kotlin.math.ceil

/**
 * **按源总数分档**的搜源计划(纯函数,可 JVM 单测;2026-10-17 用户定案)。
 *
 * <p>## 解决的现场
 * 池子大小因人而异(几十到三百多):固定截断会让范围外的源永不参与换源;
 * 而"自动一路搜到全量"又把人挂在等待里 —— 冷门片 0 命中时自动链跑完 315 个源要四五分钟。
 * 上一版我把它拍成"自动跑两轮(1/3→1/2)就收口",那是**用轮次数替他决定等多久**,
 * 而且小订阅(几十个源)也被切成两轮,白多点一次。
 *
 * <p>## 分档口径(用户 2026-10-17 指定)
 * <pre>
 *   总数 ≤ 99   ⇒ 1 档跑完(池子小,再分档只是让人多点一次)
 *   100 ~ 300   ⇒ 2 档:1/2 → 全量
 *   &gt; 300      ⇒ 3 档:1/3 → 2/3 → 全量
 * </pre>
 * **自动链只跑第 1 档**;小池的第 1 档本身就是全量,所以"自动搜多少个"在小池上没有歧义。
 * 剩下的每一档都要用户点一次「继续找源」才推进 —— 等待时长由他决定。
 *
 * <p>## 为什么累计单调不减
 * 每档必须在**上一档已问过的源**之上**追加**,不能按新比例重新取样 —— 否则会出现
 * "搜过的又被搜 / 有的永远搜不到"。[poolSizeForWave] 返回**累计**要问到的源数。
 *
 * <p>纯逻辑,无 IO / Android 依赖 —— 由 `ExpandPoolPolicyTest` 锁行为。
 */
object ExpandPoolPolicy {

    /** ≤ 这个数就一档跑完(小池不分档) */
    const val SMALL_POOL_MAX = 99

    /** ≤ 这个数分两档;再大分三档 */
    const val MID_POOL_MAX = 300

    /** 第 1 档(自动那一档)编号 */
    const val FIRST_WAVE = 1

    /** 这个池子分几档跑完。[total] ≤ 0 ⇒ 0 档(没源可搜) */
    @JvmStatic
    fun waveCount(total: Int): Int = when {
        total <= 0 -> 0
        total <= SMALL_POOL_MAX -> 1
        total <= MID_POOL_MAX -> 2
        else -> 3
    }

    /**
     * 第 [wave] 档**累计**应问到的源数(单调不减,最后一档 = 全量)。
     *
     * @param total 可用源总数(过滤后);<=0 返回 0
     * @param wave  档号(从 [FIRST_WAVE] 起,越界钳到范围内)
     */
    @JvmStatic
    fun poolSizeForWave(total: Int, wave: Int): Int {
        if (total <= 0) return 0
        val n = waveCount(total)
        val w = wave.coerceIn(FIRST_WAVE, n)
        if (w >= n) return total
        // 向上取整:别让"下一档"在小池上退化成"不搜"
        return ceil(total.toDouble() * w / n).toInt().coerceIn(1, total)
    }

    /** 第 [wave] 档之后还有没有档 —— 决定页面上该不该出「继续找源」 */
    @JvmStatic
    fun hasNextWave(total: Int, wave: Int): Boolean = wave < waveCount(total)

    /** 还剩多少个源没问(用于"问过 N 个,还有 M 个没问"这行话) */
    @JvmStatic
    fun remainingFor(total: Int, wave: Int): Int =
        (total - poolSizeForWave(total, wave)).coerceAtLeast(0)

    /**
     * **兜底链失败时是否该自动推进下一档**(2026-10-18)。
     *
     * <p>## 解决的现场(真机《怦然心动》)
     * 源池 317,自动链只跑了第 1 档(106 个),9 个候选全失败就报了「视频播放出错」
     * —— 而此时**还有 211 个源从来没被问过**,搜索在 9 秒后还在正常跑完第一档。
     * 相当于只用了 33% 的源,就告诉用户"这部片看不了"。
     *
     * <p>## 为什么自动链要能跨档(与"手动一次一档"不矛盾)
     * "一次点击=一档"是给**用户手动**用的:他想自己决定等多久,所以省着投。
     * 但**兜底链**这条路上用户已经在等画面了 —— 此时"省流量/省时间"是次要的,
     * **"能不能播"才是唯一重要的**。把没问过的源晾着不投,是纯粹的体验损失。
     *
     * <p>## 三道闸(缺一就会变成"无限重搜")
     * <ol>
     *   <li>[allCandidatesFailed]:只有"把所有已找到的候选都试过且都失败"时才推进。
     *       还有候选没试完就继续试候选,不必再搜。</li>
     *   <li>[hasMoreWave]:还有档没跑。跑完就没有"推进"可言。</li>
     *   <li>[autoAdvancedWaves] < [maxAutoWaves]:**必须有上限**。
     *       否则"推进→失败→再推进"会变成 2026-10-13 那种无限循环
     *       (真机:token=13→14 反复重搜,用户看到"卡死无举动")。</li>
     * </ol>
     *
     * @param allCandidatesFailed 当前候选表里的源本场都失败过了
     * @param hasMoreWave 还有下一档可跑
     * @param autoAdvancedWaves 本场已被兜底链自动推进过几次
     * @param maxAutoWaves 自动推进的次数上限
     */
    @JvmStatic
    fun shouldAutoAdvanceOnExhaustion(
        allCandidatesFailed: Boolean,
        hasMoreWave: Boolean,
        autoAdvancedWaves: Int,
        maxAutoWaves: Int,
    ): Boolean = allCandidatesFailed && hasMoreWave && autoAdvancedWaves < maxAutoWaves

    /**
     * 兜底链自动推进的次数上限。
     *
     * <p>取 3:三档池(>300 个源)正好能把全池问一遍 —— 这正是"只要还有源就不能报没救"的兑现。
     * 超过 3 次还失败,说明确实没有可用源,该给结论了(继续重搜只会让用户干等)。
     */
    const val MAX_AUTO_ADVANCE_WAVES = 3
}
