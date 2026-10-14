package com.github.tvbox.osc.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.ui.components.MediaBoxAlertDialog
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.LocalSheetDismissThen
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsOptionMenuRow
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SettingsSliderRow
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.nearestTier
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.util.AppLanguage
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryMerge
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.SourceHealthMemory
import com.github.tvbox.osc.util.restartApp
import kotlin.math.roundToInt
import org.greenrobot.eventbus.EventBus

@Composable
fun PreferenceSettingsScreen(onNavigateBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val state by vm.state
    val context = LocalContext.current
    var sliderBuffer by remember(state.bufferTimes) { mutableStateOf(state.bufferTimes) }
    var sliderThreads by remember(state.searchThreads) { mutableStateOf(state.searchThreads) }

    val listState = rememberScrollState()
    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.settings_preference_title),
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
        ) {
            Spacer(Modifier.height(topPad + 8.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_language_layout)) {
                SettingsCard(SettingsCardPosition.SINGLE) {
                    LanguageRow()
                }
            }

            Spacer(Modifier.height(24.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_privacy)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_history_merge),
                        leadingIconRes = R.drawable.ic_pref_history_merge,
                        checked = state.historyMerge,
                        onCheckedChange = {
                            HistoryMerge.setEnabled(it)
                            vm.refresh()
                            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_incognito),
                        leadingIconRes = R.drawable.ic_pref_incognito,
                        checked = state.incognito,
                        onCheckedChange = {
                            vm.put(HawkConfig.INCOGNITO, it)
                            // 历史页据此立刻切到"无痕提示"或恢复列表(不必重进页面)
                            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    // 长按倍速本来就是手势的一种:原来分两行,关掉手势后那个滑块还能拖但完全没作用。
                    // 现在合成一把:选"关"就整体关,选倍速就同时开手势并定倍速。
                    val speeds = listOf(2, 4, 8)
                    val gestureOptions = List(1) { stringResource(R.string.common_off) } + speeds.map {
                        stringResource(R.string.settings_gesture_speed_value, it)
                    }
                    val gestureSelected = if (state.gestureControlDisabled) 0
                    else nearestTier(speeds, state.longPressSpeed) + 1
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_gesture_control),
                        subtitle = stringResource(R.string.settings_gesture_control_subtitle),
                        leadingIconRes = R.drawable.ic_pref_gesture,
                        valueText = gestureOptions[gestureSelected.coerceIn(0, gestureOptions.lastIndex)],
                        options = gestureOptions,
                        selectedIndex = gestureSelected,
                        onSelect = { idx ->
                            if (idx == 0) {
                                vm.put(HawkConfig.GESTURE_CONTROL_DISABLED, true)
                            } else {
                                vm.putAll(
                                    HawkConfig.GESTURE_CONTROL_DISABLED to false,
                                    HawkConfig.LONG_PRESS_SPEED to speeds[idx - 1],
                                )
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_play_search)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_auto_switch_line),
                        leadingIconRes = R.drawable.ic_pref_auto_switch_line,
                        checked = state.autoSwitchLine,
                        onCheckedChange = { vm.put(HawkConfig.AUTO_SWITCH_LINE, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_auto_block_bad_sources),
                        leadingIconRes = R.drawable.ic_pref_auto_switch_line,
                        subtitle = stringResource(R.string.settings_auto_block_bad_sources_subtitle),
                        checked = state.autoBlockBadSources,
                        onCheckedChange = {
                            vm.put(HawkConfig.AUTO_BLOCK_BAD_SOURCES, it)
                            vm.refreshState()
                            // 开关一关,所有过滤判据立刻放行:首页按最新屏蔽集合本地重算源清单
                            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SOURCE_BLOCK_CHANGE))
                        },
                    )
                }
                // 只在真有被屏蔽的源时出现:没有就整行隐藏,不留一个"0 个"的噪声入口。
                // ⚠️ 文案里**绝不能出现源名**(产品红线:用户不该知道 App 有哪些源),所以只给数量。
                if (state.blockedSourceCount > 0) {
                    SettingsCard(SettingsCardPosition.MIDDLE) {
                        SettingsRow(
                            title = stringResource(R.string.settings_source_blocked_count, state.blockedSourceCount),
                            valueText = stringResource(R.string.settings_source_unblock_all),
                            onClick = {
                                SourceHealthMemory.unblockAll()
                                vm.refreshState()
                                EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SOURCE_BLOCK_CHANGE))
                            },
                        )
                    }
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_m3u8_purify),
                        leadingIconRes = R.drawable.ic_pref_m3u8_purify,
                        checked = state.m3u8Purify,
                        onCheckedChange = { vm.put(HawkConfig.M3U8_PURIFY, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_buffer_time),
                        leadingIconRes = R.drawable.ic_pref_buffer_time,
                        value = sliderBuffer.toFloat(),
                        valueText = "${sliderBuffer}x",
                        valueRange = 1f..10f,
                        steps = 8,
                        onValueChange = { sliderBuffer = (it - 1).roundToInt() + 1 },
                        onValueChangeFinished = {
                            if (sliderBuffer != state.bufferTimes) {
                                vm.put(HawkConfig.BUFFER_TIMES, sliderBuffer)
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_search_threads),
                        subtitle = stringResource(R.string.settings_search_threads_subtitle),
                        leadingIconRes = R.drawable.ic_pref_search_threads,
                        value = sliderThreads.toFloat(),
                        valueText = "$sliderThreads",
                        valueRange = 16f..64f,
                        steps = 2,
                        onValueChange = { sliderThreads = ((it - 16) / 16).roundToInt() * 16 + 16 },
                        onValueChangeFinished = {
                            if (sliderThreads != state.searchThreads) {
                                vm.put(HawkConfig.SEARCH_THREADS, sliderThreads)
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsOptionMenuRow(
                        title = "DOH",
                        subtitle = stringResource(R.string.settings_doh_subtitle),
                        leadingIconRes = R.drawable.ic_settings_doh,
                        valueText = if (state.dohIndex == 0) stringResource(R.string.common_off)
                        else OkGoHelper.dnsHttpsList.getOrNull(state.dohIndex)
                            ?: stringResource(R.string.common_off),
                        options = OkGoHelper.dnsHttpsList.mapIndexed { index, name ->
                            if (index == 0) context.getString(R.string.common_off) else name
                        },
                        selectedIndex = state.dohIndex,
                        onSelect = { idx -> vm.put(HawkConfig.DOH_URL, idx) },
                    )
                }
            }

            Spacer(Modifier.height(64.dp))
        }
    }
}

/** 语言入口:选中即写 KV(给落盘留出弹窗交互的时间),确认后立即自重启;取消回滚 */
@Composable
private fun LanguageRow() {
    val available = LanguageManager.available()
    val current = LanguageManager.current()
    val context = LocalContext.current
    var pending by remember { mutableStateOf<AppLanguage?>(null) }
    var rollback by remember { mutableStateOf(AppLanguage.System) }
    var restarting by remember { mutableStateOf(false) }
    SettingsOptionMenuRow(
        title = stringResource(R.string.settings_language),
        leadingIconRes = R.drawable.ic_pref_language,
        subtitle = stringResource(R.string.settings_language_subtitle),
        valueText = stringResource(languageLabelRes(current)),
        options = available.map { stringResource(languageLabelRes(it)) },
        selectedIndex = available.indexOf(current),
        onSelect = { idx ->
            val target = available.getOrNull(idx)
            if (target != null && target != current) {
                rollback = current
                LanguageManager.set(target)
                pending = target
            }
        },
    )
    val cancel = {
        LanguageManager.set(rollback)
        pending = null
    }
    pending?.let {
        MediaBoxAlertDialog(
            onDismissRequest = cancel,
            text = { Text(stringResource(R.string.settings_language_restart_message)) },
            dismissButton = {
                val dismissAnimated = LocalSheetDismiss.current
                TextButton(onClick = { dismissAnimated() }) { Text(stringResource(R.string.common_cancel)) }
            },
            confirmButton = {
                // 确认走"先播退场动画再执行动作":动作(pending 清空 + 置重启中)与取消(回滚语言)收尾不同,
                // 所以这里不能复用 onDismissRequest
                val dismissThen = LocalSheetDismissThen.current
                TextButton(onClick = {
                    dismissThen {
                        pending = null
                        restarting = true
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            },
        )
    }
    if (restarting) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            restartApp(context.applicationContext)
        }
    }
}

private fun languageLabelRes(lang: AppLanguage): Int = when (lang) {
    AppLanguage.System -> R.string.settings_language_system
    AppLanguage.SimplifiedChinese -> R.string.settings_language_zh_hans
    AppLanguage.English -> R.string.settings_language_en
    AppLanguage.TraditionalTW -> R.string.settings_language_zh_hant_tw
    AppLanguage.TraditionalHK -> R.string.settings_language_zh_hant_hk
}
