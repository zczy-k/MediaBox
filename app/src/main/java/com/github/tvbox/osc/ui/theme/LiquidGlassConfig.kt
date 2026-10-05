package com.github.tvbox.osc.ui.theme

// 顶栏玻璃背景带的高出量(dp):顶栏与"玻璃带"之间的额外边距,导航栏度量也要用同一值
const val GLASS_BACKDROP_BAND_MARGIN_DP = 64

data class LiquidGlassConfig(
    val navbarEnabled: Boolean,
    val controlsEnabled: Boolean,
    val blurDp: Float,
    val distortionDp: Float,
    val translucency: Float,
    val dispersion: Boolean,
) {
    val containerAlphaScale: Float get() = 2f - translucency * 2f

    /** 通透度 → 采样内容亮度补偿(底色越淡越压暗,保住压在玻璃上的文字) */
    val contentBrightness: Float get() = (0.5f - translucency) * 0.24f

    /** 通透度 → 采样内容对比度补偿 */
    val contentContrast: Float get() = 1f + (translucency - 0.5f) * 0.5f
}
