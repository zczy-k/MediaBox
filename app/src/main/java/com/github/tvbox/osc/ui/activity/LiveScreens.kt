@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.github.tvbox.osc.util.LOG
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.LiveChannelGroup
import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.ui.components.MediaBoxAlertDialog
import com.github.tvbox.osc.ui.components.MediaBoxBottomSheet
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.LocalSheetDismissThen
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsOptionRow
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.theme.cardContainer
import xyz.doikki.videoplayer.player.VideoView
import java.util.ArrayList
import java.util.Date
import kotlin.math.max

@Composable
internal fun LiveScreen(activity: LivePlayActivity) {
    val background = if (activity.isFullBox()) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    ) {
        when (activity.pageState) {
            PageState.LOADING -> LoadStateBox(
                state = LoadState.Loading,
                emptyText = "",
                errorText = "",
                retryText = "",
                modifier = Modifier.fillMaxSize(),
                loadingContent = { ContainedLoadingIndicator(Modifier.size(64.dp)) },
            )

            PageState.EMPTY -> LoadStateBox(
                state = LoadState.Error(stringResource(R.string.live_empty_channels)),
                emptyText = "",
                errorText = stringResource(R.string.live_empty_channels),
                retryText = stringResource(R.string.common_retry),
                modifier = Modifier.fillMaxSize(),
                onRetry = { activity.loadLiveConfigOnEnter() },
            )

            PageState.READY -> LiveReadyContent(activity)
        }
        if (activity.epgSheetVisible) EpgSheet(activity)
        if (activity.settingsSheetVisible) SettingsSheet(activity)
        activity.passwordDialogTarget?.let {
            LivePasswordDialog(
                onConfirm = { activity.onPasswordConfirmed(it) },
                onDismiss = { activity.passwordDialogTarget = null },
            )
        }
    }
}

@Composable
private fun LivePasswordDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    MediaBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.live_password_title)) },
        text = {
            val dismissThen = LocalSheetDismissThen.current
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = { Text(stringResource(R.string.live_password_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (password.isNotBlank()) dismissThen { onConfirm(password.trim()) }
                    },
                ),
            )
        },
        confirmButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(
                enabled = password.isNotBlank(),
                onClick = { dismissThen { onConfirm(password.trim()) } },
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            val dismissAnimated = LocalSheetDismiss.current
            TextButton(onClick = { dismissAnimated() }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun LiveReadyContent(activity: LivePlayActivity) {
    val configuration = LocalConfiguration.current
    val shortEdge = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val longEdge = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val previewHeight = (shortEdge * 9f / 16f)
        .coerceAtLeast(150.dp)
        .coerceAtMost(maxOf(150.dp, longEdge / 2))
    Column(modifier = Modifier.fillMaxSize()) {
        PlayerArea(
            activity = activity,
            modifier = if (activity.isFullBox()) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .statusBarsPadding()
                    .height(previewHeight)
            },
        )
        if (!activity.isFullBox()) {
            ChannelInfoSection(activity)
            ChannelListSection(activity, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlayerArea(activity: LivePlayActivity, modifier: Modifier) {
    val videoView = activity.mVideoView
    Box(modifier = modifier.background(Color.Black)) {
        if (videoView != null) {
            AndroidView(
                factory = { videoView },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (activity.snapshotVisible) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                activity.snapshotBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
            }
        }
        if (!activity.snapshotVisible &&
            (activity.playState == VideoView.STATE_PREPARING || activity.playState == VideoView.STATE_BUFFERING)
        ) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(40.dp), color = Color.White)
        }
        if (activity.resolutionVisible && activity.resolutionText.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                Text(
                    text = activity.resolutionText,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        activity.gestureHintText?.let { hint ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.6f),
            ) {
                Text(
                    text = hint,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        if (activity.isBackState && activity.overlayVisible) {
            TimeshiftBar(activity, Modifier.align(Alignment.BottomCenter))
        }
        if (!activity.isFullBox()) {
            PlayerCornerButtons(activity, Modifier.align(Alignment.TopEnd))
        } else if (activity.overlayVisible) {
            IconButton(
                onClick = { activity.applyFullscreen(false) },
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.live_exit_fullscreen),
                    tint = Color.White,
                )
            }
            PlayerCornerButtons(activity, Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun PlayerCornerButtons(activity: LivePlayActivity, modifier: Modifier) {
    Row(modifier = modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerCornerButton(
            icon = {
                Icon(
                    Icons.Filled.Event,
                    contentDescription = stringResource(R.string.live_epg),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = { activity.epgSheetVisible = true },
        )
        PlayerCornerButton(
            icon = {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.live_settings),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = { activity.openSettingsSheet() },
        )
    }
}

@Composable
private fun PlayerCornerButton(icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.4f),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
private fun TimeshiftBar(activity: LivePlayActivity, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { activity.onTimeshiftTogglePlay() }) {
            Icon(
                imageVector = if (activity.playState == VideoView.STATE_PAUSED) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = stringResource(R.string.player_play_pause),
                tint = Color.White,
            )
        }
        Slider(
            value = activity.tsPosition.toFloat().coerceIn(0f, max(activity.tsDuration, 1).toFloat()),
            onValueChange = { activity.onTimeshiftSeek(it) },
            valueRange = 0f..max(activity.tsDuration, 1).toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = LiveEpgParser.durationToString(activity.tsPosition) + " / " + LiveEpgParser.durationToString(activity.tsDuration),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ChannelInfoSection(activity: LivePlayActivity) {
    val info = activity.channelInfoUi
    if (info.name.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    text = info.num.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = info.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (activity.isBackState) {
                Text(text = stringResource(R.string.live_replaying), fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
            } else {
                Text(text = stringResource(R.string.live_on_air), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (info.sourceText.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = info.sourceText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = info.currentEpgTime + "  " + info.currentEpgTitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = info.nextEpgTime + "  " + info.nextEpgTitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (activity.showTimeOn || activity.showNetSpeedOn) {
            Spacer(modifier = Modifier.height(2.dp))
            val parts = ArrayList<String>()
            if (activity.showTimeOn && activity.timeText.isNotEmpty()) parts.add(activity.timeText)
            if (activity.showNetSpeedOn && activity.netSpeedText.isNotEmpty()) parts.add(activity.netSpeedText)
            Text(
                text = parts.joinToString("  "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelListSection(activity: LivePlayActivity, modifier: Modifier) {
    val listState = rememberLazyListState()
    // 分组筛选:null = 全部。声明必须在下面 LaunchedEffect 之前(它在滚定逻辑里被读)
    var groupFilter by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(activity.scrollTick, activity.channelVersion) {
        val rows = activity.buildChannelRows()
        var target = -1
        for (i in rows.indices) {
            val row = rows[i]
            if (row.channel != null &&
                row.group?.groupIndex == activity.currentChannelGroupIndex &&
                row.channel.channelIndex == activity.currentLiveChannelIndex
            ) {
                target = i
                break
            }
        }
        // 有筛选时索引与 allRows 不对应,跳过自动定位(筛选是用户主动操作,不需要滚到当前频道)
        if (target > 0 && groupFilter == null) listState.animateScrollToItem(max(0, target - 2))
    }
    val allRows = activity.buildChannelRows()
    // 分组筛选胶囊(设计稿 04:全部/按分组):36 个频道分 4 组时,逐个展开找台太慢。
    // 筛选只改"显示哪些行",不动折叠状态与选中状态 —— 组头仍可点开合。
    val groups = remember(allRows) {
        allRows.mapNotNull { it.group }.distinctBy { it.groupIndex }
    }
    val rows = if (groupFilter == null) {
        allRows
    } else {
        allRows.filter { it.group?.groupIndex == groupFilter }
    }
    LaunchedEffect(groupFilter) {
        LOG.i("echo-live-filter filter=${groupFilter ?: "all"} groups=${groups.size} rows=${rows.size}")
    }
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = navBarInset + 24.dp),
    ) {
        if (groups.size > 1) {
            item(key = "groupFilter") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    val all = listOf<Pair<Int?, String>>(null to stringResource(R.string.live_filter_all)) +
                        groups.map { it.groupIndex to (it.groupName ?: "") }
                    all.forEach { (idx, label) ->
                        val on = groupFilter == idx
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (on) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainer
                                )
                                .clickable { groupFilter = idx }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
        itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
            val channel = row.channel
            if (channel == null) {
                val group = row.group ?: return@itemsIndexed
                GroupHeaderRow(activity, group)
            } else {
                ChannelRow(activity, row, channel)
            }
        }
    }
}

@Composable
private fun GroupHeaderRow(activity: LivePlayActivity, group: LiveChannelGroup) {
    val expanded = activity.expandedGroups.contains(group.groupIndex)
    val locked = group.groupPassword.isNotEmpty() && !activity.isPasswordConfirmedForUi(group.groupIndex)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { activity.toggleChannelGroup(group.groupIndex) }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.groupName ?: "",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (locked) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = stringResource(R.string.live_need_password),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .rotate(if (expanded) 90f else 0f),
        )
    }
}

@Composable
private fun ChannelRow(
    activity: LivePlayActivity,
    row: LiveListRow,
    channel: LiveChannelItem,
) {
    val group = row.group ?: return
    val selected = group.groupIndex == activity.currentChannelGroupIndex &&
            channel.channelIndex == activity.currentLiveChannelIndex
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { activity.selectChannel(group.groupIndex, row.channelPos) }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 频道号:30dp 圆角方图标底,与设置页 si / 详情集数格同一语言
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainer
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = channel.channelNum.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(11.dp))
        Text(
            text = channel.channelName ?: "",
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 状态:当前频道显示 LIVE 点,与播放页顶栏同一语义
        if (selected) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EpgSheet(activity: LivePlayActivity) {
    activity.epgVersion
    val channelNameStr = activity.channelName?.channelName ?: ""
    MediaBoxBottomSheet(
        onDismissRequest = { activity.epgSheetVisible = false },
        title = if (channelNameStr.isEmpty()) {
            stringResource(R.string.live_epg)
        } else {
            stringResource(R.string.live_epg_of, channelNameStr)
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        isScrollable = false,
    ) {
        val dismissAnimated = LocalSheetDismiss.current
        val epgList = activity.epgdata
        if (epgList.isEmpty()) {
            Text(
                text = stringResource(R.string.live_epg_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            )
            return@MediaBoxBottomSheet
        }
        val canCatchup = activity.canCurrentChannelCatchup()
        val now = Date()
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(epgList) { index, epg ->
                val isNow = epg.startdateTime != null && epg.enddateTime != null &&
                        !now.before(epg.startdateTime) && !now.after(epg.enddateTime)
                val clickable = epg.startdateTime != null && !now.before(epg.startdateTime) &&
                        (canCatchup || (epg.enddateTime != null && !now.after(epg.enddateTime)))
                val selected = index == activity.currentLiveLookBackIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = clickable) {
                            if (activity.onEpgRowClicked(index)) dismissAnimated()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = epg.start + "-" + epg.end,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = epg.title,
                        fontSize = 14.sp,
                        fontWeight = if (selected || isNow) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            selected -> MaterialTheme.colorScheme.primary
                            isNow -> MaterialTheme.colorScheme.onSurface
                            clickable -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        selected -> Text(
                            text = stringResource(R.string.live_replaying),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        isNow -> Text(
                            text = stringResource(R.string.live_now_playing),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(activity: LivePlayActivity) {
    activity.settingsVersion
    val groups = activity.visibleSettingGroups()
    MediaBoxBottomSheet(
        onDismissRequest = { activity.settingsSheetVisible = false },
        title = stringResource(R.string.live_settings),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        isScrollable = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            groups.forEach { group ->
                val items = group.liveSettingItems ?: return@forEach
                item(key = "sg" + group.groupIndex) {
                    SettingsGroup(
                        // 仓列表不允许单独删除,故"长按可删除"只在配置历史模式显示
                        title = if (group.groupIndex == 6 && !activity.isLiveApiLineMode()) {
                            stringResource(R.string.live_group_long_press_delete, group.groupName.orEmpty())
                        } else {
                            group.groupName
                        },
                    ) {
                        items.forEachIndexed { index, item ->
                            val position = when {
                                items.size == 1 -> SettingsCardPosition.SINGLE
                                index == 0 -> SettingsCardPosition.FIRST
                                index == items.size - 1 -> SettingsCardPosition.LAST
                                else -> SettingsCardPosition.MIDDLE
                            }
                            SettingsCard(
                                position = position,
                                color = MaterialTheme.colorScheme.surfaceBright,
                            ) {
                                if (group.groupIndex == 4) {
                                    SettingsSwitchRow(
                                        title = item.itemName,
                                        checked = activity.settingChecked(item.itemIndex),
                                        onCheckedChange = { activity.clickSettingItem(group.groupIndex, item.itemIndex) },
                                    )
                                } else {
                                    SettingsOptionRow(
                                        title = item.itemName,
                                        selected = activity.settingSelectedIndex(group.groupIndex) == item.itemIndex,
                                        onClick = { activity.clickSettingItem(group.groupIndex, item.itemIndex) },
                                        onLongClick = if (group.groupIndex == 6 && item.itemIndex > 0) {
                                            { activity.removeLiveConfigHistory(item.itemIndex - 1) }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
