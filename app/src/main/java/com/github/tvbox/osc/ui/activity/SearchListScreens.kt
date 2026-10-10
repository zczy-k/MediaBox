package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.components.VodCard
import com.github.tvbox.osc.ui.theme.filterChipColors
import com.github.tvbox.osc.util.AvailabilityHeuristic
import com.github.tvbox.osc.util.AvailabilityMemory
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.SourceIdentityMask
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun SearchListResults(
    done: List<SearchViewModel.SourceResult>,
    running: Boolean,
    selectedSource: String?,
    onSelectSource: (String?) -> Unit,
    listState: LazyListState,
    topPad: Dp,
    hasMore: Boolean,
    searchedCount: Int,
    settledCount: Int,
    totalCount: Int,
    searchableSources: Int,
    onLoadMore: () -> Unit,
    onSearchAll: () -> Unit,
    onOpenSourceSettings: () -> Unit,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
) {
    val context = LocalContext.current
    // 只有用户自己在往下滑、且快到列表尾部时才续搜(与竖排列表同口径)
    LaunchedEffect(listState, hasMore) {
        snapshotFlow {
            SearchBatchPolicy.shouldLoadMoreOnScroll(
                isScrollInProgress = listState.isScrollInProgress,
                lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                totalItemCount = listState.layoutInfo.totalItemsCount,
                hasMore = hasMore,
                running = running,
            )
        }.distinctUntilChanged().collect { if (it) onLoadMore() }
    }
    val shown = if (selectedSource == null) done else done.filter { it.sourceKey == selectedSource }
    // 方案 B 第一层:列表阶段粗筛(详见 AvailabilityHeuristic 的 KDoc)。
    // 零额外请求 —— 列表接口不返回 urlBean,能白拿到的判定依据只有 note/state 文案。
    //
    // ⚠️ `remember` 的 key **必须同时挂 done 和 marksRevision**。
    // 标记是在详情页写的(点空回写),写完搜索结果列表本身一个字节都没变,
    // 只挂 done 会命中旧缓存、拿不到新标记 —— 详见 AvailabilityMemory.marksRevision 的 KDoc。
    val marksRevision = AvailabilityMemory.marksRevision
    val unavailableMarks = remember(done, marksRevision) { AvailabilityMemory.activeMarks() }
    // ⚠️ 匿名标签:按**筛选前的稳定顺序**编号,而不是用真实站名。
    // 真实站名一旦渲染出来(标题行/筛选 chip/分区页大标题),就等于把 App 的源清单摊给用户。
    // 编号必须跟着 sourceKey 走,否则筛选一换,序号会跳。
    // ⚠️ stringResource 是 @Composable,必须**先在 Composable 作用域求值**再传进 remember ——
    // 直接写 remember { stringResource(...) } 编译不过("@Composable invocations can only
    // happen from the context of a @Composable function",v1.0.24 构建 37492586746 踩过)。
    val anonPrefix = stringResource(R.string.common_source_anonymous_prefix)
    val anonymousLabelOf = remember(done, anonPrefix) {
        done.mapIndexed { index, r -> r.sourceKey to SourceIdentityMask.anonymousLabel(index, anonPrefix) }.toMap()
    }
    // 这条是「搜索页粗筛到底执行没执行」的唯一可观测点(分组视图),别删。
    // 与轨道视图(SearchScreens.rail-filter)同口径:两个视图都要能独立判断过滤有没有跑。
    // ⚠️ 口径必须与上面那个 remember 完全一致(同一份 marks、同一个 key),
    // 否则两个视图会给出不同的 raw/shown,对不上就说明其中一个没生效。
    // ⚠️ 用 fold(0) 而不是 sumOf —— 全工程统一口径,Kotlin 版本的 stdlib 差异不碰。
    val shownTotal = remember(shown, unavailableMarks) {
        shown.fold(0) { acc, r ->
            acc + r.videos.count { !AvailabilityHeuristic.mightBeUnavailable(it, unavailableMarks) }
        }
    }
    val rawTotal = remember(shown) { shown.fold(0) { acc, r -> acc + r.videos.size } }
    LaunchedEffect(rawTotal, shownTotal, unavailableMarks.size) {
        LOG.i(
            "echo-unavailable group-filter raw=" + rawTotal +
                " shown=" + shownTotal + " marks=" + unavailableMarks.size +
                " rev=" + marksRevision
        )
    }
    // 来源栏常驻底栏(2026-10-10):列表占 weight(1f),来源栏固定在屏幕底部不随滚动
    Column(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(top = topPad - 4.dp, bottom = 12.dp),
    ) {
        if (running || done.size > 1) {
            item(key = "search_leading") {
                Column {
                    if (running) {
                        LinearWavyProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    if (done.size > 1) {
                        LazyRow(
                            modifier = Modifier.padding(top = if (running) 0.dp else 12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "filter_all") {
                                FilterChip(
                                    selected = selectedSource == null,
                                    onClick = { onSelectSource(null) },
                                    label = { Text(stringResource(R.string.common_all)) },
                                    shape = RoundedCornerShape(20.dp),
                                    colors = MaterialTheme.colorScheme.filterChipColors(),
                                )
                            }
                            items(done, key = { "filter_${it.sourceKey}" }) { result ->
                                FilterChip(
                                    selected = selectedSource == result.sourceKey,
                                    onClick = {
                                        onSelectSource(
                                            if (selectedSource == result.sourceKey) null else result.sourceKey,
                                        )
                                    },
                                    label = { Text(anonymousLabelOf[result.sourceKey].orEmpty()) },
                                    shape = RoundedCornerShape(20.dp),
                                    colors = MaterialTheme.colorScheme.filterChipColors(),
                                )
                            }
                        }
                    }
                }
            }
        }
        itemsIndexed(shown, key = { _, r -> r.sourceKey }) { index, result ->
            Column(modifier = Modifier.padding(top = if (index == 0) 12.dp else 24.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = anonymousLabelOf[result.sourceKey].orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                            .clickable {
                                // 传匿名标签而非真实站名:这个标题会渲染成分区列表页的大标题,
                                // 传站名等于从二级页面再泄露一次(同一个词的第二个出口)。
                                PartitionListActivity.startForSearch(
                                    context,
                                    result.videos,
                                    anonymousLabelOf[result.sourceKey].orEmpty(),
                                )
                            }
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
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 方案 B 第一层:与 SearchTracks 同一口径粗筛(见 AvailabilityHeuristic)
                    itemsIndexed(
                        result.videos.filterNot {
                            AvailabilityHeuristic.mightBeUnavailable(it, unavailableMarks)
                        }
                    ) { _, video ->
                        VodCard(
                            video = video,
                            onClick = { onCardClick(video) },
                            onLongClick = { onCardLongClick(video) },
                            modifier = Modifier.width(110.dp),
                        )
                    }
                }
            }
        }
    }
    SearchLoadMoreFooter(
        hasMore = hasMore,
        running = running,
        searchedCount = searchedCount,
        settledCount = settledCount,
        totalCount = totalCount,
        searchableSources = searchableSources,
        onLoadMore = onLoadMore,
        onSearchAll = onSearchAll,
        onOpenSourceSettings = onOpenSourceSettings,
    )
    }
}
