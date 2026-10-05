package com.github.tvbox.osc.ui.theme

import com.materialkolor.PaletteStyle

/** 明暗模式:只有这三态 */
object ThemeMode {
    const val FOLLOW_SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

/**
 * 固定蓝色基调。
 *
 * 原版开放了 8 个预设色 + 自定义取色器 + 9 种配色风格(81 种组合),每次切换都要跑一遍
 * materialkolor 的 HCT 算法重算整套 ColorScheme。收敛成单一蓝色后:
 * - 配色方案只有浅/深两套,首次用到时算一次,之后切换零计算
 * - 删掉取色器 Sheet、预设色网格、风格选择器三个组件与对应状态
 */
val MediaBoxSeedArgb: Int = 0xFF1B6EF3.toInt()

val MediaBoxPaletteStyle: PaletteStyle = PaletteStyle.TonalSpot

/** 主题配置:只剩"明暗模式"和"纯黑"两个维度 */
data class ThemeConfig(
    val mode: Int,
    val pureBlack: Boolean,
)
