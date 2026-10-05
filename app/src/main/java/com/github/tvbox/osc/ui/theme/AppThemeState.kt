package com.github.tvbox.osc.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.materialkolor.dynamicColorScheme

/**
 * 主题状态:固定蓝色 + 明暗模式 + 纯黑。
 *
 * 原版有三张缓存表(自定义色、预览色、深浅各一套),切色/切风格时每次都要重算并广播重组。
 * 现在只有浅/深两套方案,首次用到时算一次即常驻,后续切换是纯引用比较。
 */
object AppThemeState {

    private var current by mutableStateOf(load())

    val config: ThemeConfig get() = current

    private fun load(): ThemeConfig = ThemeConfig(
        mode = KV.get(HawkConfig.THEME_MODE, ThemeMode.FOLLOW_SYSTEM),
    )

    fun setMode(mode: Int) {
        KV.put(HawkConfig.THEME_MODE, mode)
        current = current.copy(mode = mode)
    }

    fun isDark(systemDark: Boolean): Boolean = when (current.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }

    private var lightCache: ColorScheme? = null

    private var darkCache: ColorScheme? = null

    /** 固定种子,深浅各一份,懒算一次 */
    fun scheme(isDark: Boolean): ColorScheme {
        if (isDark) {
            darkCache?.let { return it }
            return dynamicColorScheme(Color(MediaBoxSeedArgb), isDark = true, style = MediaBoxPaletteStyle)
                .also { darkCache = it }
        }
        lightCache?.let { return it }
        return dynamicColorScheme(Color(MediaBoxSeedArgb), isDark = false, style = MediaBoxPaletteStyle)
            .also { lightCache = it }
    }

    /** 音乐页按封面取色渲染用:配色风格固定,只换种子;按 (种子,深浅) 缓存,切歌不重算 */
    private val coverCache = HashMap<Pair<Int, Boolean>, ColorScheme>()

    fun coverScheme(seedArgb: Int, isDark: Boolean): ColorScheme =
        coverCache.getOrPut(seedArgb to isDark) {
            dynamicColorScheme(Color(seedArgb), isDark = isDark, style = MediaBoxPaletteStyle)
        }
}
