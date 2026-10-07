@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.github.tvbox.osc.ui.page

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.activity.ConfigManageActivity
import com.github.tvbox.osc.ui.activity.PartitionListActivity
import com.github.tvbox.osc.ui.activity.SearchActivity
import com.github.tvbox.osc.ui.components.MediaBoxBottomSheet
import com.github.tvbox.osc.ui.components.HeroCarousel
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.SearchField
import com.github.tvbox.osc.ui.components.SearchSettingsSheet
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsOptionRow
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SkeletonBox
import com.github.tvbox.osc.ui.components.VodCardMenu
import com.github.tvbox.osc.ui.components.glassTopBarSurface
import com.github.tvbox.osc.ui.components.rememberVodCardMenuState
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.util.HomeSettings
import com.github.tvbox.osc.util.SiteSearch
import com.github.tvbox.osc.util.SourceIdentityMask
import com.kyant.capsule.ContinuousCapsule
import com.github.tvbox.osc.ui.page.jumpToSearch
import kotlin.math.roundToInt
import com.github.tvbox.osc.ui.activity.SearchViewModel

private val HomeSourceCapsuleMaxWidth = 240.dp

// 自适应图标前景层在系统内的缩放系数，此处复刻以呈现与桌面图标一致的 logo 占比
private const val CapsuleLogoZoom = 1.5f

private val HomeTopBarControlSpacing = 8.dp

@Composable
fun HomePage(vm: HomeViewModel, contentPadding: PaddingValues = PaddingValues(0.dp)) {
    // 页面保持全出血(背景延伸到导航栏之下,玻璃才有内容可取),只把内容让开
    val navStart = contentPadding.calculateStartPadding(LocalLayoutDirection.current)
    val navBottom = contentPadding.calculateBottomPadding()
    val context = LocalContext.current
    val currentSource by vm.currentSource.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val rec by vm.rec.collectAsStateWithLifecycle()
    val partitions by vm.partitions.collectAsStateWithLifecycle()
    val vodMenu = rememberVodCardMenuState()

    val listState = rememberLazyListState()

    val pullState = rememberPullToRefreshState()

    val pageLoading by vm.pageLoading.collectAsStateWithLifecycle()
    val sortLoadFailed by vm.sortLoadFailed.collectAsStateWithLifecycle()
    val homeLayout by HomeSettings.layoutFlow.collectAsStateWithLifecycle()
    LaunchedEffect(homeLayout) { vm.onLayoutChanged() }
    LaunchedEffect(vm) {
        vm.pageErrorEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(vm) {
        vm.actionMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.refreshPartitions()
        }
    }

    var showSourceSheet by remember { mutableStateOf(false) }
    var showSearchSettings by remember { mutableStateOf(false) }

    // ── 源身份匿名 ──────────────────────────────────────────────────────────
    // 横幅与换源面板共用**同一份编号**:用户看到的是「源 3」,而不是
    // 「🦋 蝴蝶全量独享专线 · 严禁外泄多人共享(违者封禁)」这种带广告文案的真实站名。
    //
    // ⚠️ 下标取自**完整源列表** `sources`,不是过滤后的 `filtered`。
    // 若按过滤后的下标编号,同一个源在"输入搜索词前/后"会显示成两个不同数字,
    // 横幅上的编号也会与面板里那一行对不上。
    //
    // ⚠️ `stringResource` 是 @Composable,必须**先在 Composable 作用域求值**再传进
    // `remember` —— 直接写 `remember { stringResource(...) }` 编译不过
    // ("@Composable invocations can only happen from the context of a @Composable
    // function",v1.0.24 构建 37492586746 踩过)。
    val anonPrefix = stringResource(R.string.common_source_anonymous_prefix)
    val fallbackSourceLabel = stringResource(R.string.home_subscription_source)
    val anonymousLabelOf = remember(sources, anonPrefix) {
        sources.mapIndexed { index, bean ->
            bean.key to SourceIdentityMask.anonymousLabel(index, anonPrefix)
        }.toMap()
    }
    // ⚠️ 不能直接写 `anonymousLabelOf[currentSource?.key]`:Map<String,String>.get 只收
    // 非空 String,传 String? 编译不过。走 let 让 key 在 lambda 内完成非空收窄。
    val currentSourceLabel = currentSource?.key
        ?.let { anonymousLabelOf[it] }
        ?.ifEmpty { fallbackSourceLabel }
        ?: fallbackSourceLabel

    AppTopBarScaffold(
        collapseEnabled = false,
        topBarStartInset = navStart,
        titleContent = {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .widthIn(
                            max = minOf(
                                HomeSourceCapsuleMaxWidth,
                                maxWidth - HomeTopBarControlSpacing,
                            ),
                        )
                        .glassTopBarSurface(ContinuousCapsule, MaterialTheme.colorScheme.cardContainer)
                        .heightIn(min = 40.dp)
                        .clickable { showSourceSheet = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val capsuleLogo = remember { ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground) }
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .drawBehind {
                                capsuleLogo?.let { drawable ->
                                    val w = size.width
                                    val h = size.height
                                    drawable.setBounds(
                                        (w / 2 - w * CapsuleLogoZoom / 2).roundToInt(),
                                        (h / 2 - h * CapsuleLogoZoom / 2).roundToInt(),
                                        (w / 2 + w * CapsuleLogoZoom / 2).roundToInt(),
                                        (h / 2 + h * CapsuleLogoZoom / 2).roundToInt(),
                                    )
                                    drawable.draw(drawContext.canvas.nativeCanvas)
                                }
                            },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        // ⚠️ 这里原来直接渲染 `currentSource?.name`,把真实站名(常带广告文案)
                        // 长期挂在首页顶部。改为匿名编号,与下方换源面板同一口径。
                        text = currentSourceLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                    .clickable { showSearchSettings = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.search_settings),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(HomeTopBarControlSpacing))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                    .clickable {
                        context.startActivity(Intent(context, SearchActivity::class.java))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.common_search),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    ) { topPad, _ ->
        when {
            pageLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(Modifier.size(64.dp))
                }
            }
            sources.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPad),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.ic_empty_record),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = stringResource(R.string.home_no_source),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { ConfigManageActivity.start(context) }) {
                            Text(stringResource(R.string.config_add_subscribe))
                        }
                    }
                }
            }
            else -> {
            if (homeLayout == HomeSettings.HomeLayout.Vertical) {
                HomeGridLayout(
                    vm = vm,
                    topPadding = topPad,
                    contentPadding = contentPadding,
                    pullState = pullState,
                    onCardClick = { video -> handleCardClick(vm, video, context) },
                    onCardLongClick = { video -> vodMenu.show(video) },
                )
            } else LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pullToRefresh(
                        isRefreshing = false,
                        state = pullState,
                        onRefresh = { vm.reload() },
                    ),
                contentPadding = PaddingValues(
                    start = navStart,
                    top = topPad + 8.dp,
                    bottom = 88.dp + navBottom,
                ),
            ) {
                item(key = "hero") {
                    if (rec.state == HomeViewModel.PartitionState.Ready && rec.videos.isNotEmpty()) {
                        HeroCarousel(
                            videos = rec.videos.take(5),
                            onCardClick = { video -> handleCardClick(vm, video, context) },
                        )
                    } else if (rec.state == HomeViewModel.PartitionState.Loading) {
                        SkeletonBox(
                            modifier = Modifier
                                .fillParentMaxWidth(0.78f)
                                .aspectRatio(1.5f)
                                .clip(RoundedCornerShape(20.dp)),
                            shape = RoundedCornerShape(20.dp),
                        )
                    } else if (rec.state == HomeViewModel.PartitionState.Error) {
                        Box(
                            modifier = Modifier
                                .fillParentMaxWidth(0.78f)
                                .aspectRatio(1.5f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surfaceBright),
                            contentAlignment = Alignment.Center,
                        ) {
                            LoadStateBox(
                                state = LoadState.Error(),
                                emptyText = "",
                                errorText = stringResource(R.string.home_load_timeout),
                                                retryText = stringResource(R.string.common_retry),
                                onRetry = { vm.loadHome() },
                            )
                        }
                    }
                }
                if (rec.state != HomeViewModel.PartitionState.Empty &&
                    (rec.state == HomeViewModel.PartitionState.Loading || rec.videos.size > 5)
                ) {
                    item(key = "rec") {
                        PartitionSection(
                            title = stringResource(R.string.home_recommend),
                            state = rec.state,
                            videos = rec.videos.drop(5),
                            onLoadMore = {},
                            onCardClick = { video -> handleCardClick(vm, video, context) },
                            onCardLongClick = { video -> vodMenu.show(video) },
                            cardWidth = 140.dp,
                        )
                    }
                }
                if (sortLoadFailed) {
                    item(key = "sort_error") {
                        Row(
                            modifier = Modifier
                                .fillParentMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.common_load_failed_network),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.retrySort() }) {
                                Text(text = stringResource(R.string.common_retry))
                            }
                        }
                    }
                }
                items(partitions, key = { it.sort.id }) { p ->
                    PartitionSection(
                        title = p.sort.name ?: "",
                        state = p.state,
                        videos = p.videos,
                        onLoadMore = { vm.loadMorePartition(p) },
                        onCardClick = { video -> handleCardClick(vm, video, context) },
                        onCardLongClick = { video -> vodMenu.show(video) },
                        onOpenAll = {
                            PartitionListActivity.startForPartition(context, p.sort)
                        },
                        onRetry = { vm.retryPartition(p) },
                    )
                }
            }
        }
        }

        if (sources.isNotEmpty() && homeLayout == HomeSettings.HomeLayout.Horizontal) {
            HomePullRefreshIndicator(
                state = pullState,
                isRefreshing = false,
                topPadding = topPad,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    if (showSourceSheet) {
        MediaBoxBottomSheet(
            onDismissRequest = { showSourceSheet = false },
            title = stringResource(R.string.home_subscription_source),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            isScrollable = false,
        ) {
            val dismissAnimated = LocalSheetDismiss.current
            val keyboard = LocalSoftwareKeyboardController.current
            var query by remember { mutableStateOf("") }
            val filtered = remember(sources, query) { SiteSearch.filter(sources, query) }
            val listState = rememberLazyListState()
            val selectedIndex = filtered.indexOfFirst { it.key == currentSource?.key }
            LaunchedEffect(query.isEmpty()) {
                if (query.isEmpty() && selectedIndex > 0) listState.scrollToItem(selectedIndex)
            }
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
                if (filtered.isEmpty()) {
                    item {
                        LoadStateBox(
                            state = LoadState.Empty,
                            // 空词下为空 = 没配订阅,不是"没搜到"
                            emptyText = stringResource(
                                if (query.isEmpty()) R.string.config_empty_subscribe else R.string.home_site_search_empty,
                            ),
                            errorText = "",
                            retryText = "",
                            emptyIconRes = R.drawable.ic_empty_record,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                        )
                    }
                }
                // 源之间 2dp 只能随项带:统一 verticalArrangement 会连带放大"源列表 / 配置接口"的组间距
                itemsIndexed(filtered) { index, bean ->
                    val selected = bean.key == currentSource?.key
                    SettingsCard(
                        position = when {
                            filtered.size == 1 -> SettingsCardPosition.SINGLE
                            index == 0 -> SettingsCardPosition.FIRST
                            index == filtered.size - 1 -> SettingsCardPosition.LAST
                            else -> SettingsCardPosition.MIDDLE
                        },
                        modifier = Modifier.padding(bottom = if (index == filtered.lastIndex) 0.dp else 2.dp),
                        color = MaterialTheme.colorScheme.surfaceBright,
                    ) {
                        SettingsOptionRow(
                            // ⚠️ 原为 `bean.name ?: bean.key`,把全部源的真实站名摊给用户。
                            // 与横幅统一改为匿名编号(同一份 anonymousLabelOf)。
                            // 兜底不再回退到 `bean.key` —— 那本身就是站点的技术标识,同样算泄露。
                            title = anonymousLabelOf[bean.key] ?: stringResource(R.string.common_unnamed),
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    vm.switchSource(bean)
                                }
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
                                    ConfigManageActivity.start(context)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSearchSettings) {
        SearchSettingsSheet(
            onDismiss = { showSearchSettings = false },
            onSelectionChanged = { SearchViewModel.loadCheckedSources() },
        )
    }

    val menuContext = LocalContext.current
    VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }
}

@Composable
fun HomePullRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    topPadding: Dp,
    modifier: Modifier = Modifier,
) {
    PullToRefreshDefaults.IndicatorBox(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier
            .offset(y = topPadding)
            .size(48.dp),
        shape = RectangleShape,
        containerColor = Color.Transparent,
        elevation = 0.dp,
    ) {
        Crossfade(targetState = isRefreshing) { refreshing ->
            if (refreshing) {
                ContainedLoadingIndicator(modifier = Modifier.size(48.dp))
            } else {
                ContainedLoadingIndicator(
                    progress = { state.distanceFraction },
                    modifier = Modifier
                        .size(48.dp)
                        .drawWithContent {
                            val progress = state.distanceFraction
                            if (progress > 1f) {
                                rotate(degrees = -(progress - 1f) * 180f) {
                                    this@drawWithContent.drawContent()
                                }
                            } else {
                                drawContent()
                            }
                        },
                )
            }
        }
    }
}

private fun handleCardClick(vm: HomeViewModel, video: Movie.Video, context: android.content.Context) {
    context.dispatchVodCardClick(video, onAction = { vm.handleAction(it) })
}

@Composable
private fun PartitionSection(
    title: String,
    state: HomeViewModel.PartitionState,
    videos: List<Movie.Video>,
    onLoadMore: () -> Unit,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
    onOpenAll: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    cardWidth: Dp = 110.dp,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (onOpenAll != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .clickable(onClick = onOpenAll)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.common_all),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        when (state) {
            HomeViewModel.PartitionState.Idle -> Unit

            HomeViewModel.PartitionState.Loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(3) {
                        SkeletonBox(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }
            }

            HomeViewModel.PartitionState.Empty -> Text(
                text = stringResource(R.string.common_empty_content),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            HomeViewModel.PartitionState.Error -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.common_load_failed_network),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRetry?.invoke() }) {
                        Text(text = stringResource(R.string.common_retry))
                    }
                }
            }

            HomeViewModel.PartitionState.Ready -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(videos) { index, video ->
                    com.github.tvbox.osc.ui.components.VodCard(
                        video = video,
                        onClick = { onCardClick(video) },
                        onLongClick = { onCardLongClick(video) },
                        modifier = Modifier.width(cardWidth),
                    )
                }
                item(key = "more_$title") {
                    LaunchedEffect(videos.size) { onLoadMore() }
                }
            }
        }
    }
}
