package com.github.tvbox.osc.ui.page

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.ui.components.VodPoster
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.util.EpisodeTotals
import kotlin.math.roundToInt

private const val PROGRESS_ENTER_DURATION_MS = 600

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryRow(
    item: VodInfo,
    totalEpisodes: Int?,
    playedPercent: Int?,
    editMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: (() -> Unit)? = null,
) {
    var progressEntered by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.cardContainer,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .height(64.dp)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 设计稿 02-A:缩略图 88×50 横版(原 82dp 竖版海报 + 大卡片,行高失控)
                Box(
                    modifier = Modifier
                        .width(88.dp)
                        .height(50.dp),
                ) {
                    VodPoster(
                        name = item.name,
                        pic = item.pic,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp)),
                    )
                    if (editMode) {
                        SelectCircle(
                            selected = selected,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = item.name ?: "",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val numberedEpisode = item.playNote.isNullOrEmpty() || EpisodeTotals.isNumberedEpisode(item.playNote)
                    val eps = if (numberedEpisode) {
                        totalEpisodes ?: parseEpisodeTotal(item.note) ?: parseEpisodeTotal(item.state)
                    } else {
                        null
                    }
                    val episodeFraction = eps?.let { total ->
                        (item.playIndex + 1).coerceIn(1, total).toFloat() / total
                    }
                    val barProgress = playedPercent?.let { it / 100f } ?: episodeFraction
                    // 副行:观看位置 + 进度,合成一行(设计稿要求行高压到 64,进度文字不再单独占行)
                    val where = if (item.playNote.isNullOrEmpty()) {
                        item.note ?: ""
                    } else {
                        stringResource(R.string.history_last_watched, item.playNote)
                    }
                    val pct = barProgress?.let { (it * 100).roundToInt() }
                    Text(
                        text = buildString {
                            append(where)
                            if (pct != null && pct > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("$pct%")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // ⋯ 显式入口(设计稿:长按菜单保留,另给可见入口) —— 长按不可发现
                if (!editMode && onMoreClick != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onMoreClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.common_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            // 底部 3dp 进度条:贴卡片底边,不占行高(设计稿 02-A)
            val numberedEpisode2 = item.playNote.isNullOrEmpty() || EpisodeTotals.isNumberedEpisode(item.playNote)
            val eps2 = if (numberedEpisode2) {
                totalEpisodes ?: parseEpisodeTotal(item.note) ?: parseEpisodeTotal(item.state)
            } else {
                null
            }
            val fraction = eps2?.let { total ->
                (item.playIndex + 1).coerceIn(1, total).toFloat() / total
            }
            val bar = playedPercent?.let { it / 100f } ?: fraction
            if (bar != null && bar > 0f) {
                val progressAnim = remember { Animatable(if (progressEntered) bar else 0f) }
                LaunchedEffect(bar) {
                    val spec = if (progressEntered) {
                        ProgressIndicatorDefaults.ProgressAnimationSpec
                    } else {
                        tween(PROGRESS_ENTER_DURATION_MS)
                    }
                    progressEntered = true
                    progressAnim.animateTo(bar, spec)
                }
                LinearProgressIndicator(
                    progress = { progressAnim.value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

// i18n: keep —— 匹配源数据(片名/备注)里的"第N集/期",不能翻
private val EpisodeTotalRegex = Regex("(\\d+)\\s*[集期]")

private fun parseEpisodeTotal(note: String?): Int? {
    val total = note?.let { EpisodeTotalRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return null
    return total.takeIf { it in 2..1000 }
}
