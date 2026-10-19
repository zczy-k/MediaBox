package com.github.tvbox.osc.ui.page

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.ui.WindowSize
import com.github.tvbox.osc.ui.components.FilterSheet
import com.github.tvbox.osc.ui.components.HeroCarousel
import com.github.tvbox.osc.ui.components.ShimmerHost
import com.github.tvbox.osc.ui.components.SkeletonBox
import com.github.tvbox.osc.ui.components.VodCard
import com.github.tvbox.osc.ui.components.VodCardStyle
import com.github.tvbox.osc.ui.theme.Fluid
import com.github.tvbox.osc.ui.theme.MediaBoxRadius
import com.github.tvbox.osc.util.HomeSettings
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.flow.first
import java.util.LinkedHashMap

internal val HomeGridTabRowHeight = 52.dp

private val HomeTabIndicatorInset = 16.dp

private val HomeTabIndicatorHeight = 3.dp

private val HomeFilterChipSpacing = 8.dp

private val HomeFilterChipFitSlack = 2.dp

private val HomeFilterChipPadding = 14.dp

/** 超过此宽度不再等宽铺满:宽屏下会把每个 chip 拉成一大条,不如保持自然宽度左对齐 */
private val HomeFilterChipEqualWidthMaxWidth = 600.dp

private const val HomeGridSkeletonCount = 18

/** Sample a small first-page set before committing the shared automatic frame ratio. */
private const val HomePosterRatioSampleCount = 4

/** 末尾"加载更多"哨兵压在视口外时不会组合 ⇒ 首屏末行右侧会空一格,离末尾不足一行就先取下一页 */
internal fun shouldPrefetchNextPage(
    lastVisibleIndex: Int,
    totalItemsCount: Int,
    columns: Int,
): Boolean = totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - 1 - columns

@Composable
fun HomeGridLayout(
    vm: HomeViewModel,
    topPadding: Dp,
    contentPadding: PaddingValues,
    pullState: PullToRefreshState,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
) {
    val sorts by vm.sorts.collectAsStateWithLifecycle()
    val partitions by vm.partitions.collectAsStateWithLifecycle()
    val rec by vm.rec.collectAsStateWithLifecycle()
    val sourceKey by vm.currentSource.collectAsStateWithLifecycle()
    val sortLoadFailed by vm.sortLoadFailed.collectAsStateWithLifecycle()
    // 首页每行图片列数(设置页"首页布局"可切 2/3):作为列数下限传入,手机档即"每行 2 或 3 列"
    val homeColumns by HomeSettings.columnsFlow.collectAsStateWithLifecycle()
    val posterRatioMode by HomeSettings.posterRatioModeFlow.collectAsStateWithLifecycle()
    val titleMeasurer = rememberTextMeasurer()
    val titleLine = with(LocalDensity.current) {
        titleMeasurer.measure("M", style = MaterialTheme.typography.titleSmall).size.height.toDp()
    }

    var selectedSortId by remember { mutableStateOf("") }
    var filterOpen by remember { mutableStateOf(false) }
    val gridStates = remember(sourceKey?.key) { mutableMapOf<String, LazyGridState>() }

    LaunchedEffect(sorts) {
        val kept = selectedSortId.isNotEmpty() && sorts.any { it.id == selectedSortId }
        if (!kept) {
            selectedSortId = vm.activeSortId?.takeIf { id -> sorts.any { it.id == id } }
                ?: sorts.firstOrNull()?.id.orEmpty()
        }
    }

    LaunchedEffect(selectedSortId) {
        if (selectedSortId.isNotEmpty()) vm.ensureLoaded(selectedSortId)
    }

    val partition = partitions.firstOrNull { it.sort.id == selectedSortId }
    val sort = partition?.sort ?: sorts.firstOrNull { it.id == selectedSortId }
    // 分类 tab 行不在滚动容器里,拿不到栅格的内容内边距,得单独让开侧边导航
    val navStart = contentPadding.calculateStartPadding(LocalLayoutDirection.current)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            HomeSortTabRow(
                sorts = sorts,
                selectedId = selectedSortId,
                onSelect = { selectedSortId = it },
                showFilter = sort?.filters?.isNotEmpty() == true,
                onFilter = { filterOpen = true },
                startInset = navStart,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Crossfade(
                targetState = selectedSortId,
                animationSpec = tween(220),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = "homeGridTab",
            ) { tabId ->
            val tabPartition = partitions.firstOrNull { it.sort.id == tabId }
            val tabSort = tabPartition?.sort ?: sorts.firstOrNull { it.id == tabId }
            val tabGridState = gridStates.getOrPut(tabId) { LazyGridState() }
            val ratioSampleKey = "${sourceKey?.key.orEmpty()}|$tabId|${tabSort?.filterSelect?.toSortedMap()}"
            val posterSamples = remember(ratioSampleKey) { LinkedHashMap<String, Float>() }
            var detectedPosterRatio by remember(ratioSampleKey) { mutableStateOf<Float?>(null) }
            val posterAspectRatio = when (posterRatioMode) {
                HomeSettings.PosterRatioMode.Automatic -> detectedPosterRatio ?: HomePosterRatio.DEFAULT
                HomeSettings.PosterRatioMode.Portrait -> HomePosterRatio.PORTRAIT
                HomeSettings.PosterRatioMode.Landscape -> HomePosterRatio.LANDSCAPE
            }
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 全部尺寸由当前窗口宽高推导(见 Fluid):断点只管结构,数值连续,
            // 折叠屏展开 673dp、分屏 500dp 这类非标准宽度不会卡在两档之间
            val availWidth = maxWidth - navStart
            val pad = Fluid.padding(availWidth)
            val gap = Fluid.gap(availWidth)
            val gridColumns = Fluid.columns(availWidth, homeColumns)
            val cardWidth = Fluid.cardWidth(availWidth, gridColumns)
            val cardRadius = Fluid.cardRadius(cardWidth)
            val cardTitleSize = Fluid.titleSize(cardWidth)
            val heroHeight = Fluid.heroHeight(availWidth, maxHeight)
            // 骨架屏共用一条动画时钟:加载态一次铺 18 个格子,不共享就是 18 条无限动画同时跑。
            // active 必须按真实加载态给 —— 常驻会让动画在内容就绪后仍每帧推进,应用进不了空闲。
            // 条件与下面 when 里"真的铺骨架"的两支严格一致(null 且 sorts 非空 / Idle / Loading)。
            val shimmerActive = when {
                tabPartition == null -> sorts.isNotEmpty()
                tabPartition.state == HomeViewModel.PartitionState.Idle -> true
                tabPartition.state == HomeViewModel.PartitionState.Loading -> true
                else -> false
            }
            ShimmerHost(active = shimmerActive) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns),
                state = tabGridState,
                modifier = Modifier
                    .fillMaxSize()
                    .pullToRefresh(
                        isRefreshing = false,
                        state = pullState,
                        onRefresh = { vm.reload() },
                    ),
                contentPadding = PaddingValues(
                    start = pad + navStart,
                    end = pad,
                    top = gap,
                    bottom = 88.dp + contentPadding.calculateBottomPadding(),
                ),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                // 头图只挂在**第一个分类**上(通常是「热门/推荐」)。
                // 每个分类都挂一遍会让用户以为切了页签内容没变 —— 头图是"今天的推荐",
                // 不是分类内容的一部分。放在网格首项而不是页签上方,是为了让它跟着滚动走:
                // 固定在顶部的头图会永久吃掉 190dp 首屏高度,列表越往下滑越亏。
                //
                // 推荐位(rec)在很多源上是空的 —— 那时退到当前分类的前 5 条。
                // 不兜底的话,设计稿里最重的那块视觉资产在真机上直接消失(实测过一次)。
                val heroVideos = rec.videos.take(5)
                    .ifEmpty { tabPartition?.videos?.take(5).orEmpty() }
                    .ifEmpty { partitions.firstOrNull()?.videos?.take(5).orEmpty() }
                // 用**位置**判断而不是比较 id:页签行的选中态会 coerceAtLeast(0),
                // id 对不上时高亮仍落在第一个,用 id 比较会出现"看着是第一个、判断却不成立"
                val heroTabIndex = sorts.indexOfFirst { it.id == tabId }
                if (heroTabIndex <= 0 && heroVideos.isNotEmpty()) {
                    item(
                        key = "hero",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "hero",
                    ) {
                        HeroCarousel(
                            videos = heroVideos,
                            onCardClick = onCardClick,
                            modifier = Modifier.padding(bottom = gap),
                            heroHeight = heroHeight,
                            cornerRadius = Fluid.heroRadius(availWidth),
                        )
                    }
                }
                if (tabSort != null && tabSort.filters.isNotEmpty()) {
                    item(
                        key = "chips_${tabSort.id}",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "chips",
                    ) {
                        Box(modifier = Modifier.padding(top = 0.dp)) {
                            HomeFilterChipsRow(sort = tabSort) { selection ->
                                tabPartition?.let { vm.applyFilter(it, selection) }
                            }
                        }
                    }
                }
                when (tabPartition?.state) {
                    null -> if (sorts.isEmpty()) {
                        item(
                            key = "no_sort",
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "hint",
                        ) {
                            if (sortLoadFailed) {
                                HomeGridHint(
                                    text = stringResource(R.string.common_load_failed_network),
                                    onRetry = { vm.retrySort() },
                                )
                            } else {
                                HomeGridHint(text = stringResource(R.string.common_empty_content))
                            }
                        }
                    } else {
                        items(HomeGridSkeletonCount, contentType = { "skeleton" }) {
                            HomeGridSkeleton(titleLine, posterAspectRatio)
                        }
                    }

                    HomeViewModel.PartitionState.Idle, HomeViewModel.PartitionState.Loading ->
                        items(HomeGridSkeletonCount, contentType = { "skeleton" }) {
                            HomeGridSkeleton(titleLine, posterAspectRatio)
                        }

                    HomeViewModel.PartitionState.Empty -> item(
                        key = "empty_$tabId",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "hint",
                    ) {
                        HomeGridHint(text = stringResource(R.string.common_empty_content))
                    }

                    HomeViewModel.PartitionState.Error -> item(
                        key = "error_$tabId",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "hint",
                    ) {
                        HomeGridHint(
                            text = stringResource(R.string.common_load_failed_network),
                            onRetry = { vm.retryPartition(tabPartition) },
                        )
                    }

                    HomeViewModel.PartitionState.Ready -> {
                        val videos = tabPartition.videos
                        val sampleTarget = minOf(HomePosterRatioSampleCount, videos.size)
                        itemsIndexed(
                            videos,
                            key = { index, video -> "${index}_${video.id}_${video.name}" },
                            contentType = { _, _ -> "poster" },
                        ) { _, video ->
                            VodCard(
                                video = video,
                                onClick = { onCardClick(video) },
                                onLongClick = { onCardLongClick(video) },
                                style = VodCardStyle.Stacked,
                                posterAspectRatio = posterAspectRatio,
                                fitPosterContent = true,
                                cornerRadius = cardRadius,
                                titleSize = cardTitleSize,
                                onPosterAspectRatio = { ratio ->
                                    val imageKey = video.pic?.takeIf { it.isNotBlank() }
                                    if (posterRatioMode == HomeSettings.PosterRatioMode.Automatic &&
                                        detectedPosterRatio == null && imageKey != null && sampleTarget > 0 &&
                                        !posterSamples.containsKey(imageKey)
                                    ) {
                                        posterSamples[imageKey] = ratio
                                        if (posterSamples.size >= sampleTarget) {
                                            detectedPosterRatio = HomePosterRatio.dominant(posterSamples.values)
                                        }
                                    }
                                },
                            )
                        }
                        item(key = "more_$tabId", span = { GridItemSpan(maxLineSpan) }) {
                            LaunchedEffect(videos.size) {
                                if (tabPartition.hasMore) vm.loadMorePartition(tabPartition)
                            }
                            // 页脚只在三种情形说话:真在请求⇒"加载中…";到底⇒"没有更多了";
                            // 还有更多但此刻没在请求⇒留空(滚动会自动触发下一次)。
                            // 旧实现只看 hasMore,把"还有更多"直接印成"加载中…"—— 而站点不报
                            // pagecount 时(豆瓣这类)hasMore 永远为真,翻到空页也照样"加载中",
                            // 用户完全分不清是没加载完还是已经加载完毕(2026-10-16 反馈)。
                            val footerRes = when {
                                !tabPartition.hasMore -> R.string.common_no_more
                                tabPartition.paging -> R.string.common_loading_more
                                else -> 0
                            }
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (footerRes != 0) {
                                    Text(
                                        text = stringResource(footerRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (tabPartition?.state == HomeViewModel.PartitionState.Ready) {
                LaunchedEffect(tabGridState, tabId, tabPartition.videos.size) {
                    // 用 first 而不是 collect:每次内容变长只预取一次。源报 maxPage=0 且翻到空页时
                    // hasMore 永远为真,collect 会被"响应→重组→重新布局→再触发"的回路套成连环请求
                    snapshotFlow { tabGridState.layoutInfo }
                        .first { info ->
                            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@first false
                            shouldPrefetchNextPage(last, info.totalItemsCount, gridColumns)
                        }
                    vm.loadMorePartition(tabPartition)
                }
            }
            }
            }
            }
        }
        HomePullRefreshIndicator(
            state = pullState,
            isRefreshing = false,
            topPadding = topPadding + HomeGridTabRowHeight,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    if (filterOpen) {
        sort?.let {
            FilterSheet(
                sort = it,
                onDismiss = { filterOpen = false },
                onConfirm = { selection -> partition?.let { p -> vm.applyFilter(p, selection) } },
            )
        }
    }
}

@Composable
private fun HomeGridSkeleton(titleLine: Dp, posterAspectRatio: Float) {
    Column {
        // 圆角必须与 VodCard 的海报一致,否则内容到达的瞬间会"缩一下角"
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(posterAspectRatio),
            shape = RoundedCornerShape(MediaBoxRadius.Small),
        )
        Spacer(modifier = Modifier.height(6.dp + titleLine))
    }
}

@Composable
private fun HomeGridHint(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.common_retry))
            }
        }
    }
}

@Composable
private fun HomeSortTabRow(
    sorts: List<MovieSort.SortData>,
    selectedId: String,
    onSelect: (String) -> Unit,
    showFilter: Boolean,
    onFilter: () -> Unit,
    startInset: Dp,
) {
    if (sorts.isEmpty()) {
        Spacer(modifier = Modifier.fillMaxWidth().height(HomeGridTabRowHeight))
        return
    }
    val selectedIndex = sorts.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HomeGridTabRowHeight)
            .padding(start = startInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SecondaryScrollableTabRow(
            selectedTabIndex = selectedIndex,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            edgePadding = 16.dp,
            divider = {},
            indicator = {
                // 指示器交给选中项自己的背景去画(见下方 text 里的 background)。
                // 用 TabRow 的 indicator 画胶囊会盖在文字上面 —— 真机上表现为
                // "选中的分类只剩一个空胶囊、文字消失",这是 M3 ScrollableTabRow 的绘制顺序决定的。
            },
        ) {
            sorts.forEach { item ->
                val isSelected = item.id == selectedId
                Tab(
                    selected = isSelected,
                    onClick = { onSelect(item.id) },
                    text = {
                        Text(
                            text = item.name ?: "",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    },
                    selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showFilter) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onFilter),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_filter),
                    contentDescription = stringResource(R.string.common_filter),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun HomeFilterChipsRow(sort: MovieSort.SortData, onPick: (Map<String, String>) -> Unit) {
    val filter = sort.filters.firstOrNull() ?: return
    val entries = filter.values.entries.toList()
    val selectedKey = sort.filterSelect[filter.key]
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    fun pick(entry: Map.Entry<String, String>) {
        val next = HashMap(sort.filterSelect)
        if (selectedKey == entry.key) {
            next.remove(filter.key)
        } else {
            next[filter.key] = entry.key
        }
        onPick(next)
    }

    val neededWidth = entries.fold(0.dp) { acc, entry ->
        acc + with(density) { measurer.measure(entry.value, style).size.width.toDp() } +
            HomeFilterChipPadding * 2 + HomeFilterChipSpacing
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth <= HomeFilterChipEqualWidthMaxWidth &&
            neededWidth - HomeFilterChipSpacing + HomeFilterChipFitSlack <= maxWidth
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HomeFilterChipSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEach { entry ->
                    HomeFilterChip(
                        text = entry.value,
                        selected = selectedKey == entry.key,
                        onClick = { pick(entry) },
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HomeFilterChipSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(entries, key = { it.key }) { entry ->
                    HomeFilterChip(
                        text = entry.value,
                        selected = selectedKey == entry.key,
                        onClick = { pick(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier
            .clip(ContinuousCapsule)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceBright
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = HomeFilterChipPadding, vertical = 7.dp),
    )
}
