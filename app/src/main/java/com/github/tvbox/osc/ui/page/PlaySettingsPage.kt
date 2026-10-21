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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.DeviceCapability
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.player.effect.anime4k.Anime4kTier
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
import com.github.tvbox.osc.util.SubtitleHelper
import com.github.tvbox.osc.util.SubtitleSources
import android.widget.Toast
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.MusicSettings
import com.github.tvbox.osc.util.PlayerHelper
import kotlin.math.roundToInt
import xyz.doikki.videoplayer.player.VideoView

// KV 持久化值(exo_decode),不能翻;显示走 player_decode_* 资源
private const val DecodeHard = "硬解码" // i18n: keep
private const val DecodeSoft = "软解码" // i18n: keep

/** 分组内卡片圆角位置:首/中/尾/单张,由"总条数 + 当前下标"决定 */
private fun cardPosition(index: Int, total: Int): SettingsCardPosition = when {
    total <= 1 -> SettingsCardPosition.SINGLE
    index == 0 -> SettingsCardPosition.FIRST
    index == total - 1 -> SettingsCardPosition.LAST
    else -> SettingsCardPosition.MIDDLE
}

@Composable
fun PlaySettingsScreen(onNavigateBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val state by vm.state
    val context = LocalContext.current
    var showPrewarmWarning by remember { mutableStateOf(false) }
    var sliderCacheSize by remember(state.exoCacheSizeMb) { mutableStateOf(state.exoCacheSizeMb) }
    // 字幕:总开关 + 自定义源,都直接读写 KV,不走 SettingsState(不必整页 refresh)。
    // 内置源(Assrt/SubtitleCat/迅雷字幕)2026-10-16 起**不在设置里出现** —— 见 SubtitleSources 的说明。
    var subtitleEnabled by remember { mutableStateOf(SubtitleHelper.isEnabled()) }
    var customSources by remember { mutableStateOf(SubtitleSources.custom()) }
    var customSourceDialog by remember { mutableStateOf(false) }
    // 画质策略四档 + 画质上限:直接读写 KV,不走 SettingsState(同字幕区块的"不必整页 refresh"约定)。
    // 读 **stored()** 而不是 current() —— 界面要显示用户选的那一档,不是被旧开关迁移后的生效档。
    var qualityMode by remember { mutableStateOf(DeviceCapability.QualityMode.stored()) }
    var qualityCeiling by remember { mutableStateOf(KV.get(HawkConfig.VIDEO_QUALITY_CEILING, 0)) }

    val listState = rememberScrollState()
    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.settings_play),
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

            SettingsGroup(title = stringResource(R.string.settings_group_play_picture)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    val scales = listOf(
                        VideoView.SCREEN_SCALE_DEFAULT to stringResource(R.string.common_default),
                        VideoView.SCREEN_SCALE_16_9 to "16:9",
                        VideoView.SCREEN_SCALE_4_3 to "4:3",
                        VideoView.SCREEN_SCALE_MATCH_PARENT to stringResource(R.string.player_scale_fill),
                        VideoView.SCREEN_SCALE_ORIGINAL to stringResource(R.string.player_scale_origin),
                        VideoView.SCREEN_SCALE_CENTER_CROP to stringResource(R.string.player_scale_crop),
                    )
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_scale),
                        leadingIconRes = R.drawable.ic_play_scale,
                        valueText = PlayerHelper.getScaleName(state.playScale),
                        options = scales.map { it.second },
                        selectedIndex = scales.indexOfFirst { it.first == state.playScale },
                        onSelect = { idx -> vm.put(HawkConfig.PLAY_SCALE, scales[idx].first) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    // 画质策略:一条轴四档(2026-10-14 把独立的「流量节省」开关并进来)。
                    // 档位只回答"为画质愿意付多少代价"—— 起播前等不等首帧 + 起播后要不要自动升档;
                    // 降档/故障换线/换源链四档一致,这里只改 KV,无其它副作用。
                    //
                    // 写档位时顺手把旧的 traffic_saver 清成 false:那是上一版独立开关的残留,
                    // 不清的话 DeviceCapability.resolveMode 的迁移会把它一直按回「省流画质」。
                    //
                    // ⚠️ 说明文案放 **subtitle**,不能放 valueText —— SettingsRow 是
                    // `Column(weight(1f)) + RowValue`,无行数限制的长 valueText 会吃满宽度,
                    // 把标题列挤成每字一行的竖排(真机 v1.0.43 回归,已修)。
                    val mode = qualityMode
                    // 三档就是枚举本身,顺序即"为画质付的代价从高到低",不需要额外的显示表。
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_video_quality_mode),
                        subtitle = stringResource(R.string.settings_video_quality_mode_subtitle),
                        valueText = stringResource(qualityModeLabel(mode)),
                        options = DeviceCapability.QualityMode.entries.map { stringResource(qualityModeLabel(it)) },
                        selectedIndex = mode.ordinal,
                        onSelect = { idx ->
                            val picked = DeviceCapability.QualityMode.values()[idx]
                            qualityMode = picked
                            // 旧的 traffic_saver 是上一版独立开关的残留:不清成 false,
                            // resolveMode 的迁移会把它一直按回「省流画质」
                            vm.putAll(
                                HawkConfig.VIDEO_QUALITY_MODE to picked.ordinal,
                                HawkConfig.TRAFFIC_SAVER to false,
                            )
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    // 画质上限:高于这个清晰度的线路不参与选择、也不会自动升上去。
                    // 默认跟随设备(电视 2160 / 手机 1080);手机接电视/投屏想上 4K 就在这里放开。
                    // 实测播不动时学到的更低上限始终优先(见 DeviceCapability.capHeight)。
                    val ceilings = listOf(0, 1080, 2160)
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_video_quality_ceiling),
                        subtitle = stringResource(R.string.settings_video_quality_ceiling_subtitle),
                        valueText = stringResource(ceilingLabel(qualityCeiling)),
                        options = ceilings.map { stringResource(ceilingLabel(it)) },
                        selectedIndex = ceilings.indexOf(qualityCeiling).coerceAtLeast(0),
                        onSelect = { idx ->
                            qualityCeiling = ceilings[idx]
                            vm.put(HawkConfig.VIDEO_QUALITY_CEILING, ceilings[idx])
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    val tiers = Anime4kTier.entries
                    val labels = tiers.map { stringResource(it.labelRes) }
                    val selected = tiers.indexOf(state.anime4kTier).coerceAtLeast(0)
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_anime4k),
                        leadingIconRes = R.drawable.ic_play_anime4k,
                        subtitle = stringResource(R.string.settings_play_anime4k_subtitle),
                        valueText = labels[selected],
                        enabled = state.playType == 2,
                        options = labels,
                        selectedIndex = selected,
                        onSelect = { idx -> vm.put(HawkConfig.ANIME4K_TIER, tiers[idx].name) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_subtitle)) {
                SettingsCard(SettingsCardPosition.SINGLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_subtitle_enabled),
                        subtitle = stringResource(R.string.settings_subtitle_enabled_subtitle),
                        leadingIconRes = R.drawable.player_ic_menu_subtitle,
                        checked = subtitleEnabled,
                        onCheckedChange = {
                            subtitleEnabled = it
                            SubtitleHelper.setEnabled(it)
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            SettingsGroup(title = stringResource(R.string.settings_subtitle_sources)) {
                // 只剩一个真意图:接入用户自己的字幕源。内置的三个源不再摆出来当选项 ——
                // "这个站今天能不能连上、要不要用它"不是用户能判断的事,我们比他们更早知道结果;
                // 它们现在恒参与搜索(见 SubtitleSources.isEnabled)。
                val total = customSources.size + 1
                customSources.forEachIndexed { index, item ->
                    SettingsCard(cardPosition(index, total)) {
                        SettingsRow(
                            title = item.name,
                            subtitle = item.urlTemplate,
                            valueText = stringResource(R.string.settings_subtitle_custom_remove),
                            onClick = {
                                SubtitleSources.removeCustom(item.name)
                                customSources = SubtitleSources.custom()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_subtitle_custom_removed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    }
                }
                SettingsCard(cardPosition(total - 1, total)) {
                    SettingsRow(
                        title = stringResource(R.string.settings_subtitle_custom_add),
                        subtitle = stringResource(R.string.settings_subtitle_custom_subtitle),
                        leadingIconRes = R.drawable.ic_edit,
                        onClick = { customSourceDialog = true },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_play_behavior)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_play_kernel_prewarm),
                        leadingIconRes = R.drawable.ic_play_prewarm,
                        subtitle = stringResource(R.string.settings_play_kernel_prewarm_subtitle),
                        checked = state.kernelPrewarm,
                        onCheckedChange = { checked ->
                            if (checked) {
                                showPrewarmWarning = true
                            } else {
                                vm.put(HawkConfig.KERNEL_PREWARM, false)
                                PlaybackService.onPrewarmPreferenceChanged(context, false)
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_play_tunnel),
                        leadingIconRes = R.drawable.ic_play_tunnel,
                        checked = state.playTunnel,
                        onCheckedChange = { checked ->
                            if (checked && state.playRender != 1) vm.put(HawkConfig.PLAY_RENDER, 1)
                            vm.put(HawkConfig.PLAY_TUNNEL, checked)
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_play_prefer_aac),
                        leadingIconRes = R.drawable.ic_play_aac,
                        checked = state.preferAac,
                        onCheckedChange = { vm.put(HawkConfig.PLAY_PREFER_AAC, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_music_page),
                        leadingIconRes = R.drawable.ic_music_page,
                        subtitle = stringResource(R.string.settings_music_page_subtitle),
                        checked = state.musicPlayerPage,
                        onCheckedChange = {
                            MusicSettings.setAutoOpenPage(it)
                            vm.refresh()
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_preload_cache)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    // 「下一集预载」和「预载时长」合成一把:开关配时长 = 两把旋钮讲同一件事,
                    // 而且关了时长滑块还能拖。档位只有"提前多少秒"这一个看得见的差别。
                    val preloadSeconds = listOf(20, 60)
                    val preloadOptions = List(1) { stringResource(R.string.common_off) } + preloadSeconds.map {
                        stringResource(R.string.preload_next_on_at, it)
                    }
                    val preloadSelected = if (!state.preloadNextEpisode) 0
                    else nearestTier(preloadSeconds, state.preloadDuration) + 1
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.preload_next_episode),
                        subtitle = stringResource(R.string.preload_next_episode_subtitle),
                        leadingIconRes = R.drawable.ic_preload_next,
                        valueText = preloadOptions[preloadSelected.coerceIn(0, preloadOptions.lastIndex)],
                        options = preloadOptions,
                        selectedIndex = preloadSelected,
                        onSelect = { idx ->
                            if (idx == 0) {
                                vm.put(HawkConfig.PRELOAD_NEXT_EPISODE, false)
                            } else {
                                vm.putAll(
                                    HawkConfig.PRELOAD_NEXT_EPISODE to true,
                                    HawkConfig.PRELOAD_DURATION to preloadSeconds[idx - 1],
                                )
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.preload_play_cache),
                        leadingIconRes = R.drawable.ic_play_cache,
                        subtitle = stringResource(R.string.preload_play_cache_subtitle),
                        checked = state.playCache,
                        onCheckedChange = { vm.put(HawkConfig.PLAY_CACHE, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    // 容量是给"边播边缓存"用的:那个开关关着时拖它毫无作用,还说要重启才能生效。
                    SettingsSliderRow(
                        title = stringResource(R.string.preload_cache_size),
                        leadingIconRes = R.drawable.ic_cache_size,
                        enabled = state.playCache,
                        value = sliderCacheSize.toFloat(),
                        valueText = if (sliderCacheSize >= 1024) "%.1fGB".format(sliderCacheSize / 1024f) else "${sliderCacheSize}MB",
                        valueRange = 128f..4096f,
                        steps = 30,
                        onValueChange = { sliderCacheSize = ((it - 128) / 128).roundToInt() * 128 + 128 },
                        onValueChangeFinished = {
                            if (sliderCacheSize != state.exoCacheSizeMb) {
                                vm.put(HawkConfig.EXO_CACHE_SIZE_MB, sliderCacheSize)
                                // 容量是在 App 启动时注入共享 SimpleCache 的(首次 getSharedCache 之前),
                                // 运行中改不了 —— 不说的话用户会以为这个滑块没作用。
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_cache_size_restart),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(64.dp))
        }
    }

    if (customSourceDialog) {
        // 约定:名称|URL模板,URL 里必须带 {kw},否则搜不出东西
        TextEditDialog(
            title = stringResource(R.string.settings_subtitle_custom_add),
            initialText = "",
            onDismiss = { customSourceDialog = false },
            onConfirm = { text ->
                customSourceDialog = false
                val parts = text.split("|", limit = 2)
                val name = parts.getOrNull(0)?.trim().orEmpty()
                val url = parts.getOrNull(1)?.trim().orEmpty()
                if (url.isEmpty() || !url.contains("{kw}")) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_subtitle_custom_invalid),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    SubtitleSources.addCustom(name, url)
                    customSources = SubtitleSources.custom()
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_subtitle_custom_added),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }

    if (showPrewarmWarning) {
        MediaBoxAlertDialog(
            onDismissRequest = { showPrewarmWarning = false },
            title = { Text(stringResource(R.string.dialog_kernel_prewarm_title)) },
            text = { Text(stringResource(R.string.dialog_kernel_prewarm_message)) },
            dismissButton = {
                val dismiss = LocalSheetDismiss.current
                TextButton(onClick = { dismiss() }) {
                    Text(stringResource(R.string.dialog_kernel_prewarm_cancel))
                }
            },
            confirmButton = {
                val dismissThen = LocalSheetDismissThen.current
                TextButton(onClick = {
                    dismissThen {
                        vm.put(HawkConfig.KERNEL_PREWARM, true)
                        PlaybackService.onPrewarmPreferenceChanged(context, true)
                        showPrewarmWarning = false
                    }
                }) {
                    Text(stringResource(R.string.dialog_kernel_prewarm_confirm))
                }
            },
        )
    }

}

/**
 * 画质策略三档的档名,代价从高到低:
 * 画质优先(等首帧、一次到位)→ 自动(不等、后台扫完自动升档)→ 省流(不等、只填记忆、绝不换线)。
 */
private fun qualityModeLabel(mode: DeviceCapability.QualityMode): Int = when (mode) {
    DeviceCapability.QualityMode.QUALITY_FIRST -> R.string.video_quality_quality_first
    DeviceCapability.QualityMode.AUTO -> R.string.video_quality_auto
    DeviceCapability.QualityMode.SPEED_FIRST -> R.string.video_quality_speed_first
}

/** 画质上限的选项名:0 = 跟随设备默认(电视 2160 / 手机 1080)。 */
private fun ceilingLabel(heightPx: Int): Int = when (heightPx) {
    1080 -> R.string.video_quality_ceiling_1080
    2160 -> R.string.video_quality_ceiling_4k
    else -> R.string.video_quality_ceiling_device
}
