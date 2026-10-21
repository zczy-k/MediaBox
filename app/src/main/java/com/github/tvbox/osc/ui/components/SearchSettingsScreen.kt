package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.SearchSettings

/**
 * 「搜索设置」整页 —— 2026-10-21 从首页三点菜单的弹层搬来。
 *
 * <p>为什么搬成整页:它是**低频配置**(选 308 个站点、改匹配严格度),用户一个月碰不到一次;
 * 而首页三点菜单是高频入口。搬走后首页那颗三点按钮整个删掉 —— 一个只剩一项的菜单不值得占位。
 *
 * <p>布局:整页一个 [LazyVerticalGrid],头部(匹配模式卡 + 站点标题行)用
 * `GridItemSpan(maxLineSpan)` 占满整行,站点卡片跟在后面按列排。
 * **不再用 weight(1f) / 固定高度** —— 整页滚动天然有确定高度,不会出现"网格 0 高度"。
 */
@Composable
fun SearchSettingsScreen(onNavigateBack: () -> Unit) {
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

    val cfg = LocalConfiguration.current
    val cols = when {
        cfg.screenWidthDp >= 720 -> 4
        cfg.screenWidthDp >= 420 -> 3
        else -> 2
    }

    // 调试日志(echo- 前缀兜底落盘):记录数据规模与列数,便于"站点不显示"类问题定位
    LaunchedEffect(Unit) {
        LOG.i(
            "echo-searchsettings enter sources=${sources.size} keys=${allKeys.size} " +
                "sel=${selected.size} screenW=${cfg.screenWidthDp} cols=$cols",
        )
    }

    fun applySelection(next: Set<String>) {
        selected = next
        SearchSettings.putSourcesForSearch(next)
        com.github.tvbox.osc.ui.activity.SearchViewModel.loadCheckedSources()
    }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.search_settings),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(
                R.drawable.ic_arrow_left,
                stringResource(R.string.common_back),
                onClick = onNavigateBack,
            )
        },
    ) { topPad, navBottom ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = topPad + 8.dp,
                bottom = navBottom + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ① 匹配模式(整行)
            item(key = "match", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    SettingsGroup(title = null) {
                        SettingsCard(
                            position = SettingsCardPosition.SINGLE,
                            color = MaterialTheme.colorScheme.surfaceBright,
                        ) {
                            // 「全部」(不过滤)已下线:会把站点按拼音/模糊返回的无关内容全收进来。
                            // 只留 智能(默认,包含匹配) / 严格(标题或别名完全相等)。
                            val modes = listOf(
                                SearchSettings.MatchMode.Smart,
                                SearchSettings.MatchMode.Exact,
                            )
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
                    // ② 站点标题 + 全选/反选(整行)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 4.dp),
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
                                    stringResource(
                                        R.string.search_sites_selected_hint,
                                        selected.size,
                                        allKeys.size,
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { applySelection(allKeys) }) {
                            Text(stringResource(R.string.common_select_all))
                        }
                        TextButton(onClick = { applySelection(allKeys - selected) }) {
                            Text(stringResource(R.string.common_invert_selection))
                        }
                    }
                }
            }

            // ③ 站点卡片(按列排)
            if (sources.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.search_sites_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(sources.size, key = { sources[it].key }) { i ->
                    if (i == 0) LOG.i("echo-searchsettings item0 rendered key=${sources[0].key}")
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

@Composable
private fun SourceCard(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
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
