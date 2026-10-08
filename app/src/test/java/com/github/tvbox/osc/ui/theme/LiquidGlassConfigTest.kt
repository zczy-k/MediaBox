package com.github.tvbox.osc.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通透度派生量:默认值必须是恒等点 —— 这是"加了通透度但默认观感与改动前一致"的唯一保证 */
class LiquidGlassConfigTest {

    private fun at(translucency: Float) = LiquidGlassConfig(
        navbarEnabled = true,
        controlsEnabled = true,
        blurDp = 20f,
        distortionDp = 30f,
        translucency = translucency,
        dispersion = false,
    )

    @Test
    fun defaultTranslucency_isIdentity() {
        // 通透度已收敛成编译期常量(见 LiquidGlassState),这里直接断言**应用实际用的那个值**
        // 是恒等点 —— 比断言一个可能被删掉的常量名更贴近"默认观感与改动前一致"的初衷
        val config = at(LiquidGlassState.config.translucency)
        assertEquals(1f, config.containerAlphaScale, 0f)
        assertEquals(0f, config.contentBrightness, 0f)
        assertEquals(1f, config.contentContrast, 0f)
    }

    @Test
    fun translucency_scalesContainerAlphaMonotonically() {
        assertEquals(2f, at(0f).containerAlphaScale, 0f)
        assertEquals(0f, at(1f).containerAlphaScale, 0f)
        assertTrue(at(0.75f).containerAlphaScale < at(0.25f).containerAlphaScale)
    }

    @Test
    fun translucency_compensatesContentReadability() {
        // 底色越淡,采样内容压得越暗、对比抬得越高(补偿文字可读性)
        val opaque = at(0f)
        val clear = at(1f)
        assertTrue(clear.contentBrightness < opaque.contentBrightness)
        assertTrue(clear.contentContrast > opaque.contentContrast)
    }
}
