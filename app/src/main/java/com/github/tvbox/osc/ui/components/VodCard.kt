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
                shape = RoundedCornerShape(20.dp),
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
                    .padding(top = 8.dp),
            )
        }
        return
    }

    PressableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
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
// 非锚定匹配:站点会把站名与评分混排("某站 7.2分",RatingBadgeLeakTest 锁此语义);
// 尾随 (?!钟) 排除"分钟"类时长("片长30分钟"不能被当成评分)。2026-10-10 修正
private val RATING_SCORE_SUFFIX_REGEX = Regex("(\\d+(?:\\.\\d+)?)\\s*分(?!钟)") // i18n: keep(源备注评分提取)

/**
 * 从源备注(note)里提取**可安全展示**的短标签,通常是评分。
 *
 * <p>⚠️ **只返回评分数字,永不回显 note 原文** —— 这是产品要求的硬边界:
 * "任何情况下用户都不能看到 App 有哪些源"。
 *
 * <p>为什么以前会泄露:老实现解析不出评分时直接 `return n`(把 note 全文当角标渲染),
 * 而**站点普遍会把站名塞进 note 字段**。真机实测(诊断包 v1.0.24,js_douban 源)在
 * 详情页"相关推荐"卡片角标上直接渲染出「蝴蝶影视」—— 三个渲染出口
 * ({@link ratingBadgeText} 的角标、[posterMetaLine] 副文案、[HeroCarousel] 副标题)
 * 全部中招。这是第 6 处泄露,也是最隐蔽的一处:不在源名列表里,而是藏在
 * "备注"这个看起来无害的字段里。
 *
 * <p>代价:解析不出评分时角标不渲染(卡片少一个小标签)。
 * 这个代价比泄露站名小得多 —— 角标可有可无,源清单不能外泄。
 * 真正有信息量的备注(集数等)已由 [posterMetaLine] 的其它优先级分支覆盖。
 */
internal fun ratingBadgeText(note: String?): String? {
    val n = note?.trim().orEmpty()
    if (n.isEmpty()) return null
    val m = RATING_SCORE_REGEX.find(n)
        ?: RATING_SCORE_SUFFIX_REGEX.find(n)
    if (m != null) {
        val num = m.groupValues[1]
        return if (num.toFloatOrNull() == 0f) null else num
    }
    // 无评分可提取:**不渲染**,而不是回显原文(见 KDoc)
    return null
}

private val META_SEPARATOR = " / "

/**
 * 卡片图片底部那行副文案。优先级:年份 + 地区(有则用,组合成 "2025 / 日本")→
 * 备注里的**评分**(如 "8.5",由 [ratingBadgeText] 提取)→ 分类名(如 "日韩剧")。
 * 都为空则返回空串,不渲染该行。
 *
 * <p>之所以要做兜底:番号/漫画类源在列表接口里普遍不给 vod_area/vod_year,老逻辑
 * (仅 year+area)会整行消失,卡片下方看起来"信息不全"。
 *
 * <p>⚠️ 备注(note)只在**能提取出评分**时才参与展示,原文一律不显示 ——
 * 站点会把站名塞进 note,回显原文等于泄露源身份,见 [ratingBadgeText] 的 KDoc。
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
