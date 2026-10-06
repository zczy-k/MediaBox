package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.components.VodCard
import com.github.tvbox.osc.ui.page.openVodCardOrDetail
import com.github.tvbox.osc.ui.theme.filterChipColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 分区标题前的裸图标(22dp、onSurface 着色):画稿图标与内置图标共用 */
@Composable
internal fun SectionTitleIcon(painter: Painter) {
    Icon(
        painter = painter,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
internal fun SectionTitleIcon(imageVector: ImageVector) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(22.dp),
    )
}

/**
 * 详情页的换源分区。
 *
 * <p>⚠️ **这里绝不显示任何真实站名**。产品要求:任何情况下用户都不能看到 App 有哪些源。
 * 以前这里直接把 `SourceChip.name`(= `ApiConfig.getSource(key).name`)渲染成 FilterChip 标签,
 * 于是空态"暂无片源"下方会冒出一排真实站名 —— 这就是被用户截图拍到的泄露。
 * 现在标签一律由 [DetailViewModel.sourceLabelFor] 按**下标**生成(`源 1`/`源 2`…),
 * 与站名无关;`currentSourceName` 参数已彻底删除(唯一调用方本来就传 null,留着就是定时炸弹)。
 *
 * <p>候选 chip 仍然可点,点走的是 [DetailViewModel.switchSource] —— 手动换源的能力保留,
 * 只是不再告诉用户"你正在换到哪个站"。换源全自动化下这才是正确形态:
 * 提示既刷屏又泄露身份(与 [DetailViewModel.stopPlaybackForSwitch] 静默换源同一口径)。
 */
@Composable
internal fun SourceSection(vm: DetailViewModel, revision: Int) {
    @Suppress("UNUSED_EXPRESSION") revision
    val sourceChips by vm.sourceChips.collectAsStateWithLifecycle()
    val sourcesSearching by vm.sourcesSearching.collectAsStateWithLifecycle()
    // 搜索中且还没有候选时,整行不渲染 —— 过去这里会先画出标题 + 空的换源行,
    // 正是截图里"暂无片源下面挂着一个孤零零的换源"的成因。
    if (sourceChips.isEmpty()) return
    val listState = rememberLazyListState()
    LaunchedEffect(sourceChips.size) {
        if (sourceChips.isNotEmpty()) listState.scrollToItem(0)
    }
    Column(
        modifier = Modifier
            .padding(start = 6.dp, end = 6.dp, top = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_switch_source))
            Text(
                text = vm.sourceSectionTitle(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            if (sourcesSearching) {
                Text(
                    text = stringResource(R.string.detail_finding_source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 标签只由下标生成(源 1 / 源 2 …),**不含真实站名** —— 见本函数 KDoc
            itemsIndexed(sourceChips, key = { _, c -> c.key }) { index, chip ->
                val label = vm.sourceLabelFor(index)
                if (label.isEmpty()) return@itemsIndexed
                FilterChip(
                    selected = false,
                    onClick = { vm.candidateForKey(chip.key)?.let { vm.switchSource(it) } },
                    label = { Text(label) },
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
internal fun RelatedSection(
    activity: DetailActivity,
    vm: DetailViewModel,
    onCardLongClick: (Movie.Video) -> Unit = {},
) {
    val relatedVideos by vm.relatedVideos.collectAsStateWithLifecycle()
    if (relatedVideos.isEmpty()) return
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_recommend))
            Text(
                text = stringResource(R.string.detail_recommend),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                relatedVideos,
                key = { _, v -> (v.sourceKey ?: "") + "|" + (v.id ?: "") },
            ) { _, video ->
                VodCard(
                    video = video,
                    onClick = { activity.openVodCardOrDetail(video) },
                    onLongClick = { onCardLongClick(video) },
                    modifier = Modifier.width(110.dp),
                )
            }
        }
    }
}

private val CR_LINK_REGEX = Regex("\\[a=cr:(?:\\{.*?\\}|\\[.*?\\])/](.*?)\\[/a]")
private val WHITESPACE_REGEX = Regex("\\s")

internal fun removeHtmlTag(info: String?): String {
    if (info.isNullOrEmpty()) return ""
    var text = info.replace(CR_LINK_REGEX, "$1")
    text = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    return text.replace(WHITESPACE_REGEX, "")
}
