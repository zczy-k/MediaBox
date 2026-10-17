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
}
