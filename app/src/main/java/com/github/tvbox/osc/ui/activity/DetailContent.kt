package com.github.tvbox.osc.ui.activity

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.components.VodPoster
import com.github.tvbox.osc.ui.theme.Fluid
import com.github.tvbox.osc.ui.theme.filterChipColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun DetailContent(
    activity: DetailActivity,
    vm: DetailViewModel,
    revision: Int,
    onCardLongClick: (Movie.Video) -> Unit,
) {
    val info = vm.vodInfo ?: return
    @Suppress("UNUSED_EXPRESSION") revision

    val currentFlag = info.playFlag
    val episodes = info.seriesMap?.get(currentFlag).orEmpty()
    val playIndex = info.playIndex
    val qualityOptions by vm.qualityOptions.collectAsStateWithLifecycle()
    val qualitySelected by vm.qualitySelected.collectAsStateWithLifecycle()
    val collected by vm.collected.collectAsStateWithLifecycle()
    var descExpanded by rememberSaveable { mutableStateOf(false) }
    var titleExpanded by rememberSaveable(info.id) { mutableStateOf(false) }
    var titleOverflow by remember(info.id) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item(key = "header") {
            // 走 cleanVodDescription 而不是 removeHtmlTag:后者只去 HTML 标签,
            // 站点堆在简介开头的推广群/链接/分隔线会原样留在界面上(见该函数 KDoc)
            val desc = remember(info.des) { cleanVodDescription(info.des) }
            Column {
                // ── 标题区 ────────────────────────────────────────────────
                // 剧照已经退到整页背景(见 DetailScreen 的背景层),这里不再放 268dp 大图:
                // 顶部是播放器,再压一张大图会互相抢重心,而且把标题挤到第二屏。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp),
                ) {
                    Text(
                        text = info.name ?: "TVBox",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (titleExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { titleOverflow = it.hasVisualOverflow },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = titleOverflow || titleExpanded) {
                                titleExpanded = !titleExpanded
                            },
                    )
                    if (titleOverflow || titleExpanded) {
                        Text(
                            text = stringResource(if (titleExpanded) R.string.detail_collapse else R.string.detail_expand),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { titleExpanded = !titleExpanded }
                                .padding(vertical = 2.dp),
                        )
                    }
                    val metaParts = listOfNotNull(
                        if (info.year > 0) info.year.toString() else null,
                        info.area?.takeIf { it.isNotBlank() },
                        info.type?.takeIf { it.isNotBlank() },
                    )
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                // ── 操作条:按可用宽度决定放几个入口 ───────────────────────
                // 槽位由 Fluid.detailActionSlots 算:360dp 只放得下 3 个(第 3 个是「⋯」),
                // 412dp 起四个全平铺。详见 DetailActions.kt 的 KDoc。
                val windowWidth = LocalConfiguration.current.screenWidthDp.dp
                DetailActionRow(
                    availWidth = windowWidth - Fluid.padding(windowWidth) * 2,
                    windowWidth = windowWidth,
                    primaryText = stringResource(R.string.common_play) + " · " +
                        stringResource(R.string.detail_episodes),
                    onPlay = { vm.showEpisodeSheet() },
                    collected = collected,
                    onCollect = { vm.toggleCollect() },
                    sourceCount = info.seriesFlags?.size ?: 1,
                    onSwitchSource = { vm.onSwitchSourceClick() },
                    onCast = { activity.playContainer?.showCast() },
                    onMusic = { activity.openMusicPlayer() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                // ── 简介:两行折叠 ────────────────────────────────────────
                if (desc.isNotEmpty()) {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { descExpanded = !descExpanded },
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { descExpanded = !descExpanded }
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(if (descExpanded) R.string.detail_collapse else R.string.detail_expand),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(if (descExpanded) 180f else 0f),
                        )
                    }
                }
            }
        }

        if (qualityOptions.size > 1) {
            item(key = "quality") {
                ChipRow(
                    title = stringResource(R.string.detail_quality),
                    leading = { SectionTitleIcon(painterResource(R.drawable.ic_detail_quality)) },
                ) {
                    itemsIndexed(qualityOptions) { index, option ->
                        FilterChip(
                            selected = index == qualitySelected,
                            onClick = { vm.onQualityClick(index, activity.playbackFacts()) },
                            label = { Text(option) },
                            shape = RoundedCornerShape(20.dp),
                            colors = MaterialTheme.colorScheme.filterChipColors(),
                        )
                    }
                }
            }
        }

        // 「线路」区:手动换线入口。标签是「线路N + 实测画质」,不暴露站点自报的 flag 名,
        // 也不会泄露源身份;更关键的是用户手动选过之后播放侧会记 userPickedLine 而不再自动换线,
        // 这个意图要有入口才成立(见 DetailLineSection 的说明)。
        // 单线路不显示:没有可切换对象,一行只有一个 chip 的「线路」区是噪声。
        // flags / currentFlag **必须显式传进去**:vodInfo 是普通 var,不是 StateFlow,
        // 若在组合内部读它,参数不变时 Compose 会跳过重组 ⇒ 点完线路"视频换了但选中态不换"
        val lineFlags = info.seriesFlags.orEmpty()
        if (lineFlags.size > 1) {
            item(key = "lines") {
                DetailLineSection(vm, lineFlags, currentFlag)
            }
        }

        if (episodes.isNotEmpty()) {
            item(key = "episodes") {
                EpisodeRow(vm, info, episodes, playIndex, currentFlag)
            }
        }

        // 「换源」区(2026-10-14 恢复):用户明确要求"选集下方那一栏显示可换的源 + 评分"。
        //
        // ⚠️ 此前这里主动下线了它(旧注释:"换源由播放侧自动接管,手动 chip 无意义")。
        // 但真机反馈暴露两个问题:①自动接管只在"线路耗尽"时发生,用户想**主动**换到更好的源却没入口;
        // ②连"有哪些源/各自评分"都看不到。故按用户设计恢复:用户主动点「换源」→ 分页浏览候选源。
        // SourceSection 内部**无条件渲染「换源」按钮**(即使候选为空),不受此 item 的可见性影响。
        item(key = "sources") {
            SourceSection(vm, revision)
        }

        // 「相关推荐」是独立区块,不受影响
        item(key = "related") {
            RelatedSection(activity, vm, onCardLongClick)
        }
    }
}

/**
 * 详情页操作条上的圆形按钮:42dp 热区、无文字、图标 21dp。
 *
 * <p>为什么不用 IconButton:它的默认热区是 48dp 且自带水波纹半径,三颗并排时会撑出
 * 一条 156dp 的虚框,把"对这部片子的操作"做成了一条工具栏。
 */
@Composable
private fun DetailCircleAction(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * 章节容器:标题 + 横向滚动 chips。
 *
 * <p>由「画质」「线路」两个区共用,保证两个区在视觉上完全同构。
 * 从 `private` 放开到 `internal` 是因为 [DetailLineSection] 在同包的独立文件里复用它。
 */
@Composable
internal fun ChipRow(
    title: String,
    leading: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(20.dp))
            .padding(vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            leading?.invoke()
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = if (leading != null) 8.dp else 0.dp),
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
