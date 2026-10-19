package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.ui.components.MediaBoxBottomSheet
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.theme.filterChipColors
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun EpisodeRow(
    vm: DetailViewModel,
    info: VodInfo,
    episodes: List<VodInfo.VodSeries>,
    playIndex: Int,
    currentFlag: String?,
) {
    Column(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 16.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(20.dp))
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_episodes))
            Text(
                text = stringResource(R.string.detail_episodes),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            PillAction(
                // 图标与文案同向:都表达"点一下会切到什么" —— 正序=向上箭头,倒序=向下箭头
                iconRes = if (info.reverseSort) {
                    R.drawable.ic_episode_order_asc
                } else {
                    R.drawable.ic_episode_reverse
                },
                text = stringResource(if (info.reverseSort) R.string.detail_order_asc else R.string.detail_order_desc),
                onClick = { vm.toggleReverse() },
            )
            Spacer(Modifier.width(8.dp))
            PillAction(
                iconRes = R.drawable.ic_episode_grid_all,
                text = stringResource(R.string.common_all),
                onClick = { vm.showEpisodeSheet() },
            )
        }
        val listState = rememberLazyListState()
        var prevReverseSort by remember { mutableStateOf(info.reverseSort) }
        LaunchedEffect(playIndex, currentFlag, episodes.size, info.reverseSort) {
            if (episodes.isEmpty()) return@LaunchedEffect
            val reverseChanged = info.reverseSort != prevReverseSort
            prevReverseSort = info.reverseSort
            if (reverseChanged) {
                listState.scrollToItem(0)
            } else if (playIndex >= 0) {
                listState.scrollToItem(minOf(playIndex, episodes.size - 1))
            }
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(episodes) { index, ep ->
                FilterChip(
                    selected = index == playIndex,
                    onClick = { vm.onEpisodeClick(index) },
                    label = {
                        Text(
                            text = ep.name ?: (index + 1).toString(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = MaterialTheme.colorScheme.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PillAction(iconRes: Int, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun EpisodeSheet(vm: DetailViewModel, revision: Int, slideFromEnd: Boolean) {
    @Suppress("UNUSED_EXPRESSION") revision
    val show by vm.episodeSheet.collectAsStateWithLifecycle()
    // 面板在屏时冻结底栏自动收起（见 PlayerUiState.overlayPanelOpen）:投影随开合指令下发到播放层
    if (!show) return
    val info = vm.vodInfo ?: return
    val flags = info.seriesFlags.orEmpty()
    val currentFlag = info.playFlag
    val rawEpisodes = info.seriesMap?.get(currentFlag).orEmpty()
    // ⚠️ 弹层网格必须吃 reverseSort:之前直接渲染原始列表,页面点倒序后弹层仍是正序,
    // 同一个 App 两个选集视图排序不一致(真 bug,不是设计偏好)
    val episodes = if (info.reverseSort) rawEpisodes.asReversed() else rawEpisodes
    // 网格下标 ↔ 原始下标换算:选集/分组定位全部用**显示下标**,点击回调换回原始下标
    fun displayToRaw(index: Int): Int = if (info.reverseSort) episodes.size - 1 - index else index
    val playIndex = info.playIndex
    val playDisplayIndex = if (playIndex >= 0) episodes.indexOfFirst {
        it === rawEpisodes.getOrNull(playIndex)
    } else -1
    val lineHeights by vm.lineQualityHeights.collectAsStateWithLifecycle()

    val groupCount = when {
        episodes.size > 400 -> 120
        episodes.size > 100 -> 60
        else -> 20
    }
    val groups = if (episodes.size > groupCount) {
        val result = ArrayList<String>()
        var i = 0
        while (i < episodes.size) {
            val end = minOf(i + groupCount, episodes.size)
            result.add("${i + 1} - $end")
            i += groupCount
        }
        result
    } else {
        emptyList()
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val gridScope = rememberCoroutineScope()
    var selectedGroup by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(show, currentFlag, playIndex, info.reverseSort) {
        if (show && playDisplayIndex >= 0) {
            selectedGroup = playDisplayIndex / groupCount
            if (playDisplayIndex in episodes.indices) gridState.scrollToItem(playDisplayIndex)
        }
    }

    MediaBoxBottomSheet(
        onDismissRequest = { vm.dismissEpisodeSheet() },
        title = if (info.name.isNullOrEmpty()) {
                stringResource(R.string.detail_episodes)
            } else {
                stringResource(R.string.detail_episodes_of, info.name.orEmpty())
            },
        isScrollable = false,
        // 集数多、弹层高:压到 78%,顶部留出页面语境,不再顶到状态栏
        maxHeightFraction = 0.78f,
        // 横屏全屏播放时改成右侧滑出（竖屏详情页仍是贴底弹层）
        slideFromEnd = slideFromEnd,
    ) {
        val dismissAnimated = LocalSheetDismiss.current
        Column(modifier = Modifier.fillMaxWidth()) {
            // 线路切换入口：竖屏贴底弹层与横屏全屏侧滑面板**都显示**。
            // 侧滑面板宽约窗口 40% 出头，一行横向滚动 chips 完全放得下；
            // 早前这里用 `&& !slideFromEnd` 把横屏整条短路掉，导致「全屏里只有选集、没有线路」，
            // 而横屏全屏恰恰是看片最常待的状态 —— 手动换线在这里等于没有入口。
            if (flags.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(flags, key = { i, f -> "${i}_${f.name}" }) { index, flag ->
                        FilterChip(
                            selected = flag.name == currentFlag,
                            onClick = { vm.onFlagClick(flag.name ?: "") },
                            // 不再显示站点自报的 flag 名(常见假的"1080P/蓝光",有的还带站名/域名):
                            // 只给"线路序号 + 实测画质",没测到就只给序号 —— 宁缺勿假。
                            label = { Text(lineLabel(index, flag.name, lineHeights, vm.lineMissingEpisodes(flag.name), vm.lineHealthSuffix(flag.name))) },
                            shape = RoundedCornerShape(20.dp),
                            colors = MaterialTheme.colorScheme.filterChipColors(),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            // 工具行:左「倒序/正序」(与页面选集卡共用 vm.toggleReverse,状态天然同步)、
            // 右分组下拉(原分组行右移到这里,少占一行)。任何一侧不存在时另一侧独占。
            if (rawEpisodes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { vm.toggleReverse() },
                        label = {
                            Text(
                                stringResource(
                                    if (info.reverseSort) R.string.detail_order_asc
                                    else R.string.detail_order_desc
                                ),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        shape = RoundedCornerShape(999.dp),
                        colors = MaterialTheme.colorScheme.filterChipColors(),
                    )
                    if (groups.size > 3) {
                        FilterChip(
                            selected = true,
                            onClick = {
                                selectedGroup = (selectedGroup + 1) % groups.size
                                gridScope.launch { gridState.scrollToItem(selectedGroup * groupCount) }
                            },
                            label = {
                                Text(
                                    "第 ${selectedGroup + 1} / ${groups.size} 组",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            shape = RoundedCornerShape(999.dp),
                            colors = MaterialTheme.colorScheme.filterChipColors(),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
            }
            // 列数不再"按片名长度猜":那条规则在平板上会崩(1024dp 宽的格子挤 12sp 的字)。
            // 统一走 Fluid.columns —— 卡片目标宽随宽度缩放,任何屏幕密度感一致。
            // 侧滑面板约 45% 宽:同一公式,只是入参窄;名字超长交给 Ellipsis,不为它牺牲密度。
            val gridColumnCount = com.github.tvbox.osc.ui.theme.Fluid.columns(
                if (slideFromEnd) 420.dp else 720.dp, minColumns = if (slideFromEnd) 2 else 3,
            )
            val gridModifier = if (slideFromEnd) {
                // 全高面板:网格直接撑满标题/线路行之外的剩余高度
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            } else {
                val rowCount = if (episodes.isEmpty()) 0 else (episodes.size + gridColumnCount - 1) / gridColumnCount
                val gridContentHeight = (rowCount * 40).dp + (((rowCount - 1).coerceAtLeast(0)) * 8).dp
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = minOf(560.dp, gridContentHeight))
            }
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                state = gridState,
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(gridColumnCount),
                modifier = gridModifier,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    // 底部避开手势条/导航栏：面板底色仍铺到屏幕最底(沉浸不变),只把收尾行抬起来
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItemsIndexed(episodes) { index, ep ->
                    FilterChip(
                        selected = index == playDisplayIndex,
                        onClick = {
                            vm.onEpisodeClick(displayToRaw(index))
                            dismissAnimated()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        label = {
                            Text(
                                text = ep.name ?: (index + 1).toString(),
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                fontSize = 13.sp,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = MaterialTheme.colorScheme.filterChipColors(),
                    )
                }
            }
        }
    }
}
