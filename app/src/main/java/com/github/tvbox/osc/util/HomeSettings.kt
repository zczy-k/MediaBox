package com.github.tvbox.osc.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object HomeSettings {

    enum class HomeLayout { Horizontal, Vertical }

    /** 首页网格卡片比例:自动采样当前源/分类;手动模式统一指定画框比例。 */
    enum class PosterRatioMode { Automatic, Portrait, Landscape }

    // 未登记 KVKeySpec:读取必须带默认值(靠默认值携带 String 类型),KV.get(key) 不带默认值会解不出
    private const val KEY_LAYOUT = "home_layout"

    private const val VALUE_LAYOUT_HORIZONTAL = "horizontal"

    private const val VALUE_LAYOUT_VERTICAL = "vertical"

    /**
     * 首页栅格列数设置键。已登记进 KVKeySpec(HOME_COLUMNS → int),读取仍带默认值作双保险。
     * 取值只认 2 / 3,其余一律归一到 3。
     */
    private val KEY_COLUMNS = HawkConfig.HOME_COLUMNS

    const val DEFAULT_COLUMNS = 3

    private val mutableLayout = MutableStateFlow(current())

    val layoutFlow: StateFlow<HomeLayout> = mutableLayout

    private val mutableColumns = MutableStateFlow(currentColumns())

    /** 首页栅格列数(2/3)。设置页改动后即时生效:HomeGridLayout 收集它做重组,无需重启 */
    val columnsFlow: StateFlow<Int> = mutableColumns

    private val mutablePosterRatioMode = MutableStateFlow(currentPosterRatioMode())

    /** 首页图片比例模式;切换后首页即时重排 */
    val posterRatioModeFlow: StateFlow<PosterRatioMode> = mutablePosterRatioMode

    fun current(): HomeLayout =
        if (KV.get(KEY_LAYOUT, VALUE_LAYOUT_VERTICAL) == VALUE_LAYOUT_HORIZONTAL) {
            HomeLayout.Horizontal
        } else {
            HomeLayout.Vertical
        }

    fun setLayout(layout: HomeLayout) {
        KV.put(
            KEY_LAYOUT,
            if (layout == HomeLayout.Vertical) VALUE_LAYOUT_VERTICAL else VALUE_LAYOUT_HORIZONTAL,
        )
        mutableLayout.value = layout
    }

    /** 归一:只接受 2,其余(含 3 与非法值)一律返回 3 */
    fun currentColumns(): Int = if (KV.get(KEY_COLUMNS, DEFAULT_COLUMNS) == 2) 2 else 3

    fun setColumns(columns: Int) {
        val normalized = if (columns == 2) 2 else DEFAULT_COLUMNS
        KV.put(KEY_COLUMNS, normalized)
        mutableColumns.value = normalized
    }

    private fun currentPosterRatioMode(): PosterRatioMode = when (
        KV.get(HawkConfig.HOME_POSTER_RATIO_MODE, "auto").lowercase(java.util.Locale.ROOT)
    ) {
        "portrait" -> PosterRatioMode.Portrait
        "landscape" -> PosterRatioMode.Landscape
        else -> PosterRatioMode.Automatic
    }

    fun setPosterRatioMode(mode: PosterRatioMode) {
        val value = when (mode) {
            PosterRatioMode.Automatic -> "auto"
            PosterRatioMode.Portrait -> "portrait"
            PosterRatioMode.Landscape -> "landscape"
        }
        KV.put(HawkConfig.HOME_POSTER_RATIO_MODE, value)
        mutablePosterRatioMode.value = mode
    }
}
