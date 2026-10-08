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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
    val totalCount by vm.totalCount.collectAsStateWithLifecycle()
    val hotSearch by vm.hotSearch.collectAsStateWithLifecycle()
    val suggest by vm.suggest.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(KV.get(HawkConfig.SEARCH_HISTORY, ArrayList<String>())) }
    val searchedTitle by vm.searchedTitle.collectAsStateWithLifecycle()
    val matchMode by vm.matchMode.collectAsStateWithLifecycle()
    val sitesEmpty by vm.sitesEmpty.collectAsStateWithLifecycle()
    val vodMenu = rememberVodCardMenuState()
    var resultLayout by remember { mutableStateOf(SearchSettings.resultLayout()) }

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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp),
                trailing = {
                    LayoutSwitchAction(
                        selected = resultLayout,
                        onSelect = {
                            resultLayout = it
                            SearchSettings.setResultLayout(it)
                        },
                    )
                },
            )
        },
        navigationIcon = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
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
                onLoadMore = { vm.loadNextBatch() },
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
private fun SearchEmptyBox(topPad: Dp, text: String) {
    LoadStateBox(
        state = LoadState.Empty,
        emptyText = text,
        errorText = "",
        retryText = "",
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
    onLoadMore: () -> Unit,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
) {
    val done = results.filter { it.videos.isNotEmpty() }
    // 还有来源没搜时不能说"无结果":否则用户会以为全库都没有,而其实只是还没轮到
    if (done.isEmpty() && !running && !hasMore) {
        SearchEmptyBox(
            topPad = topPad,
            text = if (matchMode == SearchSettings.MatchMode.Exact) {
                stringResource(R.string.search_no_exact_result, searchedTitle)
            } else {
                stringResource(R.string.search_no_result, searchedTitle)
            },
        )
        return
    }
    val railState = rememberLazyListState()
    val railResultState = rememberLazyListState()
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
                topPad = topPad,
                railState = railState,
                listState = railResultState,
                hasMore = hasMore,
                searchedCount = searchedCount,
                settledCount = settledCount,
                totalCount = totalCount,
                onLoadMore = onLoadMore,
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
                topPad = topPad,
                hasMore = hasMore,
                searchedCount = searchedCount,
                settledCount = settledCount,
                totalCount = totalCount,
                onLoadMore = onLoadMore,
                onCardClick = onCardClick,
                onCardLongClick = onCardLongClick,
            )
        }
    }
}
