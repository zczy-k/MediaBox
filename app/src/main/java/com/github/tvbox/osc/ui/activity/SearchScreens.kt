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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
import com.github.tvbox.osc.ui.theme.cardContainer
import kotlinx.coroutines.flow.distinctUntilChanged
import com.github.tvbox.osc.util.SearchSettings
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

@Composable
internal fun RailResults(
    results: List<SearchViewModel.SourceResult>,
    running: Boolean,
    selectedSource: String?,
    onSelectSource: (String?) -> Unit,
    topPad: Dp,
    railState: LazyListState,
    listState: LazyListState,
    hasMore: Boolean,
    searchedCount: Int,
    totalCount: Int,
    onLoadMore: () -> Unit,
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
    // 行内 siteName 也走这里 —— 竖排轨道名 + 行内标注是两个独立的泄露口,必须一起封。
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
    val rows = remember(results, selectedSource, unavailableMarks) {
        results
            .filter { it.videos.isNotEmpty() && (selectedSource == null || it.sourceKey == selectedSource) }
            .sortedBy { it.arrivedAt }
            .flatMap { result ->
                result.videos
                    .filterNot { AvailabilityHeuristic.mightBeUnavailable(it, unavailableMarks) }
                    .map { (anonymousLabelOf[result.sourceKey].orEmpty()) to it }
            }
    }
    // 这条是「搜索页粗筛到底执行没执行」的唯一可观测点(轨道视图),别删。
    // 之前这里一行日志都没有,导致 search-purge 实测 0 次也无法判断过滤是否生效。
    LaunchedEffect(rows.size, unavailableMarks.size) {
        val raw = results.fold(0) { acc, r -> acc + r.videos.size }
        LOG.i(
            "echo-unavailable rail-filter raw=" + raw +
                " shown=" + rows.size + " marks=" + unavailableMarks.size +
                " rev=" + marksRevision
        )
    }

    LaunchedEffect(selectedSource) {
        if (rows.isNotEmpty()) listState.scrollToItem(0)
    }

    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = railState,
            modifier = Modifier.width(SearchRailWidth),
            contentPadding = PaddingValues(start = 12.dp, end = 10.dp, top = topPad + 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                    selected = selectedSource == result.sourceKey,
                    onClick = { onSelectSource(result.sourceKey) },
                )
            }
        }
        VerticalDivider(
            modifier = Modifier.padding(top = topPad + 8.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 10.dp, end = 12.dp, top = topPad + 8.dp, bottom = 12.dp),
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
            itemsIndexed(rows, key = { index, (_, video) -> "rail_row_${index}_${video.sourceKey}_${video.id}" }) { _, (siteName, video) ->
                SearchResultRow(
                    video = video,
                    siteName = siteName.takeIf { selectedSource == null },
                    onClick = { onCardClick(video) },
                    onLongClick = { onCardLongClick(video) },
                )
            }
            if (rows.isEmpty() && !running && !hasMore) {
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
            item(key = "rail_more") {
                SearchLoadMoreFooter(
                    hasMore = hasMore,
                    running = running,
                    searchedCount = searchedCount,
                    totalCount = totalCount,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

@Composable
internal fun SearchRailItem(
    name: String,
    pending: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    queued: Boolean = false,
) {
    val contentColor = when {
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        // 还没轮到搜的源压暗显示:与"正在搜"的转圈区分开,站点栏才不会几十个一起转
        queued -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceBright,
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
            }
        }
    }
}

/**
 * 分批搜索的尾部控件:还有来源没搜就给出入口与进度,全搜完则明确收口。
 *
 * <p>这个入口是"按需续搜"的必要条件 —— 只靠滚动触发,用户不会知道下面还有没搜的来源,
 * 空结果时更会误以为全库都没有。
 */
@Composable
internal fun SearchLoadMoreFooter(
    hasMore: Boolean,
    running: Boolean,
    searchedCount: Int,
    totalCount: Int,
    onLoadMore: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (hasMore) {
                stringResource(R.string.search_progress, searchedCount, totalCount)
            } else {
                stringResource(R.string.search_all_sources_done, totalCount)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (hasMore) {
            OutlinedButton(onClick = onLoadMore, enabled = !running) {
                Text(stringResource(R.string.search_load_more))
            }
        }
    }
}

@Composable
internal fun SearchResultRow(
    video: Movie.Video,
    siteName: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
                if (note.isNotEmpty() || siteName != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 内容角标(中文字幕/无码影片…)用主色胶囊突出,一眼能分辨。
                        if (note.isNotEmpty()) SearchTag(text = note, emphasized = true)
                        if (siteName != null) {
                            // 来源名按剩余宽度自适应:原来写死 90dp,长名字必被截成 "missav_tvbo..."
                            SearchTag(
                                text = siteName,
                                emphasized = false,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
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
