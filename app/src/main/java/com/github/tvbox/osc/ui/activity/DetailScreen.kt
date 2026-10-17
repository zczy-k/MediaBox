@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.activity

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.player.ui.playerDim
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
                is PlaybackCommand.StopForContentSwitch -> container.stopForContentSwitch(command.incomingKey)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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

            // 有内容才展开正文;没内容(找源中/确实没有)由下面的整页海报态接管
            if (!fullBox && pageState is DetailViewModel.PageState.Ready) {
                DetailContent(activity, vm, revision, onCardLongClick = { vodMenu.show(it) })
            }
        }

        // **整页海报态**(2026-10-17 用户真机截图指出的版式问题):
        // 片子还没搜出来时,上面那块 16:9 播放器是空的,占着首屏最贵的位置;
        // 海报和搜索状态被挤在下面一小条里。现在无内容时整屏只讲这一件事。
        //
        // 用**覆盖层**而不是把播放器高度收成 0:播放器 View 一直活着、尺寸不变,
        // 换片时"同片保留"那条路径(重进同一部片的详情页)不会因表面被销毁而重开解码器;
        // 它只是被不透明地盖住。转 Ready 时淡出,不重组播放器。
        AnimatedVisibility(
            visible = !fullBox && pageState !is DetailViewModel.PageState.Ready,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
            NoContentScreen(vm, pageState, revision, header, shortEdge, configuration.screenHeightDp.dp)
        }
    }

    // 侧滑只在"横屏全屏"这一种形态:大屏设备点全屏时系统可能不旋转(忽略应用的方向限制),
    // 那时窗口仍是竖屏,面板必须保持贴底
    EpisodeSheet(vm, revision, slideFromEnd = fullBox && isLandscapeNow)
    VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }
}

/**
 * **整页海报态**:详情页还没有可播内容时(找源中 / 确实没找到),整屏只讲这一件事 ——
 * 大海报 + 片名 + 搜索状态,垂直居中;换源板块仍然常驻在下方。
 *
 * <p>判据与换源那一行旁边的小字**共用** [sourceStatusText]:同一场搜索不能在两处说两种话。
 */
@Composable
private fun NoContentScreen(
    vm: DetailViewModel,
    state: DetailViewModel.PageState,
    revision: Int,
    header: DetailViewModel.DetailHeader,
    shortEdge: Dp,
    screenHeight: Dp,
) {
    val searching by vm.sourcesSearching.collectAsStateWithLifecycle()
    val progress by vm.searchProgress.collectAsStateWithLifecycle()
    val found = vm.totalCandidateCount()
    val empty = state as? DetailViewModel.PageState.Empty
    val emptyMsg = empty?.msg
    // "收口"= 这一场搜索不再在跑、页面也确实没内容:这时候才给「再搜一次」入口
    val settled = empty != null && !searching
    // 海报:宽约屏宽 46%、竖版 3:4;再用可用高度封一道,横屏小竖向空间时不会撑出屏幕
    val posterW = minOf(shortEdge * 0.46f, screenHeight * 0.42f * 3f / 4f).coerceAtLeast(96.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // 上下两道安全区都要留:真机 v1.1.63 第一版只挡了状态栏,换源板块卡片被手势条切掉下沿
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (header.picture.isNotEmpty()) {
                VodPoster(
                    name = header.name,
                    pic = header.picture,
                    modifier = Modifier
                        .width(posterW)
                        .height(posterW * 4f / 3f)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
            if (header.name.isNotEmpty()) {
                Text(
                    text = header.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(14.dp))
            }
            if (!settled) {
                ContainedLoadingIndicator()
                Spacer(modifier = Modifier.height(14.dp))
            }
            Text(
                text = if (settled) {
                    emptyMsg ?: stringResource(R.string.detail_source_none_found)
                } else {
                    sourceStatusText(
                        searchedOnce = true,
                        searching = true,
                        asked = progress?.done ?: 0,
                        found = found,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (settled) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { vm.retry() }) {
                    Text(stringResource(R.string.detail_search_again))
                }
            }
        }
        // 换源板块常驻:等待期间照常显示已发现的源。
        // statusInline 只在**收口后**打开:找源中时上面那行已经说过"正在找源 · 已问过 N 个",
        // 板块再说一遍就是两处同文(真机 v1.1.47 拍到过);收口后上面那行说的是失败原因,
        // 板块这行补的是"问过多少 / 找到几个"的证据,两句不重复。
        SourceSection(vm, revision = revision, statusInline = settled)
    }
}
