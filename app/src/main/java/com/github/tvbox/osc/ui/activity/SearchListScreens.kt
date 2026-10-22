package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.github.tvbox.osc.ui.components.VodPoster
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
    timeoutCount: Int = 0,
    onLoadMore: () -> Unit,
    onSearchAll: () -> Unit,
    onOpenSourceSettings: () -> Unit,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
    /** 点缩略图看原图;null ⇒ 点图等价于点整行(播放),保证任何一处漏传都不会出现死区 */
    onPosterClick: ((Movie.Video) -> Unit)? = null,
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
        // 2026-10-22 闪退止血:pinyinNotice 弹出时 topPad 为 0,0-4=-4dp ⇒ PaddingValues 负值
        // IllegalArgumentException(真机崩溃栈实锤)。下限保护必须有。
        contentPadding = PaddingValues(top = (topPad - 4.dp).coerceAtLeast(0.dp), bottom = 12.dp),
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
                // 每个源下面用**紧凑行**而不是海报卡片:搜索结果是一张"清单",
                // 用户在这里扫的是片名,不是海报。66×42 缩略图只用来确认"是不是这部"。
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    result.videos
                        .filterNot { AvailabilityHeuristic.mightBeUnavailable(it, unavailableMarks) }
                        .forEach { video ->
                            SearchResultRow(
                                video = video,
                                // 真实来源站名:仅"全部"视图标来源,且**只有多站源才标**。
                                // ⚠️ 不能直接用 result.sourceName —— 那是**源名**("sextb_tvbox" 这种打包名),
                                // 与条目来自源里哪个网站无关;一站多网的源会让几个站的结果全写成同一个名字。
                                // ⚠️ 单站源**一条都不标**(用户 2026-10-22 明确要求):单站源的源名就是站名,
                                // 在首页已经露过一遍,搜索列表再标是纯噪音。
                                siteName = if (selectedSource == null) {
                                    sourceLabelOf(video, result.isMultiSite)
                                } else {
                                    null
                                },
                                onClick = { onCardClick(video) },
                                onLongClick = { onCardLongClick(video) },
                                onPosterClick = onPosterClick?.let { cb -> { cb(video) } },
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
        timeoutCount = timeoutCount,
        onLoadMore = onLoadMore,
        onSearchAll = onSearchAll,
        onOpenSourceSettings = onOpenSourceSettings,
    )
    }
}

/**
 * 搜索结果行 —— 紧凑清单样式。
 *
 * <p>固定画框 66×44 缩略图 + 两行标题 + 一行"站名/备注/元信息"。
 * 为什么不用海报卡:搜索页用户是在**扫片名**,海报卡(110dp 宽、2:3)一屏只放得下 3 个,
 * 行样式能放 8 个以上。
 *
 * <p>**双热区**(2026-10-22):点缩略图看原图,点文字进详情播放。
 * 缩略图自带 clickable,子节点先消费事件 ⇒ 点图不会冒泡成"播放"。
 */
@Composable
private fun SearchResultRow(
    video: com.github.tvbox.osc.bean.Movie.Video,
    siteName: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPosterClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // ⚠️ 必须是 combinedClickable,不能是 clickable(2026-10-22):
            // 本函数一直声明了 onLongClick 参数,但外层只挂了 `.clickable(onClick)`,
            // 于是**手机列表里长按结果行完全没反应** —— 换源/收藏菜单根本打不开。
            // 这是既有缺陷,顺手在这里修掉(横排行一直是好的,只有手机行漏了)。
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 缩略图:固定画框 + 三层结构 + 双热区,详见 SearchThumb 的 KDoc
        SearchThumb(
            video = video,
            onPlay = onClick,
            onPosterClick = onPosterClick,
            onLongClick = onLongClick,
        )
        Spacer(modifier = Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.name ?: "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                // ⚠️ **两行**,不是一行(2026-10-22 用户报"卡片显示不全")。
                // 番号类标题普遍偏长:`ymdd-370-rm [Reducing Mosaic] St...` /
                // `YMDD-370 温泉频道【温泉女子】被...` —— 单行只剩个前缀,
                // 用户看不出这是哪一部。单行时代码与 SearchScreens 里那段 KDoc
                // ("标题最多两行,番号标题普遍偏长")本来就是矛盾的,这里对齐 KDoc。
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // 这条是「标题到底有没有被截断」的唯一可观测点:只在**真的溢出**时打,
                // 否则每帧刷屏。排查"卡片显示不全"时先看有没有这行日志。
                onTextLayout = { result ->
                    if (result.didOverflowHeight) {
                        com.github.tvbox.osc.util.LOG.i(
                            "echo-searchrow title-overflow name-len=" + (video.name?.length ?: 0) +
                                " lines=" + result.lineCount +
                                " h=" + result.size.height,
                        )
                    }
                },
            )
            val meta = listOfNotNull(
                if (video.year > 0) video.year.toString() else null,
                video.type?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            // 站名 / 备注正文 / 元信息**合并成一行**。
            //
            // ⚠️ 备注必须用 noteBodyOf 而不是 video.note:爬虫写的原文是
            // `"123AV · 3:21:59"` 这种"站名 · 备注",站名已由左边那枚标签画过一遍,
            // 再整条原样显示就会看到 `123AV` 出现两次(真机截图里就是这样)。
            //
            // 为什么不再给备注单独一行:单行标题时站名曾独占一行,叠成"标题 / 站名"两截、
            // 右边大片空白,视觉上就像卡片没画完 —— 用户 2026-10-22 报的"卡片显示不全"。
            val noteBody = noteBodyOf(video)
            val info = listOfNotNull(
                noteBody.takeIf { it.isNotEmpty() },
                meta.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            if (siteName != null || info.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 来源站名标签:仅"全部"视图、且仅**多站源**传入(见 sourceLabelOf)。
                    // 站名短(2~12 字符),按自身宽度占位,不参与权重分配 ——
                    // 空间不够时让右边那行文字截断,站名不能被挤成半截。
                    siteName?.let { site ->
                        SearchTag(text = site, emphasized = false)
                        if (info.isNotEmpty()) Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (info.isNotEmpty()) {
                        Text(
                            text = info,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
        com.github.tvbox.osc.ui.components.ratingBadgeText(video.note)?.let { score ->
            Text(
                text = score,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}
