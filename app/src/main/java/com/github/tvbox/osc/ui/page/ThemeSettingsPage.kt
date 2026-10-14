@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.CapsuleSegmentedButton
import com.github.tvbox.osc.ui.components.SegmentOption
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsOptionMenuRow
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.ui.theme.ThemeMode
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HomeSettings
import org.greenrobot.eventbus.EventBus

/**
 * 主题设置:明暗模式、纯黑,以及三条界面布局(首页/收藏的列数与海报比例)。
 *
 * 配色已固定为蓝色基调,原版的自定义色 / 预设色 / 配色风格 / 液态玻璃四项调节
 * 全部移除 —— 那些选项既增加理解成本,又在每次切换时触发整套配色重算与重组。
 *
 * <p>布局三项原先在偏好设置页:它们是"看起来是什么样",跟明暗模式同一件事,
 * 放一起用户才找得到,偏好页也能瘦下来。
 */
@Composable
fun ThemeSettingsScreen(onNavigateBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val config = AppThemeState.config
    val state by vm.state

    val listState = rememberScrollState()

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(R.drawable.ic_arrow_left, stringResource(R.string.common_back), onClick = onNavigateBack)
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.height(topPad - 20.dp))

            SettingsGroup(title = stringResource(R.string.theme_color)) {
                SettingsCard(SettingsCardPosition.SINGLE) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        ThemeModeRow(
                            currentMode = config.mode,
                            onModeSelected = { AppThemeState.setMode(it) },
                        )
                    }
                }
            }

            SettingsGroup(title = stringResource(R.string.settings_group_layout)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    HomeColumnsRow(
                        columns = state.homeColumns,
                        onSelect = { columns ->
                            // setColumns 同时落 KV 并推 columnsFlow(首页栅格立即重组);refreshState 只重读本页状态
                            HomeSettings.setColumns(columns)
                            vm.refreshState()
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    HomePosterRatioRow(
                        mode = state.homePosterRatioMode,
                        onSelect = {
                            HomeSettings.setPosterRatioMode(it)
                            vm.refreshState()
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    CollectColumnsRow(
                        columns = state.collectColumns,
                        onSelect = { columns ->
                            vm.put(HawkConfig.COLLECT_COLUMNS, columns)
                            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_COLLECT_LAYOUT_CHANGE))
                        },
                    )
                }
            }

            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun HomePosterRatioRow(
    mode: HomeSettings.PosterRatioMode,
    onSelect: (HomeSettings.PosterRatioMode) -> Unit,
) {
    val modes = HomeSettings.PosterRatioMode.values()
    val options = listOf(
        stringResource(R.string.settings_home_poster_ratio_auto),
        stringResource(R.string.settings_home_poster_ratio_portrait),
        stringResource(R.string.settings_home_poster_ratio_landscape),
    )
    SettingsOptionMenuRow(
        title = stringResource(R.string.settings_home_poster_ratio),
        subtitle = stringResource(R.string.settings_home_poster_ratio_subtitle),
        leadingIconRes = R.drawable.ic_pref_collect_columns,
        valueText = options[mode.ordinal],
        options = options,
        selectedIndex = mode.ordinal,
        onSelect = { idx -> onSelect(modes.getOrElse(idx) { HomeSettings.PosterRatioMode.Automatic }) },
    )
}

@Composable
private fun HomeColumnsRow(columns: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        stringResource(R.string.settings_home_columns_three),
        stringResource(R.string.settings_home_columns_two),
    )
    val selectedIndex = if (columns == 3) 0 else 1
    SettingsOptionMenuRow(
        title = stringResource(R.string.settings_home_columns),
        leadingIconRes = R.drawable.ic_pref_collect_columns,
        valueText = options[selectedIndex],
        options = options,
        selectedIndex = selectedIndex,
        onSelect = { idx -> onSelect(if (idx == 0) 3 else 2) },
    )
}

@Composable
private fun CollectColumnsRow(columns: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        stringResource(R.string.settings_collect_columns_three),
        stringResource(R.string.settings_collect_columns_two),
    )
    val selectedIndex = if (columns == 3) 0 else 1
    SettingsOptionMenuRow(
        title = stringResource(R.string.settings_collect_columns),
        leadingIconRes = R.drawable.ic_pref_collect_columns,
        valueText = options[selectedIndex],
        options = options,
        selectedIndex = selectedIndex,
        onSelect = { idx -> onSelect(if (idx == 0) 3 else 2) },
    )
}

@Composable
private fun ThemeModeRow(currentMode: Int, onModeSelected: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.theme_mode),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        CapsuleSegmentedButton(
            options = listOf(
                SegmentOption(
                    label = stringResource(R.string.theme_mode_auto),
                    value = ThemeMode.FOLLOW_SYSTEM,
                    iconPainter = painterResource(R.drawable.ic_brightness_auto),
                ),
                SegmentOption(
                    label = stringResource(R.string.theme_mode_light),
                    value = ThemeMode.LIGHT,
                    iconPainter = painterResource(R.drawable.ic_light_mode),
                ),
                SegmentOption(
                    label = stringResource(R.string.theme_mode_dark),
                    value = ThemeMode.DARK,
                    iconPainter = painterResource(R.drawable.ic_dark_mode),
                ),
            ),
            selectedValue = currentMode,
            onOptionSelected = onModeSelected,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
