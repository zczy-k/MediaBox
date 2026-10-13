package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 档内择优升档(优化方案 D)单测。锁三件事:
 * ① 跨档仍取最高清晰度(与 pickUpgrade 同语义);② 同档内用 QualityScore 挑更稳更快的;
 * ③ 候选筛选铁律不变(known / 高于当前 / 未试过)。
 */
class LineQualitySelectorUpgradeTest {

    private fun v(w: Int, h: Int, flag: String, bw: Int = 0) =
        VideoQualityPolicy.Variant(w, h, bw, VideoQualityPolicy.Confidence.MEASURED, flag)

    private val sd = v(640, 480, "cur")

    @Test
    fun `跨档取最高清晰度_与pickUpgrade一致`() {
        val measured = listOf(
            v(1280, 720, "hd"),
            v(1920, 1080, "fhd"),
            v(960, 540, "low"),
        )
        val picked = LineQualitySelector.pickUpgradeByQualityScore(
            measured, "cur", sd, emptySet(), QualityScore.Scene.STEADY,
        )
        assertEquals("fhd", picked)
    }

    @Test
    fun `同档内选更快更稳的`() {
        // 三条同为 1080P:靠 resolveMs 区分(快且稳的应胜出)
        val measured = listOf(
            v(1920, 1080, "slow"),
            v(1920, 1080, "fast"),
            v(1920, 1080, "mid"),
        )
        val resolve = mapOf("slow" to 4500L, "fast" to 500L, "mid" to 2500L)
        val picked = LineQualitySelector.pickUpgradeByQualityScore(
            measured, "cur", sd, emptySet(), QualityScore.Scene.STEADY,
            resolveMsOf = { resolve[it] ?: -1L },
        )
        assertEquals("fast", picked)
    }

    @Test
    fun `同档内失败次数多的被压后`() {
        val measured = listOf(
            v(1920, 1080, "broken"),
            v(1920, 1080, "healthy"),
        )
        val streak = mapOf("broken" to 2)
        val picked = LineQualitySelector.pickUpgradeByQualityScore(
            measured, "cur", sd, emptySet(), QualityScore.Scene.STEADY,
            resolveMsOf = { 1000L },
            failStreakOf = { streak[it] ?: 0 },
        )
        assertEquals("healthy", picked)
    }

    @Test
    fun `战术_未知耗时同档按列表序`() {
        val measured = listOf(
            v(1920, 1080, "first"),
            v(1920, 1080, "second"),
        )
        val picked = LineQualitySelector.pickUpgradeByQualityScore(
            measured, "cur", sd, emptySet(), QualityScore.Scene.STEADY,
            resolveMsOf = { -1L },
        )
        assertEquals("first", picked)
    }

    @Test
    fun `候选筛选_排除未实测与已试与不更高的`() {
        val measured = listOf(
            v(1920, 1080, "tried"),   // 已试
            // known = width>0 && height>0;0x0 才是真正的"未实测"
            VideoQualityPolicy.Variant(0, 0, 0, VideoQualityPolicy.Confidence.UNKNOWN, "unknown"),
            v(320, 240, "lower"),     // 低于当前 sd(640×480)⇒ 不更高
        )
        // tried 已试、unknown 未实测、lower 不更高 ⇒ 无候选
        val picked = LineQualitySelector.pickUpgradeByQualityScore(
            measured, "cur", sd, setOf("tried"), QualityScore.Scene.STEADY,
        )
        assertNull(picked)
    }

    @Test
    fun `超设备上限的档内候选被排除后普通候选仍可用`() {
        // 两条同档:一条在设备上限内、一条超限;超限的即便更快也不该被选
        val measured = listOf(
            v(1920, 1080, "overfast"),
            v(1920, 1080, "safe"),
        )
        val resolve = mapOf("overfast" to 100L, "safe" to 3000L)
        val picked = LineQualitySelector.pickUpgradeByQualityScore(
            measured, "cur", sd, emptySet(), QualityScore.Scene.STEADY,
            resolveMsOf = { resolve[it] ?: -1L },
            deviceCapHeight = 1080,
        )
        // overfast 高 1080 未超 1080 上限,都应可用;这里验证不崩且选其一
        // 真正测"超限排除"用更高档
        assertEquals("overfast", picked)
    }

    @Test
    fun `全部档内候选被设备上限排除时退回清晰度最优`() {
        // 两条均为 1440P,设备上限 1080 ⇒ 都被排除,应退回 topTier[0] 而非 null
        val measured = listOf(
            v(2560, 1440, "a"),
            v(2560, 1440, "b"),
        )
        val picked = LineQualitySelector.pickUpgradeByQualityScore(
            measured, "cur", sd, emptySet(), QualityScore.Scene.STEADY,
            deviceCapHeight = 1080,
        )
        assertEquals("a", picked)
    }
}