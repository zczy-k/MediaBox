package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨源升清判据单测(P0-1)。锁四件事:
 * ① 只升不降(θ + ΔS 双门槛);② 未收敛/未知尺寸不切;③ 天花板与设备上限;
 * ④ 一次到位取最优、同分保序。
 */
class CrossSourceUpgradePolicyTest {

    private fun v(w: Int, h: Int, bw: Int = 0, flag: String = "") =
        VideoQualityPolicy.Variant(w, h, bw, VideoQualityPolicy.Confidence.MEASURED, flag)

    private val sd = v(640, 480, flag = "A1")       // S≈554
    private val hd = v(1280, 720, flag = "A2")      // S≈960
    private val fhd = v(1920, 1080, flag = "B1")    // S=1440
    private val qhd = v(2560, 1440, flag = "B2")    // S=1920

    private fun cand(key: String, variant: VideoQualityPolicy.Variant) =
        CrossSourceUpgradePolicy.Candidate(key, variant)

    // ==================== worthCrossSource ====================

    @Test
    fun `未收敛不跨源`() {
        assertFalse(
            CrossSourceUpgradePolicy.worthCrossSource(sd, fhd, qualityFirst = false, sourceConverged = false),
        )
    }

    @Test
    fun `尺寸未知不跨源`() {
        assertFalse(
            CrossSourceUpgradePolicy.worthCrossSource(null, fhd, false, true),
        )
        assertFalse(
            CrossSourceUpgradePolicy.worthCrossSource(sd, VideoQualityPolicy.Variant(), false, true),
        )
    }

    @Test
    fun `大幅提升值得跨源`() {
        // 540P(960×540) → 1080P:S 比约 2.0,远超 θ 且 ΔS 足够
        assertTrue(
            CrossSourceUpgradePolicy.worthCrossSource(v(960, 540), fhd, false, true),
        )
    }

    @Test
    fun `小幅提升不足ΔS不值得`() {
        // 720P → 768P:比值虽过 θ,但 ΔS 不足,不折腾
        assertFalse(
            CrossSourceUpgradePolicy.worthCrossSource(v(1280, 720), v(1366, 768), false, true),
        )
    }

    @Test
    fun `自动档当前已达1080P锚不折腾`() {
        assertFalse(
            CrossSourceUpgradePolicy.worthCrossSource(fhd, qhd, qualityFirst = false, sourceConverged = true),
        )
        // 画质优先档则允许继续追
        assertTrue(
            CrossSourceUpgradePolicy.worthCrossSource(fhd, qhd, qualityFirst = true, sourceConverged = true),
        )
    }

    @Test
    fun `已达4K级任何模式都不追`() {
        val uhd = v(3840, 2160)
        assertFalse(
            CrossSourceUpgradePolicy.worthCrossSource(uhd, v(3840, 2160), qualityFirst = true, sourceConverged = true),
        )
    }

    @Test
    fun `候选不更高不切`() {
        assertFalse(
            CrossSourceUpgradePolicy.worthCrossSource(fhd, hd, qualityFirst = true, sourceConverged = true),
        )
    }

    // ==================== pickCrossSourceUpgrade ====================

    @Test
    fun `一次到位_取最优而非相邻一档`() {
        val candidates = listOf(
            cand("sB", hd),   // 720P
            cand("sC", fhd),  // 1080P
            cand("sD", qhd),  // 1440P
        )
        val picked = CrossSourceUpgradePolicy.pickCrossSourceUpgrade(
            candidates, current = sd, qualityFirst = true, sourceConverged = true, deviceCapHeight = 0,
        )
        // 必须直奔最优 sD,而不是相邻的 sB
        assertEquals("sD", picked?.sourceKey)
    }

    @Test
    fun `设备上限直接排除超限候选`() {
        val candidates = listOf(
            cand("sC", fhd),  // 1080P
            cand("sD", qhd),  // 1440P > cap 1080
        )
        val picked = CrossSourceUpgradePolicy.pickCrossSourceUpgrade(
            candidates, current = sd, qualityFirst = true, sourceConverged = true, deviceCapHeight = 1080,
        )
        assertEquals("sC", picked?.sourceKey)
    }

    @Test
    fun `全部候选不值得切时返回null`() {
        val candidates = listOf(cand("sB", hd)) // 720P,当前已是 1080P
        val picked = CrossSourceUpgradePolicy.pickCrossSourceUpgrade(
            candidates, current = fhd, qualityFirst = false, sourceConverged = true, deviceCapHeight = 0,
        )
        assertNull(picked)
    }

    @Test
    fun `同分保持传入顺序`() {
        // 两条完全相同画质:S 与 bpp 都相同 ⇒ score 相同,应取先传入者
        val a = cand("first", v(1920, 1080, bw = 8_000_000))
        val b = cand("second", v(1920, 1080, bw = 8_000_000))
        val picked = CrossSourceUpgradePolicy.pickCrossSourceUpgrade(
            listOf(a, b), current = sd, qualityFirst = true, sourceConverged = true, deviceCapHeight = 0,
        )
        assertEquals("first", picked?.sourceKey)
    }

    @Test
    fun `空候选返回null`() {
        assertNull(
            CrossSourceUpgradePolicy.pickCrossSourceUpgrade(
                emptyList(), current = sd, qualityFirst = true, sourceConverged = true, deviceCapHeight = 0,
            ),
        )
    }

    @Test
    fun `未收敛时即使候选更好也不切`() {
        val candidates = listOf(cand("sD", qhd))
        assertNull(
            CrossSourceUpgradePolicy.pickCrossSourceUpgrade(
                candidates, current = sd, qualityFirst = true, sourceConverged = false, deviceCapHeight = 0,
            ),
        )
    }
}