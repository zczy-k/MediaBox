package com.github.tvbox.osc.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨源提示判据单测:锁住"何时提示"的四条口径。
 *
 * <p>1 源未收敛不提示(不抢源内 sweep 的自动升档);2 1080P 锚与 4K 天花板不打扰;
 * 3 θ 比值与 ΔS 差值双门槛(防 720→768 骚扰);4 画质优先档更激进。
 */
class CrossSourceHintPolicyTest {

    private fun v(w: Int, h: Int) =
        VideoQualityPolicy.Variant(w, h, 0, VideoQualityPolicy.Confidence.MEASURED, "")

    @Test
    fun notConverged_neverHints() {
        assertFalse(
            CrossSourceHintPolicy.shouldHint(
                v(1280, 720), v(1920, 1080), qualityFirst = false, sourceConverged = false,
            ),
        )
    }

    @Test
    fun unknownSide_neverHints() {
        assertFalse(
            CrossSourceHintPolicy.shouldHint(
                null, v(1920, 1080), qualityFirst = false, sourceConverged = true,
            ),
        )
        assertFalse(
            CrossSourceHintPolicy.shouldHint(
                v(1280, 720), VideoQualityPolicy.Variant(), qualityFirst = false, sourceConverged = true,
            ),
        )
    }

    @Test
    fun autoMode_currentAtAnchor_doesNotHint() {
        // 当前已 1080P 锚,自动档不折腾
        assertFalse(
            CrossSourceHintPolicy.shouldHint(
                v(1920, 1080), v(3840, 2160), qualityFirst = false, sourceConverged = true,
            ),
        )
    }

    @Test
    fun atCeiling_neverHints_evenQualityFirst() {
        assertFalse(
            CrossSourceHintPolicy.shouldHint(
                v(3840, 2160), v(3840, 2160), qualityFirst = true, sourceConverged = true,
            ),
        )
    }

    @Test
    fun autoMode_clearUpgrade_hints() {
        // 540P -> 1080P:比值远超 θ 且 ΔS 足够大
        assertTrue(
            CrossSourceHintPolicy.shouldHint(
                v(960, 540), v(1920, 1080), qualityFirst = false, sourceConverged = true,
            ),
        )
    }

    @Test
    fun smallUpgrade_belowDeltaS_doesNotHint() {
        // 720P -> 768P:比值虽过 θ,但 ΔS 不足,不打扰
        assertFalse(
            CrossSourceHintPolicy.shouldHint(
                v(1280, 720), v(1366, 768), qualityFirst = false, sourceConverged = true,
            ),
        )
    }

    @Test
    fun qualityFirst_isNotStricterThanAuto() {
        // 1280×720 → 1600×900,S 比约 1.25,过 AUTO 的 θ=1.08;画质优先(θ=1.03)必须同样放行
        val cur = v(1280, 720)
        val cand = v(1600, 900)
        assertTrue(CrossSourceHintPolicy.shouldHint(cur, cand, qualityFirst = false, sourceConverged = true))
        assertTrue(CrossSourceHintPolicy.shouldHint(cur, cand, qualityFirst = true, sourceConverged = true))
    }

    @Test
    fun candidateNotBetter_doesNotHint() {
        assertFalse(
            CrossSourceHintPolicy.shouldHint(
                v(1920, 1080), v(1280, 720), qualityFirst = true, sourceConverged = true,
            ),
        )
    }
}
