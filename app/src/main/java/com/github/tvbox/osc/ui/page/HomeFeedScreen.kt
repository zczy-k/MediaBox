package com.github.tvbox.osc.ui.page

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.activity.ConfigManageActivity
import com.github.tvbox.osc.ui.activity.SearchActivity
import com.github.tvbox.osc.ui.activity.SearchViewModel
import com.github.tvbox.osc.ui.components.FilterSheet
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.MediaBoxBottomSheet
import com.github.tvbox.osc.ui.components.SearchField
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsOptionRow
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SkeletonBox
import com.github.tvbox.osc.ui.components.VodCardMenu
import com.github.tvbox.osc.ui.components.VodPoster
import com.github.tvbox.osc.ui.components.ratingBadgeText
import com.github.tvbox.osc.ui.components.rememberVodCardMenuState
import com.github.tvbox.osc.ui.theme.Fluid
import com.github.tvbox.osc.ui.theme.MediaBoxRadius
import com.github.tvbox.osc.util.SiteSearch
import com.github.tvbox.osc.util.SourceIdentityMask
import kotlinx.coroutines.flow.first

/**
 * 首页 —— 按「流光 · 珊瑚橙」设计稿重写的一版。
 *
 * <p>为什么不改 [HomePage] / [HomeGridLayout]:那两个文件是"标题栏 + 下划线页签 + 横滑分区"
 * 的旧结构,在它上面调尺寸只会得到"换了颜色的旧界面"。这个文件是**独立实现**,
 * 旧的两个文件不再被 [MainScreen] 引用。
 *
 * <p>结构(自上而下,全部随可用宽度缩放):
 * 1. 搜索胶囊 —— 源切换在左端,右侧「更多」
 * 2. 通栏头图 —— 渐变压底、标题/评分压在上面、右下白色「立即播放」、短条指示点
 * 3. 分类胶囊 —— 横滑,选中品牌实底
 * 4. 筛选胶囊 —— 该分类有筛选时出现
 * 5. 3~N 列海报网格 —— 列数由 [Fluid.columns] 算出,卡片宽由列数反推
 */
@Composable
fun HomeFeedScreen(vm: HomeViewModel, contentPadding: PaddingValues = PaddingValues(0.dp)) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val navStart = contentPadding.calculateStartPadding(layoutDirection)
    val navEnd = contentPadding.calculateEndPadding(layoutDirection)
    val navBottom = contentPadding.calculateBottomPadding()

    val sorts by vm.sorts.collectAsStateWithLifecycle()
    val partitions by vm.partitions.collectAsStateWithLifecycle()
    val rec by vm.rec.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val currentSource by vm.currentSource.collectAsStateWithLifecycle()
    val pageLoading by vm.pageLoading.collectAsStateWithLifecycle()
    val sortLoadFailed by vm.sortLoadFailed.collectAsStateWithLifecycle()

    val vodMenu = rememberVodCardMenuState()
    val pullState = rememberPullToRefreshState()
    val gridState = rememberLazyGridState()

    var selectedSortId by remember { mutableStateOf("") }
    var filterOpen by remember { mutableStateOf(false) }
    var showSourceSheet by remember { mutableStateOf(false) }

    LaunchedEffect(sorts) {
        val kept = selectedSortId.isNotEmpty() && sorts.any { it.id == selectedSortId }
        if (!kept) {
            selectedSortId = vm.activeSortId?.takeIf { id -> sorts.any { it.id == id } }
                ?: sorts.firstOrNull()?.id.orEmpty()
        }
    }
    LaunchedEffect(selectedSortId) {
        if (selectedSortId.isNotEmpty()) vm.ensureLoaded(selectedSortId)
        gridState.scrollToItem(0)
    }
    LaunchedEffect(vm) {
        vm.pageErrorEvents.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(vm) {
        vm.actionMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.refreshPartitions()
        }
    }

    // 源匿名:用户只看到「源 3」,不暴露真实站名(与旧首页同一口径)
    val anonPrefix = stringResource(R.string.common_source_anonymous_prefix)
    val fallbackLabel = stringResource(R.string.home_subscription_source)
    val anonymousLabelOf = remember(sources, anonPrefix) {
        sources.mapIndexed { index, bean ->
            bean.key to SourceIdentityMask.anonymousLabel(index, anonPrefix)
        }.toMap()
    }
    val currentSourceLabel = currentSource?.key
        ?.let { anonymousLabelOf[it] }
        ?.ifEmpty { fallbackLabel }
        ?: fallbackLabel

    val partition = partitions.firstOrNull { it.sort.id == selectedSortId }
    val sort = partition?.sort ?: sorts.firstOrNull { it.id == selectedSortId }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(start = navStart, end = navEnd),
        ) {
            HomeSearchBar(
                sourceLabel = currentSourceLabel,
                onSourceClick = { showSourceSheet = true },
                onSearchClick = {
                    context.startActivity(Intent(context, SearchActivity::class.java))
                },
            )

            when {
                pageLoading -> HomeLoading()
                sources.isEmpty() -> HomeNoSource {
                    ConfigManageActivity.start(context)
                }
                else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val pad = Fluid.padding(maxWidth)
                    val gap = Fluid.gap(maxWidth)
                    val columns = Fluid.columns(maxWidth, 3)
                    val cardWidth = Fluid.cardWidth(maxWidth, columns)
                    val heroHeight = Fluid.heroHeight(maxWidth, maxHeight)

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pullToRefresh(
                                isRefreshing = false,
                                state = pullState,
                                onRefresh = { vm.reload() },
                            ),
                        contentPadding = PaddingValues(
                            start = pad,
                            end = pad,
                            top = gap,
                            bottom = gap + navBottom,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        // ① 通栏头图:推荐位为空时退到当前分类前 5 条。
                        //
                        // ⚠️ 槽位**常驻**,是否渲染在 item 内部判断 —— 之前把 if 写在外面,
                        // 首帧数据还没到时这个 item 根本没被注册,后续数据到了也不会补位,
                        // 真机表现为"冷启动没有头图、切一次分类才出现"。
                        item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                            val heroVideos = rec.videos.take(5)
                                .ifEmpty { partition?.videos?.take(5).orEmpty() }
                            if (heroVideos.isNotEmpty()) {
                                HomeHero(
                                    videos = heroVideos,
                                    height = heroHeight,
                                    radius = Fluid.heroRadius(maxWidth),
                                    onClick = { video -> context.dispatchVodCardClick(video) { vm.handleAction(it) } },
                                )
                            }
                        }

                        // ② 分类胶囊(槽位常驻,理由同头图:首帧 sorts 为空时不能丢槽位)
                        item(key = "sorts", span = { GridItemSpan(maxLineSpan) }) {
                            if (sorts.isNotEmpty()) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    contentPadding = PaddingValues(vertical = 2.dp),
                                ) {
                                    items(sorts, key = { it.id }) { item ->
                                        HomePill(
                                            text = item.name ?: "",
                                            selected = item.id == selectedSortId,
                                            onClick = { selectedSortId = item.id },
                                        )
                                    }
                                }
                            }
                        }

                        // ③ 筛选胶囊(该分类带筛选时才有)
                        if (sort?.filters?.isNotEmpty() == true) {
                            item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                                HomeFilterPills(
                                    sort = sort,
                                    onPick = { selection -> partition?.let { vm.applyFilter(it, selection) } },
                                    onOpenSheet = { filterOpen = true },
                                )
                            }
                        }

                        when (partition?.state) {
                            null -> if (sorts.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    HomeHint(
                                        text = stringResource(
                                            if (sortLoadFailed) R.string.common_load_failed_network
                                            else R.string.common_empty_content
                                        ),
                                        onRetry = if (sortLoadFailed) ({ vm.retrySort() }) else null,
                                    )
                                }
                            } else {
                                items(12) { HomePosterSkeleton(cardWidth) }
                            }

                            HomeViewModel.PartitionState.Idle,
                            HomeViewModel.PartitionState.Loading ->
                                items(12) { HomePosterSkeleton(cardWidth) }

                            HomeViewModel.PartitionState.Empty ->
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    HomeHint(text = stringResource(R.string.common_empty_content))
                                }

                            HomeViewModel.PartitionState.Error ->
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    HomeHint(
                                        text = stringResource(R.string.common_load_failed_network),
                                        onRetry = { partition?.let { vm.retryPartition(it) } },
                                    )
                                }

                            HomeViewModel.PartitionState.Ready -> {
                                gridItems(
                                    partition.videos,
                                    key = { "${it.id}_${it.name}" },
                                ) { video ->
                                    HomePoster(
                                        video = video,
                                        cardWidth = cardWidth,
                                        onClick = {
                                            context.dispatchVodCardClick(video) { vm.handleAction(it) }
                                        },
                                        onLongClick = { vodMenu.show(video) },
                                    )
                                }
                                item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                                    HomeFooter(partition)
                                    LaunchedEffect(partition.videos.size) {
                                        if (partition.hasMore) vm.loadMorePartition(partition)
                                    }
                                }
                            }
                        }
                    }

                    // 滚到接近末尾时预取下一页(与旧实现同判据)
                    if (partition?.state == HomeViewModel.PartitionState.Ready) {
                        LaunchedEffect(gridState, selectedSortId, partition.videos.size) {
                            snapshotFlow { gridState.layoutInfo }
                                .first { info ->
                                    val last = info.visibleItemsInfo.lastOrNull()?.index
                                        ?: return@first false
                                    shouldPrefetchNextPage(last, info.totalItemsCount, columns)
                                }
                            vm.loadMorePartition(partition)
                        }
                    }
                }
            }
        }
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

    if (showSourceSheet) {
        HomeSourceSheet(
            sources = sources,
            anonymousLabelOf = anonymousLabelOf,
            currentKey = currentSource?.key,
            onPick = { vm.switchSource(it) },
            onManage = { ConfigManageActivity.start(context) },
            onDismiss = { showSourceSheet = false },
        )
    }

    val menuContext = LocalContext.current
    VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }
}

// ───────────────────────── 顶部搜索胶囊 ─────────────────────────

@Composable
private fun HomeSearchBar(
    sourceLabel: String,
    onSourceClick: () -> Unit,
    onSearchClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(MediaBoxRadius.Pill))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onSearchClick)
                .padding(start = 5.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(MediaBoxRadius.Pill))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable(onClick = onSourceClick)
                    .padding(start = 10.dp, end = 3.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(R.string.home_search_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ───────────────────────── 头图 ─────────────────────────

@Composable
private fun HomeHero(
    videos: List<Movie.Video>,
    height: androidx.compose.ui.unit.Dp,
    radius: androidx.compose.ui.unit.Dp,
    onClick: (Movie.Video) -> Unit,
) {
    val n = videos.size
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { n })
    val current = pagerState.currentPage % n

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        pageSpacing = 10.dp,
    ) { page ->
        val video = videos[page % n]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(radius))
                .clickable { onClick(video) },
        ) {
            // ── 方案 B:双层图 ──
            // 底层:同一张图放大裁切 + 模糊,做氛围背景。
            // 竖版海报(2:3)填横条(约 3:1),Crop 必然裁掉上下 —— 比例只能缓解不能消除;
            // 让"被裁的部分"变成故意虚化的背景,前景则完整显示,一个像素都不裁。
            // ⚠️ 不加 blur:Android 12+ 上真模糊会把背景糊成一团颜色,
            // 反而失去"放大图"的轮廓感(平板 Android 10 不支持 blur,恰好呈现清晰放大图,
            // 用户对比两台设备后明确更喜欢后者)。压暗层保证文字可读。
            VodPoster(
                name = video.name,
                pic = video.pic,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.18f
                        scaleY = 1.18f
                    },
            )
            // 中层:整体压暗,让 Fit 后的亮部与背景不突兀
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f)),
            )
            // ── 前景:海报靠左撑满高度(Fit 不裁) + 右侧信息列 ──
            // 竖版海报 Fit 只占头图约 1/3 宽,两侧大片模糊背景很难看(2026-10-21 用户反馈);
            // 信息移到海报旁边,空间被利用,视觉重心平衡(Netflix / Disney+ 式 hero)。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                // 海报+信息作为一组**整体居中**:两侧模糊背景均分,
                // 比左贴边 + 右侧一大片空要平衡(2026-10-21 用户定案)。
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VodPoster(
                    name = video.name,
                    pic = video.pic,
                    fitContent = true,
                    // 精确贴合 2:3,不留 Fit 的内边空隙
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(2f / 3f),
                )
                Column(
                    modifier = Modifier.widthIn(max = 230.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = video.name ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = buildString {
                        if (video.year > 0) append(video.year)
                        video.type?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it)
                        }
                        ratingBadgeText(video.note)?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it)
                            append(" 分")
                        }
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(n.coerceAtMost(5)) { i ->
                            val on = i == current
                            Box(
                                modifier = Modifier
                                    .height(4.dp)
                                    .width(if (on) 13.dp else 4.dp)
                                    .clip(CircleShape)
                                    .background(if (on) Color.White else Color.White.copy(alpha = 0.45f)),
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.42f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.74f),
                        )
                    ),
            )
            Text(
                text = stringResource(R.string.home_hot_recommend),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF3A0E04),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFFC0AE))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Text(
                text = stringResource(R.string.home_hero_play),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD6431F),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 14.dp)
                    .clip(RoundedCornerShape(MediaBoxRadius.Pill))
                    .background(Color.White)
                    .clickable { onClick(video) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ───────────────────────── 胶囊 / 海报 / 状态 ─────────────────────────

@Composable
private fun HomePill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(MediaBoxRadius.Pill))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun HomeFilterPills(
    sort: com.github.tvbox.osc.bean.MovieSort.SortData,
    onPick: (Map<String, String>) -> Unit,
    onOpenSheet: () -> Unit,
) {
    val filter = sort.filters.firstOrNull() ?: return
    val entries = filter.values.entries.toList()
    val selectedKey = sort.filterSelect[filter.key]
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(entries, key = { it.key }) { entry ->
            val on = selectedKey == entry.key
            Text(
                text = entry.value,
                style = MaterialTheme.typography.labelMedium,
                color = if (on) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(MediaBoxRadius.Pill))
                    .background(
                        if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainer
                    )
                    .clickable {
                        val next = HashMap(sort.filterSelect)
                        if (on) next.remove(filter.key) else next[filter.key] = entry.key
                        onPick(next)
                    }
                    .padding(horizontal = 13.dp, vertical = 6.dp),
            )
        }
        if (sort.filters.size > 1) {
            item {
                Text(
                    text = stringResource(R.string.common_filter),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(MediaBoxRadius.Pill))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable(onClick = onOpenSheet)
                        .padding(horizontal = 13.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 集数角标:只从 note 里提取「N集」这种纯数字信息,绝不回显 note 原文(会泄露站名) */
private val EPISODE_REGEX = Regex("(\\d+)\\s*集")

@Composable
private fun HomePoster(
    video: Movie.Video,
    cardWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Fluid.cardRadius(cardWidth)))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onClick),
        ) {
            VodPoster(
                name = video.name,
                pic = video.pic,
                modifier = Modifier.fillMaxSize(),
            )
            ratingBadgeText(video.note)?.let { score ->
                Text(
                    text = score,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.94f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            EPISODE_REGEX.find(video.note.orEmpty())?.let { m ->
                Text(
                    text = "${m.groupValues[1]}集",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.52f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            text = video.name ?: "",
            style = MaterialTheme.typography.titleSmall,
            fontSize = Fluid.titleSize(cardWidth),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun HomePosterSkeleton(cardWidth: androidx.compose.ui.unit.Dp) {
    Column {
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            shape = RoundedCornerShape(Fluid.cardRadius(cardWidth)),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HomeFooter(partition: HomeViewModel.Partition) {
    val text = when {
        !partition.hasMore -> stringResource(R.string.common_no_more)
        partition.paging -> stringResource(R.string.common_loading_more)
        else -> ""
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeHint(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRetry != null) {
            Text(
                text = stringResource(R.string.common_retry),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(MediaBoxRadius.Pill))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun HomeLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
        )
    }
}

@Composable
private fun HomeNoSource(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_empty_record),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.home_no_source),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.config_add_subscribe),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(MediaBoxRadius.Pill))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .clickable(onClick = onAdd)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

// ───────────────────────── 源面板 ─────────────────────────

@Composable
private fun HomeSourceSheet(
    sources: List<com.github.tvbox.osc.bean.SourceBean>,
    anonymousLabelOf: Map<String, String>,
    currentKey: String?,
    onPick: (com.github.tvbox.osc.bean.SourceBean) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    MediaBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.home_subscription_source),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        isScrollable = false,
    ) {
        val dismissAnimated = LocalSheetDismiss.current
        val keyboard = LocalSoftwareKeyboardController.current
        var query by remember { mutableStateOf("") }
        val filtered = remember(sources, query) { SiteSearch.filter(sources, query) }
        val listState = rememberLazyListState()
        SearchField(
            query = query,
            onQueryChange = { query = it },
            onSearch = { keyboard?.hide() },
            hint = stringResource(R.string.home_site_search_hint),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            itemsIndexed(filtered) { index, bean ->
                SettingsCard(
                    position = when {
                        filtered.size == 1 -> SettingsCardPosition.SINGLE
                        index == 0 -> SettingsCardPosition.FIRST
                        index == filtered.lastIndex -> SettingsCardPosition.LAST
                        else -> SettingsCardPosition.MIDDLE
                    },
                    modifier = Modifier.padding(bottom = if (index == filtered.lastIndex) 0.dp else 2.dp),
                    color = MaterialTheme.colorScheme.surfaceBright,
                ) {
                    SettingsOptionRow(
                        title = anonymousLabelOf[bean.key] ?: stringResource(R.string.common_unnamed),
                        selected = bean.key == currentKey,
                        onClick = {
                            if (bean.key != currentKey) onPick(bean)
                            dismissAnimated()
                        },
                    )
                }
            }
            item {
                SettingsGroup(title = null, modifier = Modifier.padding(top = 20.dp)) {
                    SettingsCard(
                        position = SettingsCardPosition.SINGLE,
                        color = MaterialTheme.colorScheme.surfaceBright,
                    ) {
                        SettingsRow(
                            title = stringResource(R.string.settings_config_manage),
                            onClick = {
                                dismissAnimated()
                                onManage()
                            },
                        )
                    }
                }
            }
        }
    }
}
