package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tvbox.osc.bean.Movie

enum class VodCardStyle { Overlay, Stacked }

@Composable
fun VodCard(
    video: Movie.Video,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: VodCardStyle = VodCardStyle.Overlay,
    posterAspectRatio: Float = 2f / 3f,
    onPosterAspectRatio: ((Float) -> Unit)? = null,
    fitPosterContent: Boolean = false,
) {
    if (style == VodCardStyle.Stacked) {
        Column(modifier = modifier) {
            PressableCard(
                onClick = onClick,
                onLongClick = onLongClick,
                shape = RoundedCornerShape(16.dp),
            ) {
                Box(modifier = Modifier.aspectRatio(posterAspectRatio)) {
                    VodPoster(
                        name = video.name,
                        pic = video.pic,
                        fitContent = fitPosterContent,
                        onAspectRatio = onPosterAspectRatio,
                    )
                    RatingBadge(video, Modifier.align(Alignment.TopEnd))
                }
            }
            Text(
                text = video.name ?: "",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }
        return
    }

    PressableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(modifier = Modifier.aspectRatio(posterAspectRatio)) {
            VodPoster(
                name = video.name,
                pic = video.pic,
                fitContent = fitPosterContent,
                onAspectRatio = onPosterAspectRatio,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.8f),
                        )
                    ),
            )
            RatingBadge(video, Modifier.align(Alignment.TopEnd))
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(
                    text = video.name ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 描述兜底:year/area 列表接口常缺,依次退到 note(备注/集数)→ type(分类)→ 都不显示
                val meta = posterMetaLine(video)
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RatingBadge(video: Movie.Video, modifier: Modifier = Modifier) {
    val badge = ratingBadgeText(video.note) ?: return
    Text(
        text = badge,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private val RATING_SCORE_REGEX = Regex("评分[:：]?\\s*(\\d+(?:\\.\\d+)?)") // i18n: keep(源备注评分提取)
private val RATING_SCORE_SUFFIX_REGEX = Regex("^(\\d+(?:\\.\\d+)?)\\s*分$") // i18n: keep(源备注评分提取)

internal fun ratingBadgeText(note: String?): String? {
    val n = note?.trim().orEmpty()
    if (n.isEmpty()) return null
    val m = RATING_SCORE_REGEX.find(n)
        ?: RATING_SCORE_SUFFIX_REGEX.find(n)
    if (m != null) {
        val num = m.groupValues[1]
        return if (num.toFloatOrNull() == 0f) null else num
    }
    return n
}

private val META_SEPARATOR = " / "

/**
 * 卡片图片底部那行副文案。优先级:年份 + 地区(有则用,组合成 "2025 / 日本")→
 * 备注/集数(如 "共30集,更新至12集")→ 分类名(如 "日韩剧")。都为空则返回空串,不渲染该行。
 *
 * <p>之所以要做兜底:番号/漫画类源在列表接口里普遍不给 vod_area/vod_year,老逻辑
 * (仅 year+area)会整行消失,卡片下方看起来"信息不全"。
 */
internal fun posterMetaLine(video: Movie.Video): String {
    val primary = buildString {
        if (video.year > 0) append(video.year)
        if (!video.area.isNullOrBlank()) {
            if (isNotEmpty()) append(META_SEPARATOR)
            append(video.area)
        }
    }
    if (primary.isNotEmpty()) return primary
    ratingBadgeText(video.note)?.let { if (it.isNotEmpty()) return it }
    return video.type?.trim().orEmpty()
}
