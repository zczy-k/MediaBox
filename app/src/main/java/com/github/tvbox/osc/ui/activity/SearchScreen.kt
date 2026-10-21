@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.tvbox.osc.ui.activity

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.SearchField
import com.github.tvbox.osc.ui.components.VodCardMenu
import com.github.tvbox.osc.ui.components.glassTopBarSurface
import com.github.tvbox.osc.ui.components.rememberVodCardMenuState
import com.github.tvbox.osc.ui.page.VodCardTarget
import com.github.tvbox.osc.ui.page.openVodCardOrDetail
import com.github.tvbox.osc.ui.page.resolveVodCardTarget
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.SearchSettings
import kotlinx.coroutines.delay
import com.github.tvbox.osc.ui.page.jumpToSearch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SearchScreen(vm: SearchViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val results by vm.results.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val searchedCount by vm.searchedCount.collectAsStateWithLifecycle()
    val settledCount by vm.settledCount.collectAsStateWithLifecycle()
    val timeoutCount by vm.timeoutCount.collectAsStateWithLifecycle()
    val totalCount by vm.totalCount.collectAsStateWithLifecycle()
    val searchableCount by vm.searchableCount.collectAsStateWithLifecycle()
    val hotSearch by vm.hotSearch.collectAsStateWithLifecycle()
    val suggest by vm.suggest.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(KV.get(HawkConfig.SEARCH_HISTORY, ArrayList<String>())) }
    val searchedTitle by vm.searchedTitle.collectAsStateWithLifecycle()
    val pinyinNotice by vm.pinyinNotice.collectAsStateWithLifecycle()
    val matchMode by vm.matchMode.collectAsStateWithLifecycle()
    val sitesEmpty by vm.sitesEmpty.collectAsStateWithLifecycle()
    val vodMenu = rememberVodCardMenuState()
    // 搜索结果布局按屏宽自动(2026-10-21):竖排(左站点栏+右列表)是宽屏形态,
    // 手机上左栏吃掉 100dp、右侧结果挤成一团 ⇒ 窄屏用横排(按源分区)。
    // 手动开关已删,三点菜单随之移除。
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val resultLayout = if (screenWidthDp >= 600) {
        SearchSettings.SearchLayout.Vertical
    } else {
        SearchSettings.SearchLayout.Horizontal
    }
    // 搜索设置弹层:footer 的"勾选范围"提示行可直达(2026-10-09),改完自动按新范围重搜

    LaunchedEffect(Unit) {
        if (SearchViewModel.isCheckedSourcesStale()) {
            SearchViewModel.loadCheckedSources()
        }
        val initTitle = activity?.intent?.getStringExtra("title")
        if (!initTitle.isNullOrEmpty()) {
            query = initTitle
            vm.search(initTitle)
        }
    }

    LaunchedEffect(query) {
        val t = query.trim()
        if (t.isEmpty()) {
            vm.clearSuggest()
        } else {
            delay(300)
            vm.fetchSuggest(t)
        }
    }

    val bootState by com.github.tvbox.osc.ui.page.AppBootstrap.state.collectAsStateWithLifecycle()
    LaunchedEffect(bootState) {
        if (bootState is com.github.tvbox.osc.ui.page.AppBootstrap.Boot.Ready && SearchViewModel.isCheckedSourcesStale()) {
            SearchViewModel.loadCheckedSources()
        }
    }

    // 进入搜索页即聚焦并弹键盘:否则用户要多点一次输入框才能打字
    val searchFocus = remember { FocusRequester() }

    val resultListState = rememberLazyListState()

    LaunchedEffect(resultLayout) {
        selectedSource = null
    }

    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        query = t
        hideIme(activity)
        selectedSource = null
        vm.search(t)
        history = KV.get(HawkConfig.SEARCH_HISTORY, ArrayList())
    }

    AppTopBarScaffold(
        collapseEnabled = false,
        titleContent = {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                onSearch = { submit(query) },
                focusRequester = searchFocus,
                autoFocus = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp),

            )
        },
        navigationIcon = {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { activity?.finish() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    ) { topPad, _ ->
        if (results.isEmpty() && !running && sitesEmpty) {
            SearchEmptyBox(topPad = topPad, text = stringResource(R.string.search_no_site))
        } else if (results.isEmpty() && !running) {
            SearchIdleContent(
                history = history,
                hotSearch = hotSearch,
                suggest = suggest,
                topPad = topPad,
                onSearch = { submit(it) },
                onClearHistory = {
                    HistoryHelper.clearSearchHistory()
                    history = ArrayList()
                },
                onRemoveHistory = { word ->
                    HistoryHelper.removeSearchHistory(word)
                    history = KV.get(HawkConfig.SEARCH_HISTORY, ArrayList())
                },
            )
        } else {
            SearchResultsContent(
                results = results,
                running = running,
                selectedSource = selectedSource,
                onSelectSource = { selectedSource = it },
                resultLayout = resultLayout,
                listState = resultListState,
                searchedTitle = searchedTitle,
                matchMode = matchMode,
                topPad = topPad,
                hasMore = hasMore,
                searchedCount = searchedCount,
                settledCount = settledCount,
                totalCount = totalCount,
                searchableSources = searchableCount,
                timeoutCount = timeoutCount,
                pinyinNotice = pinyinNotice,
                onLoadMore = { vm.loadNextBatch() },
                onSearchAll = { vm.searchAllRemaining() },
                onOpenSourceSettings = { SearchSettingsActivity.start(context) },
                // 点开影片即暂停自动续批:把线程与爬虫让给详情取数,别让剩余上百个源继续抢
                onCardClick = {
                    vm.pauseBatching()
                    // 复用首页那一套路由判据:该来源打不开(索引型源 / 空 id / msearch 占位)时
                    // 不进详情白等 —— 首页那种情况会再跳一次搜索,但搜索页本身已有同名结果在旁,
                    // 再跳一轮只是绕路(且可能来回),当场提示换来源最省时间。
                    when (resolveVodCardTarget(it)) {
                        is VodCardTarget.Search -> Toast.makeText(
                            context,
                            context.getString(R.string.search_card_unopenable),
                            Toast.LENGTH_SHORT,
                        ).show()

                        else -> context.openVodCardOrDetail(it)
                    }
                },
                onCardLongClick = { vodMenu.show(it) },
            )
        }
    }

    val menuContext = LocalContext.current
    VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }
}

@Composable
private fun SearchEmptyBox(topPad: Dp, text: String, statsText: String? = null) {
    LoadStateBox(
        state = LoadState.Empty,
        emptyText = text,
        emptySubText = statsText,
        errorText = "",
        retryText = "",
        // 与历史/收藏等空态统一图标口径(2026-10-10);此前搜索空态是裸文字
        emptyIconRes = R.drawable.ic_empty_record,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPad),
    )
}

@Composable
private fun SearchResultsContent(
    results: List<SearchViewModel.SourceResult>,
    running: Boolean,
    selectedSource: String?,
    onSelectSource: (String?) -> Unit,
    resultLayout: SearchSettings.SearchLayout,
    listState: LazyListState,
    searchedTitle: String,
    matchMode: SearchSettings.MatchMode,
    topPad: Dp,
    hasMore: Boolean,
    searchedCount: Int,
    settledCount: Int,
    totalCount: Int,
    searchableSources: Int,
    timeoutCount: Int = 0,
    pinyinNotice: String? = null,
    onLoadMore: () -> Unit,
    onSearchAll: () -> Unit,
    onOpenSourceSettings: () -> Unit,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
) {
    val done = results.filter { it.videos.isNotEmpty() }
    // 还有来源没搜时不能说"无结果":否则用户会以为全库都没有,而其实只是还没轮到
    if (done.isEmpty() && !running && !hasMore) {
        // 2026-10-10:空态给出"终态构成"。Empty(无此片)与 Timeout/Failed(源没答话)含义不同,
        // 分开计数;超时占比高时补一句网络提示 —— 让"搜不到"可自助定性,不新增任何探测请求。
        val emptyCount = results.count { it.state == SearchViewModel.ResultState.Empty }
        // ⚠️ 刻意**不叫** timeoutCount:本函数有个同名入参(实时计数,给底部进度条用)。
        // 这里数的是"已结算结果里的超时数",是另一个量 —— 同名会把入参遮蔽掉,
        // 后来的人极易误读成"底部那个计数在这里被重算了"。
        val timeoutSettled = results.count { it.state == SearchViewModel.ResultState.Timeout }
        val failedCount = results.count { it.state == SearchViewModel.ResultState.Failed }
        val settled = emptyCount + timeoutSettled + failedCount
        val emptyLabel = if (emptyCount > 0) stringResource(R.string.search_stats_empty, emptyCount) else null
        val timeoutLabel = if (timeoutSettled > 0) stringResource(R.string.search_stats_timeout, timeoutSettled) else null
        val failedLabel = if (failedCount > 0) stringResource(R.string.search_stats_failed, failedCount) else null
        val buckets = listOfNotNull(emptyLabel, timeoutLabel, failedLabel).joinToString(" · ")
        val statsText = if (settled <= 0 || buckets.isEmpty()) {
            null
        } else {
            val line = stringResource(R.string.search_stats_line, settled, buckets)
            if (timeoutSettled >= 5 && timeoutSettled * 5 >= settled * 4) {
                line + "\n" + stringResource(R.string.search_stats_network_hint)
            } else {
                line
            }
        }
        SearchEmptyBox(
            topPad = topPad,
            text = if (matchMode == SearchSettings.MatchMode.Exact) {
                stringResource(R.string.search_no_exact_result, searchedTitle)
            } else {
                stringResource(R.string.search_no_result, searchedTitle)
            },
            statsText = statsText,
        )
        return
    }
    val railState = rememberLazyListState()
    val railResultState = rememberLazyListState()
    Column(modifier = Modifier.fillMaxSize()) {
    // 拼音查询解析成功时明示"已按某个中文片名搜索"(2026-10-11)。
    // 为什么必要:用户敲 qjyf、结果里却全是《千钧一发》之类的中文名,不解释会以为搜错了。
    // 放在结果区之上而不是塞进两个子组件 —— 两种布局(竖排/横排)都要显示,
    // 包一层就只写一次,避免又出现"一个布局有、另一个没有"的不一致
    // (同类问题本轮已在 timeoutCount 上踩过一次)。
    pinyinNotice?.let { resolved ->
        Text(
            text = stringResource(R.string.search_pinyin_resolved, resolved),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = topPad + 8.dp),
        )
    }
    // ⚠️ 提示条已经把内容顶到 top bar 之下 ⇒ 子组件**不能再加一次 topPad**,
    // 否则拼音搜索时顶部会多出约 2×topPad 的空白(提示条一次 + 列表 contentPadding 一次)。
    // 这正是"包一层"带来的连带账,必须显式还掉。
    val childTopPad = if (pinyinNotice != null) 0.dp else topPad
    AnimatedContent(
        targetState = resultLayout,
        transitionSpec = {
            val toVertical = targetState == SearchSettings.SearchLayout.Vertical
            (slideInHorizontally(spring(stiffness = Spring.StiffnessMedium)) { full ->
                if (toVertical) full / 4 else -full / 4
            } + fadeIn(spring(stiffness = Spring.StiffnessMedium))).togetherWith(
                slideOutHorizontally(spring(stiffness = Spring.StiffnessMedium)) { full ->
                    if (toVertical) -full / 4 else full / 4
                } + fadeOut(spring(stiffness = Spring.StiffnessMedium)),
            )
        },
        label = "searchResultLayout",
    ) { layout ->
        if (layout == SearchSettings.SearchLayout.Vertical) {
            RailResults(
                results = results,
                running = running,
                selectedSource = selectedSource,
                onSelectSource = onSelectSource,
                searchedTitle = searchedTitle,
                topPad = childTopPad,
                railState = railState,
                listState = railResultState,
                hasMore = hasMore,
                searchedCount = searchedCount,
                settledCount = settledCount,
                totalCount = totalCount,
                searchableSources = searchableSources,
                timeoutCount = timeoutCount,
                onLoadMore = onLoadMore,
                onSearchAll = onSearchAll,
                onOpenSourceSettings = onOpenSourceSettings,
                onCardClick = onCardClick,
                onCardLongClick = onCardLongClick,
            )
        } else {
            SearchListResults(
                done = done,
                running = running,
                selectedSource = selectedSource,
                onSelectSource = onSelectSource,
                listState = listState,
                topPad = childTopPad,
                hasMore = hasMore,
                searchedCount = searchedCount,
                settledCount = settledCount,
                totalCount = totalCount,
                searchableSources = searchableSources,
                timeoutCount = timeoutCount,
                onLoadMore = onLoadMore,
                onSearchAll = onSearchAll,
                onOpenSourceSettings = onOpenSourceSettings,
                onCardClick = onCardClick,
                onCardLongClick = onCardLongClick,
            )
        }
    }
    }
}
