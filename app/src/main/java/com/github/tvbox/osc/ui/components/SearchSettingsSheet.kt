package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.HomeSettings
import com.github.tvbox.osc.util.SearchSettings

@Composable
/** @param onSelectionChanged 勾选落盘后的通知(调用方自己去刷新搜索结果用的选择缓存) */
fun SearchSettingsSheet(onDismiss: () -> Unit, onSelectionChanged: () -> Unit) {
    val sources = remember { ApiConfig.get().getSourceBeanList().filter(SourceBean::isSearchable) }
    val allKeys = remember(sources) { sources.map { it.key }.toSet() }
    var matchMode by remember { mutableStateOf(SearchSettings.matchMode()) }
    var selected by remember {
        val selection = SearchSettings.currentSelection()
        mutableStateOf(
            when {
                selection == null -> allKeys
                selection.isEmpty() -> emptySet()
                else -> selection.filter { it in allKeys }.toSet().ifEmpty { allKeys }
            },
        )
    }

    fun applySelection(next: Set<String>) {
        selected = next
        SearchSettings.putSourcesForSearch(next)
        onSelectionChanged()
    }

    MediaBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.search_settings),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        // 固定 85% 高:标题与「全选/反选」常驻,站点列表在下方内部滚动。
        // 原来整页滚动 —— 滚到第 50 个站点时按钮已经不见了。
        maxHeightFraction = 0.85f,
        // 必须固定高度:否则 Column 是 wrap 高度,站点网格 weight(1f) 拿到 0 被压扁
        fillHeight = true,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            SettingsGroup(title = null) {
                SettingsCard(
                    position = SettingsCardPosition.SINGLE,
                    color = MaterialTheme.colorScheme.surfaceBright,
                ) {
                    // 「全部」(不过滤)已下线:会把站点按拼音/模糊返回的无关内容全收进来。
                    // 只留 智能(默认,包含匹配) / 严格(标题或别名完全相等)。
                    val modes = listOf(SearchSettings.MatchMode.Smart, SearchSettings.MatchMode.Exact)
                    val options = listOf(
                        stringResource(R.string.search_match_smart),
                        stringResource(R.string.search_match_exact),
                    )
                    val selectedIdx = modes.indexOf(matchMode).coerceAtLeast(0)
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.search_match_mode),
                        subtitle = stringResource(R.string.search_match_mode_subtitle),
                        valueText = options[selectedIdx],
                        options = options,
                        selectedIndex = selectedIdx,
                        onSelect = { idx ->
                            val mode = modes.getOrElse(idx) { SearchSettings.MatchMode.Smart }
                            matchMode = mode
                            SearchSettings.setMatchMode(mode)
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.search_sites),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (selected.isEmpty()) {
                            stringResource(R.string.search_sites_none_hint)
                        } else {
                            stringResource(R.string.search_sites_selected_hint, selected.size, allKeys.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { applySelection(allKeys) }) { Text(stringResource(R.string.common_select_all)) }
                TextButton(onClick = { applySelection(allKeys - selected) }) { Text(stringResource(R.string.common_invert_selection)) }
            }
            if (sources.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_sites_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                // 列数按窗口宽算(不再用 BoxWithConstraints:它内部是 SubcomposeLayout,
                // 在 Column + weight 的组合下会让子项拿到 0 高度)。弹层宽 = min(640dp, 屏宽)。
                val screenW = LocalConfiguration.current.screenWidthDp
                val cols = when {
                    screenW >= 720 -> 4
                    screenW >= 420 -> 3
                    else -> 2
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 8.dp),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(sources.size, key = { sources[it].key }) { i ->
                        val src = sources[i]
                        SourceCard(
                            name = src.name?.takeIf { it.isNotEmpty() } ?: src.key,
                            selected = src.key in selected,
                            onClick = {
                                applySelection(
                                    if (src.key in selected) selected - src.key else selected + src.key,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        // 网格里每格独立,不再需要左右拼接的半圆角
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(horizontal = 12.dp)
                        .size(14.dp)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
        }
    }
}
