@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.tvbox.osc.ui.activity

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.components.PressableCard
import com.github.tvbox.osc.ui.components.VodPoster
import com.github.tvbox.osc.ui.currentWindowWidthClass
import com.github.tvbox.osc.ui.WindowSize
import com.github.tvbox.osc.ui.WindowWidthClass
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.ui.theme.filterChipColors
import kotlinx.coroutines.flow.distinctUntilChanged
import com.github.tvbox.osc.util.SearchSettings
import com.github.tvbox.osc.util.SearchDedupPolicy
import com.github.tvbox.osc.util.AvailabilityHeuristic
import com.github.tvbox.osc.util.AvailabilityMemory
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.SourceIdentityMask

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryChip(
    word: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text = word,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

internal fun hideIme(activity: android.app.Activity?) {
    if (activity == null) return
    val view = activity.window?.currentFocus ?: return
    val imm = activity.getSystemService(InputMethodManager::class.java)
    imm?.hideSoftInputFromWindow(view.windowToken, 0)
}

@Composable
internal fun LayoutSwitchAction(
    selected: SearchSettings.SearchLayout,
    onSelect: (SearchSettings.SearchLayout) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Icon(
            painter = painterResource(R.drawable.ic_more_vert),
            contentDescription = stringResource(R.string.search_result_layout),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { expanded = true }
                .padding(4.dp)
                .size(22.dp),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .width(156.dp)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LayoutSwitchCard(
                    iconRes = R.drawable.ic_layout_horizontal,
                    label = stringResource(R.string.search_layout_horizontal),
                    selected = selected == SearchSettings.SearchLayout.Horizontal,
                    onClick = {
                        expanded = false
                        onSelect(SearchSettings.SearchLayout.Horizontal)
                    },
                )
                LayoutSwitchCard(
                    iconRes = R.drawable.ic_layout_vertical,
                    label = stringResource(R.string.search_layout_vertical),
                    selected = selected == SearchSettings.SearchLayout.Vertical,
                    onClick = {
                        expanded = false
                        onSelect(SearchSettings.SearchLayout.Vertical)
                    },
                )
            }
        }
    }
}

@Composable
internal fun LayoutSwitchCard(
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

internal val SearchRailWidth = 140.dp

/** 同名聚合组(P2):[key] 供 LazyColumn 稳定 Diff,[representative] 为点击打开的条目 */
private data class SearchDedupGroup(
    val key: String,
    val videos: List<Movie.Video>,
    val representative: Movie.Video,
)

@Composable
internal fun RailResults(
    results: List<SearchViewModel.SourceResult>,
    running: Boolean,
    selectedSource: String?,
    onSelectSource: (String?) -> Unit,
    searchedTitle: String,
    topPad: Dp,
    railState: LazyListState,
    listState: LazyListState,
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
    // 只有用户自己在往下滑、且快到列表尾部时才续搜:初始布局或"列表不够长"都不会自动把源搜完
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
    // 匿名标签:与 SearchListResults 同一口径(按 sourceKey 编号,不含真实站名)。
    // 竖排轨道名是独立的泄露口,编号必须跟着 sourceKey 走,否则筛选一换,序号会跳。
    // ⚠️ stringResource 必须先在 Composable 作用域求值,不能写进 remember 块(见 SearchListResults 同处注释)
    val anonPrefix = stringResource(R.string.common_source_anonymous_prefix)
    val anonymousLabelOf = remember(results, anonPrefix) {
        results.mapIndexed { index, r -> r.sourceKey to SourceIdentityMask.anonymousLabel(index, anonPrefix) }.toMap()
    }
    // 方案 B 第一层:列表阶段粗筛掉"看起来没资源"的条目(站点自述 暂无资源/未收录…),
    // 以及之前点空过、被回写标记的条目。零额外请求 —— 列表接口本来就不返回 urlBean,
    // 真正能白拿到的判定依据只有 note/state 文案,详见 AvailabilityHeuristic 的 KDoc。
    //
    // ⚠️ `remember` 的key **必须同时挂 results 和 marksRevision**。
    // 标记是在详情页写的(点空回写),写完搜索结果列表本身一个字节都没变,
    // 只挂 results 会命中旧缓存、拿不到新标记 —— 详见 AvailabilityMemory.marksRevision 的 KDoc。
    val marksRevision = AvailabilityMemory.marksRevision
    val unavailableMarks = remember(results, marksRevision) { AvailabilityMemory.activeMarks() }
    // 2026-10-09 来源标签折叠:rows 不再携带行内来源标签 —— "全部"列表里同名片来自
    // 不同源的两行原本只靠行尾"源N"标签区分,它挤占内容行还把标题下方压扁;
    // 来源信息由左栏站点列表(点击即"展开"该源的专属视图)承担,行内容完整让给标题/角标/meta。
    val rows = remember(results, selectedSource, unavailableMarks) {
        results
            .filter { it.videos.isNotEmpty() && (selectedSource == null || it.sourceKey == selectedSource) }
            .sortedBy { it.arrivedAt }
            .flatMap { result ->
                result.videos.filterNot { AvailabilityHeuristic.mightBeUnavailable(it, unavailableMarks) }
            }
    }
    // P2 同名聚合(2026-10-09):仅"全部"视图聚合;归并键=归一化标题(SearchSettings.normalizedTitle),
    // 同名不同片由身份相容性校验拦截 —— 年份/地区/类型任一"双方都确知且互相矛盾"即拆组
    // (同名翻拍/剧场版不会误并),宁漏并不误并,与可用性粗筛同一设计哲学。
    // 组键 = "grp_归一化标题#子组序",与列表位置无关:新结果并入同组不改 key,
    // LazyColumn 原地更新、滚动位置与动画稳定;无有效标题的条目退回 sourceKey+id 行键。
    // 单源视图(选中某源)不聚合 —— 该视图本身就是"展开后的来源",聚合反而多余。
    val dedupGroups: List<SearchDedupGroup> = remember(rows, selectedSource, searchedTitle) {
        val groups = if (selectedSource != null) {
            rows.map { v -> SearchDedupGroup("row_${v.sourceKey}_${v.id}", listOf(v), v) }
        } else {
            SearchDedupPolicy
                .group(rows) { v ->
                    SearchDedupPolicy.FilmIdentity(
                        titleKey = SearchSettings.normalizedTitle(v.name),
                        year = v.year,
                        area = v.area?.trim().orEmpty(),
                        type = v.type?.trim().orEmpty(),
                    )
                }
                .map { g ->
                    // 组内代表(点击打开的条目)按确定性规则选:相关度最高 → 原名等于关键词 →
                    // sourceKey/id 兜底,避免同一组两次渲染选出不同代表导致海报闪跳
                    val rep = g.members.minWith(
                        compareByDescending<Movie.Video> {
                            SearchSettings.relevanceScore(it.name, it.alias, searchedTitle)
                        }.thenByDescending { it.name?.trim() == searchedTitle }
                            .thenBy { it.sourceKey.orEmpty() }
                            .thenBy { it.id.orEmpty() },
                    )
                    val tk = SearchSettings.normalizedTitle(rep.name)
                    val key = if (tk.isEmpty()) "row_${rep.sourceKey}_${rep.id}"
                    else "grp_${tk}#${g.subIndex}"
                    SearchDedupGroup(key, g.members, rep)
                }
        }
        // 相关度排序(2026-10-10):组按代表条目相关度降序 —— 贴词正片浮前,
        // 花絮/预告类(relevanceScore 已压到 ≤2)沉后;sortedByDescending 稳定,同分保持到达序
        val intent = SearchSettings.parseIntent(searchedTitle)
        groups.sortedByDescending {
            SearchSettings.relevanceScore(
                it.representative.name, it.representative.alias, searchedTitle, it.representative.note,
            ) + SearchSettings.intentModifier(intent, it.representative.name, it.representative.year)
        }
    }
    // 这条是「搜索页粗筛到底执行没执行」的唯一可观测点(轨道视图),别删。
    // 之前这里一行日志都没有,导致 search-purge 实测 0 次也无法判断过滤是否生效。
    LaunchedEffect(rows.size, unavailableMarks.size, dedupGroups.size) {
        val raw = results.fold(0) { acc, r -> acc + r.videos.size }
        LOG.i(
            "echo-unavailable rail-filter raw=" + raw +
                " shown=" + rows.size + " groups=" + dedupGroups.size +
                " marks=" + unavailableMarks.size +
                " rev=" + marksRevision
        )
    }

    LaunchedEffect(selectedSource) {
        if (rows.isNotEmpty()) listState.scrollToItem(0)
    }

    // 2026-10-09 响应式来源栏:手机竖屏(Compact)下左侧站点栏固定 140dp 约占 1/3 屏宽,
    // 结果区被压窄到标题都截断(真机截图:meta"2026 · 中国…"被截)。Compact 时把来源栏
    // 折叠成顶部横滑筛选条(与横排视图同款交互),结果区占满全宽;Medium/Expanded(平板/
    // 横屏/TV)屏幕宽,侧栏保留 —— 侧栏能同时呈现 pending/queued/failed/timeout 状态点,
    // 是信息量更高的形态,宽屏没有理由放弃。
    val done = remember(results) { results.filter { it.videos.isNotEmpty() } }
    if (currentWindowWidthClass() == WindowWidthClass.Compact) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (done.size > 1) {
                SourceFilterChips(
                    done = done,
                    anonymousLabelOf = anonymousLabelOf,
                    selectedSource = selectedSource,
                    onSelectSource = onSelectSource,
                    topPad = topPad,
                )
            }
            RailResultList(
                listState = listState,
                groups = dedupGroups,
                running = running,
                hasMore = hasMore,
                onCardClick = onCardClick,
                onLongClick = onCardLongClick,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = if (done.size > 1) 8.dp else topPad + 8.dp,
                    bottom = 12.dp,
                ),
            )
            // 来源栏常驻底栏(2026-10-10):不随列表滚动,结果多时无需反复下滑即可续搜/调范围
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
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = railState,
                modifier = Modifier
                    .width(SearchRailWidth + 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentPadding = PaddingValues(start = 12.dp, end = 10.dp, top = topPad + 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "rail_all") {
                    SearchRailItem(
                        name = stringResource(R.string.common_all),
                        pending = running,
                        selected = selectedSource == null,
                        onClick = { onSelectSource(null) },
                    )
                }
                items(results, key = { "rail_${it.sourceKey}" }) { result ->
                    SearchRailItem(
                        name = anonymousLabelOf[result.sourceKey].orEmpty(),
                        pending = result.state == SearchViewModel.ResultState.Pending,
                        queued = result.state == SearchViewModel.ResultState.Queued,
                        failed = result.state == SearchViewModel.ResultState.Failed,
                        timeout = result.state == SearchViewModel.ResultState.Timeout,
                        selected = selectedSource == result.sourceKey,
                        onClick = { onSelectSource(result.sourceKey) },
                    )
                }
            }
            VerticalDivider(
                modifier = Modifier.padding(top = topPad + 8.dp, bottom = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
            RailResultList(
                listState = listState,
                groups = dedupGroups,
                running = running,
                hasMore = hasMore,
                onCardClick = onCardClick,
                onLongClick = onCardLongClick,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 10.dp, end = 12.dp, top = topPad + 8.dp, bottom = 12.dp),
            )
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
}

/**
 * 竖排视图的结果列表(紧凑/侧栏两形态共用):进度条 + 结果行 + 空态。
 * 来源栏(进度/勾选提示/续搜按钮)已改为常驻底栏,由 [RailResults] 放在列表之下。
 */
@Composable
private fun RailResultList(
    listState: LazyListState,
    groups: List<SearchDedupGroup>,
    running: Boolean,
    hasMore: Boolean,
    onCardClick: (Movie.Video) -> Unit,
    onLongClick: (Movie.Video) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (running) {
            item(key = "rail_progress") {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }
        }
        // 稳定 key = 组键("grp_归一化标题#子组序"/无标题回退行键):与列表位置无关,
        // 新结果并入同组只改组内容不改行身份;不同组之间键必然不同(同键身份冲突已拆子组)
        items(groups, key = { it.key }) { group ->
            SearchResultRow(
                video = group.representative,
                sourceCount = group.videos.size,
                onClick = { onCardClick(group.representative) },
                onLongClick = { onLongClick(group.representative) },
            )
        }
        if (groups.isEmpty() && !running && !hasMore) {
            item(key = "rail_empty") {
                Text(
                    text = stringResource(R.string.search_site_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                )
            }
        }
    }
}

/**
 * 紧凑屏(手机竖屏)的来源筛选条:横滑 chips(全部 + 各源匿名标签),与横排视图同款交互。
 *
 * <p>只列**有结果**的源(done):没结果的源在这里是纯噪音 —— 侧栏形态才有"哪个源挂了/
 * 还没轮到"的状态语义,compact 下退化为"能点出结果来的源"。
 */
@Composable
private fun SourceFilterChips(
    done: List<SearchViewModel.SourceResult>,
    anonymousLabelOf: Map<String, String>,
    selectedSource: String?,
    onSelectSource: (String?) -> Unit,
    topPad: Dp,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = topPad + 8.dp, bottom = 2.dp),
    ) {
        item(key = "chips_all") {
            FilterChip(
                selected = selectedSource == null,
                onClick = { onSelectSource(null) },
                label = { Text(stringResource(R.string.common_all)) },
                shape = RoundedCornerShape(20.dp),
                colors = MaterialTheme.colorScheme.filterChipColors(),
            )
        }
        items(done, key = { "chips_${it.sourceKey}" }) { result ->
            FilterChip(
                selected = selectedSource == result.sourceKey,
                onClick = { onSelectSource(result.sourceKey) },
                label = { Text(anonymousLabelOf[result.sourceKey].orEmpty()) },
                shape = RoundedCornerShape(20.dp),
                colors = MaterialTheme.colorScheme.filterChipColors(),
            )
        }
    }
}

/**
 * 站点栏里的一项(2026-10-08 起带终态语义)。
 *
 * @param pending 正在搜(转圈)
 * @param queued 还没轮到搜(压暗)
 * @param failed 源请求失败/解析失败 —— 与"搜完没这部片"是两回事,前者不该让用户以为换源没用
 * @param timeout 等满单源限时仍无回包
 */
@Composable
internal fun SearchRailItem(
    name: String,
    pending: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    queued: Boolean = false,
    failed: Boolean = false,
    timeout: Boolean = false,
) {
    val unavailable = failed || timeout
    val contentColor = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        // 失败/超时:标成 error色,让"这个源挂了"与"这个源没有这部片"在站点栏上一眼可分
        unavailable -> MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
        // 还没轮到搜的源压暗显示:与"正在搜"的转圈区分开,站点栏才不会几十个一起转
        queued -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (pending) {
                Spacer(modifier = Modifier.width(6.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = contentColor.copy(alpha = 0.6f),
                    strokeWidth = 1.5.dp,
                )
            } else if (unavailable) {
                // 不用图标字体:一个小圆点即可,避免为一处状态引入新的资源依赖
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.8f)),
                )
            }
        }
    }
}

/**
 * 来源栏常驻底栏(2026-10-10):进度 + 勾选范围提示 + 续搜入口固定在屏幕底部,
 * 不随列表内容滚动 —— 结果多时用户无需反复下滑即可随时续搜/调整范围。
 *
 * <p>这个入口是"按需续搜"的必要条件 —— 只靠滚动触发,用户不会知道下面还有没搜的来源,
 * 空结果时更会误以为全库都没有。
 *
 * <p>2026-10-08:进度文案在"搜完了多少"与"还剩多少"之间切换。此前只显示 [searchedCount],
 * 而它在批次启动时就 +N(是"已发起"),于是 24 个源刚发出去就显示"已搜索 24/300",
 * 其中几十个还在转圈 —— 数字很大但结果没出来,观感上就是"搜不动了"。
 * 现在有终态计数 [settledCount] 后,搜索进行中显示实际完成的条数。
 */
@Composable
internal fun SearchLoadMoreFooter(
    hasMore: Boolean,
    running: Boolean,
    searchedCount: Int,
    settledCount: Int,
    totalCount: Int,
    searchableSources: Int = 0,
    onLoadMore: () -> Unit,
    onSearchAll: (() -> Unit)? = null,
    onOpenSourceSettings: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = when {
                    !hasMore -> stringResource(R.string.search_all_sources_done, totalCount)
                    // 搜索中:数字与站点栏里不再转圈的源数一致
                    running -> stringResource(R.string.search_progress_settled, settledCount, totalCount)
                    else -> stringResource(R.string.search_progress, searchedCount, totalCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 防静默收窄(2026-10-09):勾选数 < 可搜总数时明示比例,点此直达搜索设置
            if (searchableSources > totalCount && totalCount > 0) {
                Text(
                    text = stringResource(R.string.search_scope_narrow_hint, totalCount, searchableSources),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    maxLines = 1,
                    modifier = Modifier
                        .let { m -> if (onOpenSourceSettings != null) m.clickable { onOpenSourceSettings() } else m },
                )
            }
        }
        if (hasMore) {
            // 两个续搜入口并排同权:左边按批续(默认节流),右边一次性搜完全部剩余源
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.search_load_more), maxLines = 1)
                }
                if (onSearchAll != null) {
                    OutlinedButton(
                        onClick = onSearchAll,
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.search_load_all), maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * 搜索结果行(2026-10-09 重排):海报 + 标题(两行) + 内容角标 + 元信息,**不放来源标签**。
 *
 * <p>来源标签默认"折叠":用户通常不关心结果来自哪个源,行内标签却把标题下方一行挤占、
 * 长源名还会把角标/元信息压到截断。来源信息的"展开"入口由既有的来源筛选承担 ——
 * 竖排视图是左栏站点列表(点击即展开该源专属视图),横排视图是顶部筛选 chips,
 * 分组页"全部>"则是整源展开。行内不再重复展示。
 *
 * <p>内容行结构(自上而下):
 * 1. 标题(最多两行,省略结尾)—— 番号标题普遍偏长;
 * 2. 内容角标(中文字幕/无码影片…,主色胶囊)—— 与来源无关的内容信息,保留突出;
 * 3. 元信息(年份/地区/类型)—— 一行完整显示,不再被来源标签挤占。
 */
@Composable
internal fun SearchResultRow(
    video: Movie.Video,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    sourceCount: Int = 1,
) {
    PressableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.cardContainer)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 海报自带圆角并留出内边距:不裁的话左侧跟随卡片圆角、右侧是硬直角,看着像被切了一半。
            VodPoster(
                name = video.name,
                pic = video.pic,
                modifier = Modifier
                    .width(72.dp)
                    .height(96.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 4.dp),
            ) {
                // 番号标题普遍偏长:一行必然截断,放到两行再省略。
                Text(
                    text = video.name ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val note = video.note?.trim().orEmpty()
                if (note.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    // 内容角标(中文字幕/无码影片…)用主色胶囊突出,一眼能分辨。
                    SearchTag(text = note, emphasized = true)
                }
                val meta = searchMetaText(video)
                if (meta.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // P2 同名聚合角标:该标题共有 N 个来源可用(N>1 才显示)。
            // 点击行为与整卡一致(打开代表条目,换源链兜底);信息是"可用源数"而非源身份。
            if (sourceCount > 1) {
                SearchTag(
                    text = stringResource(R.string.search_result_source_count, sourceCount),
                    emphasized = false,
                    modifier = Modifier.padding(end = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchTag(
    text: String,
    emphasized: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (emphasized) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (emphasized) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                }
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

internal fun searchMetaText(video: Movie.Video): String = listOfNotNull(
    video.year.takeIf { it > 0 }?.toString(),
    video.area?.takeIf { it.isNotBlank() },
    video.type?.takeIf { it.isNotBlank() },
).joinToString(" · ")
