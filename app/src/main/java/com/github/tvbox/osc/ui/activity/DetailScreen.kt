@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.activity

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.player.ui.playerDim
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.VodCardMenu
import com.github.tvbox.osc.ui.components.VodPoster
import com.github.tvbox.osc.ui.components.rememberVodCardMenuState
import kotlinx.coroutines.delay
import com.github.tvbox.osc.ui.page.jumpToSearch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(activity: DetailActivity, vm: DetailViewModel) {
    val menuContext = LocalContext.current
    val pageState by vm.pageState.collectAsStateWithLifecycle()
    val header by vm.header.collectAsStateWithLifecycle()
    val full by vm.fullScreen.collectAsStateWithLifecycle()
    val rotating by vm.rotating.collectAsStateWithLifecycle()
    val revision by vm.revision.collectAsStateWithLifecycle()
    val playSignal by vm.playSignal.collectAsStateWithLifecycle()
    val toast by vm.toastEvent.collectAsStateWithLifecycle()
    val finish by vm.finishEvent.collectAsStateWithLifecycle()
    val searchProgress by vm.searchProgress.collectAsStateWithLifecycle()
    val vodMenu = rememberVodCardMenuState()

    val configuration = LocalConfiguration.current
    val isLandscapeNow = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullBox = if (rotating) isLandscapeNow else full
    val shortEdge = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val longEdge = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val previewBoxHeight = (shortEdge * 9f / 16f)
        .coerceAtLeast(150.dp)
        .coerceAtMost(maxOf(150.dp, longEdge / 2))

    val container = remember { activity.ensurePlayContainer().also { it.setOnQualitySelectedListener(vm::onQualitySelectionAccepted) } }

    LaunchedEffect(container, vm) {
        vm.playbackCommands.collect { command ->
            when (command) {
                is PlaybackCommand.StopForContentSwitch -> container.stopForContentSwitch()
                is PlaybackCommand.StopForSourceSwitch -> container.stopForSourceSwitch(command.tip)
                is PlaybackCommand.ClearSourceSwitchTip -> container.clearSourceSwitchTip()
                is PlaybackCommand.SetEpisodeSheetOpen -> container.setEpisodeSheetOpen(command.open)
                is PlaybackCommand.SelectQuality -> container.selectQuality(command.position)
            }
        }
    }

    LaunchedEffect(container, playSignal) {
        if (playSignal > 0) activity.playCurrent()
    }

    var musicWatch by remember { mutableStateOf(false) }
    var musicArmed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { musicWatch = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { musicWatch = false }
    LaunchedEffect(playSignal) {
        if (playSignal > 0) musicArmed = true
    }
    LaunchedEffect(musicWatch, musicArmed) {
        if (!musicWatch || !musicArmed) return@LaunchedEffect
        // 连续命中才交接(300ms × 3 ≈ 1s):首帧前后几百毫秒内视频轨可能尚未上报,
        // 单次命中就会把普通影视误判成音乐片并交接给音乐页,表现是只剩声音、没有画面。
        val stableHitsRequired = 3
        var hits = 0
        while (true) {
            delay(300)
            if (!activity.musicPlaybackDetected()) {
                hits = 0
                continue
            }
            if (++hits < stableHitsRequired) continue
            if (!activity.handOffToMusicPlayer()) {
                hits = 0
                continue
            }
            musicArmed = false
            return@LaunchedEffect
        }
    }

    LaunchedEffect(full) {
        activity.applyFullscreen(full)
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(activity, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    LaunchedEffect(finish) {
        if (finish) {
            vm.consumeFinish()
            activity.finish()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            modifier = if (fullBox) {
                Modifier.fillMaxSize().background(Color.Black)
            } else {
                Modifier.fillMaxWidth()
                    .background(Color.Black)
                    .statusBarsPadding()
                    .height(previewBoxHeight)
                    .background(Color.Black)
            },
        ) {
            AndroidView(
                factory = { container },
                modifier = Modifier.fillMaxSize(),
            )
            if (pageState is DetailViewModel.PageState.Loading && !fullBox) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        indicatorColor = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
            // 提示层由控制器 Compose 层绘制(PlayerLayers.PlayerTipLayer):写在这一层会盖住顶栏/底栏
            if (!fullBox && pageState is DetailViewModel.PageState.Ready) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_expand),
                    contentDescription = stringResource(R.string.detail_fullscreen_play),
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 16.dp,
                            // vs_30 太小时该式子会变负(Compose 直接抛 IllegalArgumentException)，钳到 0
                            bottom = (16.dp + playerDim(R.dimen.vs_30) / 2 - 20.dp).coerceAtLeast(0.dp),
                        )
                        .size(40.dp)
                        .clickable { vm.onFullScreenToggleRequested(true, activity.playbackFacts()) }
                        .padding(9.dp),
                )
            }
        }

        if (!fullBox) {
            when (val state = pageState) {
                is DetailViewModel.PageState.Loading -> {
                    // 点击卡片时 Intent 已带片名/海报,而取详情要等网络:先渲染已知信息,
                    // 等待期间就不是一片空白(纯感知提速,不改变任何取数时机)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (header.picture.isNotEmpty()) {
                            VodPoster(
                                name = header.name,
                                pic = header.picture,
                                modifier = Modifier
                                    .width(120.dp)
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        if (header.name.isNotEmpty()) {
                            Text(
                                text = header.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        ContainedLoadingIndicator()
                        // S6(2026-10-09):聚合搜索进行中显示实况进度 —— 修复前无候选时
                        // 用户对着纯转圈最长 ~59s,分不清"在搜"还是"死了"。null = 搜索不在跑,不显示
                        searchProgress?.let { p ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (p.candidates > 0) {
                                    stringResource(
                                        R.string.detail_search_progress_found,
                                        p.done, p.total, p.candidates,
                                    )
                                } else {
                                    stringResource(R.string.detail_search_progress, p.done, p.total)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                is DetailViewModel.PageState.Empty -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LoadStateBox(
                            state = LoadState.Empty,
                            emptyText = state.msg ?: stringResource(R.string.detail_empty_source),
                            errorText = "",
                            retryText = "",
                            emptyIconRes = R.drawable.icon_error,
                            modifier = Modifier.weight(1f),
                        )
                        SourceSection(vm, revision = revision)
                    }
                }

                is DetailViewModel.PageState.Ready -> {
                    DetailContent(activity, vm, revision, onCardLongClick = { vodMenu.show(it) })
                }
            }
        }
    }

    // 侧滑只在"横屏全屏"这一种形态:大屏设备点全屏时系统可能不旋转(忽略应用的方向限制),
    // 那时窗口仍是竖屏,面板必须保持贴底
    EpisodeSheet(vm, revision, slideFromEnd = fullBox && isLandscapeNow)
    VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }
}
