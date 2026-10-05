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

/**
 * 玻璃效果参数:固定值,不再开放给用户调。
 *
 * 原版把模糊 / 扭曲 / 通透度 / 色散 四项做成滑块并持久化到 KV,每拖一次都要重建整条玻璃
 * 渲染链(backdrop + RenderEffect)并触发大范围重组。这里收敛成编译期常量:渲染参数在首次
 * 组合时即固定,之后不再有状态读取与重组。
 *
 * 想彻底关掉玻璃效果(最省电、最跟手):把两个 Enabled 改成 false 即可 —— 调用侧已按
 * `navbarEnabled && SDK >= S` 判断,关掉后自动退回纯色顶栏/导航栏。
 */
object LiquidGlassState {
    val config: LiquidGlassConfig = LiquidGlassConfig(
        navbarEnabled = true,
        controlsEnabled = true,
        blurDp = 20f,
        distortionDp = 30f,
        translucency = 0.5f,
        dispersion = true,
    )
}
