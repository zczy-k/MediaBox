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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.CapsuleSegmentedButton
import com.github.tvbox.osc.ui.components.SegmentOption
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.ui.theme.ThemeMode

/**
 * 主题设置:只保留"明暗模式"与"纯黑"两项。
 *
 * 配色已固定为蓝色基调,原版的自定义色 / 预设色 / 配色风格 / 液态玻璃四项调节
 * 全部移除 —— 那些选项既增加理解成本,又在每次切换时触发整套配色重算与重组。
 */
@Composable
fun ThemeSettingsScreen(onNavigateBack: () -> Unit) {
    val config = AppThemeState.config

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

            Spacer(Modifier.height(36.dp))
        }
    }
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
