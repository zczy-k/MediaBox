package com.github.tvbox.osc.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 手写的两套配色方案 —— 「流光 · 珊瑚橙」设计稿的唯一实现。
 *
 * <p>**为什么不再用 MaterialKolor 生成**:`dynamicColorScheme(seed)` 会按 HCT 算法
 * 把种子色扩散到整张色板,连 surface / background 都被染上主色相。种子是珊瑚橙时,
 * 白底会变成粉白、卡片会变成粉卡、导航条会变成粉条 —— 与设计稿的"白底 + 纯珊瑚按钮"
 * 完全是两种气质。设计稿的颜色是**指定值**,不是算出来的,所以这里逐个写死。
 *
 * <p>改色只需改本文件与 [MediaBoxSeedArgb](冷启动首帧),不要再动 [AppThemeState]。
 */
object MediaBoxSchemes {

    val Light: ColorScheme = lightColorScheme(
        // 品牌:纯珊瑚,不做明度抬升
        primary = Color(0xFFFF6B4A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFEDE8),
        onPrimaryContainer = Color(0xFFD6431F),
        inversePrimary = Color(0xFFFFB59F),

        // 次级:暖中性,避免整屏只有一种颜色
        secondary = Color(0xFF7A6E6A),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF4EFED),
        onSecondaryContainer = Color(0xFF1A1412),

        // 三级:强调蓝(评分/角标),与主色形成冷暖对比
        tertiary = Color(0xFF2F6BFF),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE7EEFF),
        onTertiaryContainer = Color(0xFF12308A),

        // 底色:暖白,不是纯白;正文近黑但带暖调
        background = Color(0xFFFBF8F7),
        onBackground = Color(0xFF1A1412),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1A1412),
        surfaceVariant = Color(0xFFF4EFED),
        onSurfaceVariant = Color(0xFF7A6E6A),
        surfaceTint = Color(0xFFFF6B4A),

        // 容器层级:全部是白/暖灰,不带主色相
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFEFE8E5),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFDFBFA),
        surfaceContainer = Color(0xFFF7F2F0),
        surfaceContainerHigh = Color(0xFFF4EFED),
        surfaceContainerHighest = Color(0xFFEFE8E5),

        inverseSurface = Color(0xFF332C29),
        inverseOnSurface = Color(0xFFF7F2F0),

        error = Color(0xFFDC2626),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),

        outline = Color(0xFFE8DED9),
        outlineVariant = Color(0xFFF1EAE6),
        scrim = Color(0xFF000000),
    )

    val Dark: ColorScheme = darkColorScheme(
        primary = Color(0xFFFF7F5F),
        onPrimary = Color(0xFF3A0E04),
        primaryContainer = Color(0xFF5A1E0E),
        onPrimaryContainer = Color(0xFFFFD9CE),
        inversePrimary = Color(0xFFD6431F),

        secondary = Color(0xFFB7ABA6),
        onSecondary = Color(0xFF241E1C),
        secondaryContainer = Color(0xFF332E2C),
        onSecondaryContainer = Color(0xFFF5EFEC),

        tertiary = Color(0xFF9DB8FF),
        onTertiary = Color(0xFF0A1E4D),
        tertiaryContainer = Color(0xFF1B3266),
        onTertiaryContainer = Color(0xFFD9E2FF),

        background = Color(0xFF12100F),
        onBackground = Color(0xFFF5EFEC),
        surface = Color(0xFF1A1716),
        onSurface = Color(0xFFF5EFEC),
        surfaceVariant = Color(0xFF332E2C),
        onSurfaceVariant = Color(0xFFB7ABA6),
        surfaceTint = Color(0xFFFF7F5F),

        surfaceBright = Color(0xFF2E2927),
        surfaceDim = Color(0xFF12100F),
        surfaceContainerLowest = Color(0xFF0D0B0A),
        surfaceContainerLow = Color(0xFF1A1716),
        surfaceContainer = Color(0xFF1F1B1A),
        surfaceContainerHigh = Color(0xFF2A2523),
        surfaceContainerHighest = Color(0xFF332E2C),

        inverseSurface = Color(0xFFF5EFEC),
        inverseOnSurface = Color(0xFF332C29),

        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),

        outline = Color(0xFF3A3431),
        outlineVariant = Color(0xFF2A2523),
        scrim = Color(0xFF000000),
    )
}
