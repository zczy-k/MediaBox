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
import com.github.tvbox.osc.util.SubtitleHelper
import com.github.tvbox.osc.util.SubtitleSources
import android.widget.Toast
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.util.HawkConfig
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
    var sliderPreloadDuration by remember(state.preloadDuration) { mutableStateOf(state.preloadDuration) }
    var sliderCacheSize by remember(state.exoCacheSizeMb) { mutableStateOf(state.exoCacheSizeMb) }
    // 字幕:总开关 + 内置源开关 + 自定义源,都直接读写 KV,不走 SettingsState(不必整页 refresh)
    var subtitleEnabled by remember { mutableStateOf(SubtitleHelper.isEnabled()) }
    var sourceEnabled by remember {
        mutableStateOf(SubtitleSources.builtIns.associate { it.key to SubtitleSources.isEnabled(it.key) })
    }
    var customSources by remember { mutableStateOf(SubtitleSources.custom()) }
    var customSourceDialog by remember { mutableStateOf(false) }
    // 画质选项:直接读写 KV,不走 SettingsState(同字幕区块的"不必整页 refresh"约定)。
    // ⚠️ 这里存的是**用户的选择**,不归一化 —— 流量节省关闭后要能一键恢复原选择;
    // 实际生效档位由 DeviceCapability.effectiveMode() 决定(口径见《选线机制设计》附录 F)。
    var qualityMode by remember { mutableStateOf(DeviceCapability.QualityMode.current()) }
    // 流量节省(默认开启):它是「画质选项」的前置条件 —— 未关闭时「画质优先」不可选
    var trafficSaver by remember { mutableStateOf(DeviceCapability.trafficSaverOn()) }
    var showTrafficSaverConfirm by remember { mutableStateOf(false) }

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
                    val playerTypes = PlayerHelper.getExistPlayerTypes().sortedDescending()
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_kernel),
                        leadingIconRes = R.drawable.ic_play_kernel,
                        valueText = PlayerHelper.getPlayerName(state.playType),
                        options = playerTypes.map { PlayerHelper.getPlayerName(it) },
                        selectedIndex = playerTypes.indexOf(state.playType).coerceAtLeast(0),
                        onSelect = { idx -> vm.put(HawkConfig.PLAY_TYPE, playerTypes[idx]) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_render),
                        leadingIconRes = R.drawable.ic_play_render,
                        valueText = PlayerHelper.getRenderName(state.playRender),
                        options = listOf("SurfaceView", "TextureView"),
                        selectedIndex = 1 - state.playRender,
                        onSelect = { idx ->
                            val render = 1 - idx
                            if (render == 0 && state.playTunnel) vm.put(HawkConfig.PLAY_TUNNEL, false)
                            vm.put(HawkConfig.PLAY_RENDER, render)
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
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
                    // 解码方式:软解 = 系统软件解码器 c2.android.*(仅视频渲染器);
                    // 内核选外部播放器时该设置不生效,行置灰
                    val codec = state.exoDecode
                    val decodeLabels = listOf(
                        stringResource(R.string.player_decode_hard),
                        stringResource(R.string.player_decode_soft),
                    )
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_decode),
                        leadingIconRes = R.drawable.ic_play_decode,
                        valueText = when (codec) {
                            DecodeSoft -> decodeLabels[1]
                            DecodeHard -> decodeLabels[0]
                            else -> codec
                        },
                        enabled = state.playType == 2,
                        options = decodeLabels,
                        selectedIndex = if (codec == DecodeSoft) 1 else 0,
                        onSelect = { idx -> vm.put(HawkConfig.EXO_DECODE, if (idx == 1) DecodeSoft else DecodeHard) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    // 流量节省:默认开启。它是「画质选项」的**前置条件**(未关闭则画质优先不可选),
                    // 所以刻意排在它上一行(《选线机制设计》附录 F)。
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_traffic_saver),
                        subtitle = stringResource(R.string.settings_traffic_saver_subtitle),
                        checked = trafficSaver,
                        onCheckedChange = { checked ->
                            if (checked) {
                                // 恢复节省:无需确认,立即生效
                                trafficSaver = true
                                DeviceCapability.setTrafficSaver(true)
                            } else {
                                // 关闭:必须二次确认(会明显增加流量),确认前不改 KV
                                showTrafficSaverConfirm = true
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    // 「画质选项」三档:只分化起播前的探测策略(口径见 DeviceCapability.QualityMode);
                    // 起播后的降档/换线/换源链三档一致,这里只改 KV,无其它副作用
                    val mode = qualityMode
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_video_quality_mode),
                        subtitle = stringResource(R.string.settings_video_quality_mode_subtitle),
                        valueText = if (trafficSaver && mode == DeviceCapability.QualityMode.QUALITY_FIRST) {
                            // 存量用户可能已存「画质优先」:不改写 KV(关掉开关即自动恢复),
                            // 但必须把"当前没在生效"讲清楚,否则用户会以为设置坏了
                            stringResource(R.string.video_quality_quality_first_paused)
                        } else {
                            stringResource(
                                when (mode) {
                                    DeviceCapability.QualityMode.QUALITY_FIRST -> R.string.video_quality_quality_first
                                    DeviceCapability.QualityMode.SPEED_FIRST -> R.string.video_quality_speed_first
                                    else -> R.string.video_quality_auto
                                },
                            )
                        },
                        options = listOf(
                            stringResource(R.string.video_quality_quality_first),
                            stringResource(R.string.video_quality_auto),
                            stringResource(R.string.video_quality_speed_first),
                        ),
                        selectedIndex = mode.ordinal,
                        // 流量节省开启 ⇒「画质优先」置灰(下标 0 === QUALITY_FIRST.ordinal,口径锁在 VideoQualityModeTest)
                        disabledIndices = if (trafficSaver) setOf(0) else emptySet(),
                        onDisabledSelect = {
                            Toast.makeText(
                                context,
                                context.getString(R.string.video_quality_quality_first_locked_hint),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        onSelect = { idx ->
                            val picked = DeviceCapability.QualityMode.values()[idx]
                            qualityMode = picked
                            vm.put(HawkConfig.VIDEO_QUALITY_MODE, picked.ordinal)
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
                val total = SubtitleSources.builtIns.size + customSources.size + 1
                SubtitleSources.builtIns.forEachIndexed { index, source ->
                    SettingsCard(cardPosition(index, total)) {
                        SettingsSwitchRow(
                            title = source.name,
                            checked = sourceEnabled[source.key] ?: true,
                            enabled = subtitleEnabled,
                            onCheckedChange = { checked ->
                                SubtitleSources.setEnabled(source.key, checked)
                                sourceEnabled = sourceEnabled + (source.key to checked)
                            },
                        )
                    }
                }
                customSources.forEachIndexed { index, item ->
                    SettingsCard(cardPosition(SubtitleSources.builtIns.size + index, total)) {
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
                    SettingsSwitchRow(
                        title = stringResource(R.string.preload_next_episode),
                        leadingIconRes = R.drawable.ic_preload_next,
                        subtitle = stringResource(R.string.preload_next_episode_subtitle),
                        checked = state.preloadNextEpisode,
                        onCheckedChange = { vm.put(HawkConfig.PRELOAD_NEXT_EPISODE, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = stringResource(R.string.preload_duration),
                        leadingIconRes = R.drawable.ic_preload_duration,
                        value = sliderPreloadDuration.toFloat(),
                        valueText = "${sliderPreloadDuration}s",
                        valueRange = 20f..120f,
                        steps = 9,
                        onValueChange = { sliderPreloadDuration = ((it - 20) / 10).roundToInt() * 10 + 20 },
                        onValueChangeFinished = {
                            if (sliderPreloadDuration != state.preloadDuration) {
                                vm.put(HawkConfig.PRELOAD_DURATION, sliderPreloadDuration)
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
                    SettingsSliderRow(
                        title = stringResource(R.string.preload_cache_size),
                        leadingIconRes = R.drawable.ic_cache_size,
                        value = sliderCacheSize.toFloat(),
                        valueText = if (sliderCacheSize >= 1024) "%.1fGB".format(sliderCacheSize / 1024f) else "${sliderCacheSize}MB",
                        valueRange = 128f..4096f,
                        steps = 30,
                        onValueChange = { sliderCacheSize = ((it - 128) / 128).roundToInt() * 128 + 128 },
                        onValueChangeFinished = {
                            if (sliderCacheSize != state.exoCacheSizeMb) {
                                vm.put(HawkConfig.EXO_CACHE_SIZE_MB, sliderCacheSize)
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

    if (showTrafficSaverConfirm) {
        MediaBoxAlertDialog(
            onDismissRequest = { showTrafficSaverConfirm = false },
            title = { Text(stringResource(R.string.settings_traffic_saver_off_title)) },
            text = { Text(stringResource(R.string.settings_traffic_saver_off_message)) },
            dismissButton = {
                // 取消:走 LocalSheetDismiss ⇒ 会触发 onDismissRequest,状态自然复位
                val dismiss = LocalSheetDismiss.current
                TextButton(onClick = { dismiss() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            confirmButton = {
                val dismissThen = LocalSheetDismissThen.current
                TextButton(onClick = {
                    dismissThen {
                        // 二次确认通过后才真正关闭:**不改写 VIDEO_QUALITY_MODE**,
                        // 用户原有的「画质优先」选择保留,重新打开开关即自动恢复
                        DeviceCapability.setTrafficSaver(false)
                        trafficSaver = false
                        showTrafficSaverConfirm = false
                    }
                }) {
                    Text(stringResource(R.string.settings_traffic_saver_off_confirm))
                }
            },
        )
    }
}
