package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.theme.MediaBoxColors
import com.github.tvbox.osc.ui.theme.MediaBoxRadius

private const val HERO_PAGES_PER_SET = 100_000

/**
 * 首页头图轮播。
 *
 * <p>2026-10-19 改版:原来是"露边卡片"(左右各留 18% 屏宽、相邻页缩放 0.82),
 * 视觉重心落在两张半卡片上,海报本身反而变小。新版改为**整幅通栏**:
 * 一屏只讲一部片,标题压在底部渐变里,右下角一颗白色实心按钮承担"立即播放"。
 *
 * <p>为什么去掉缩放/透明度动画:整幅铺满时相邻页在屏幕外,缩放不可见却要每帧算
 * `graphicsLayer`;留着只是白烧帧,低端机滑动会掉帧。
 */
@Composable
fun HeroCarousel(
    videos: List<Movie.Video>,
    onCardClick: (Movie.Video) -> Unit,
    modifier: Modifier = Modifier,
    /** 头图高度:由调用方按窗口宽高算(Fluid.heroHeight);null 时退回 1.72:1 比例 */
    heroHeight: Dp? = null,
    /** 圆角:由调用方按窗口宽算(Fluid.heroRadius) */
    cornerRadius: Dp = MediaBoxRadius.Container,
) {
    if (videos.isEmpty()) return
    val n = videos.size
    val pagerState = rememberPagerState(
        initialPage = n * (HERO_PAGES_PER_SET / 2),
        pageCount = { n * HERO_PAGES_PER_SET },
    )

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        pageSpacing = 12.dp,
    ) { page ->
        val video = videos[page % n]
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (heroHeight != null) Modifier.height(heroHeight)
                    else Modifier.aspectRatio(1.72f)
                )
                .clip(RoundedCornerShape(cornerRadius))
                .clickable { onCardClick(video) },
        ) {
            VodPoster(
                name = video.name,
                pic = video.pic,
                modifier = Modifier.fillMaxSize(),
            )
            // 底部渐变必须够高(72% 黑):海报本身明暗不可控,只压 40% 时白底海报上的白字会糊
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.72f),
                        )
                    ),
            )
            Text(
                text = stringResource(R.string.home_hot_recommend),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MediaBoxColors.BrandDeep,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MediaBoxColors.BrandBright)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, end = 120.dp, bottom = 13.dp),
            ) {
                Text(
                    text = video.name ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = heroMetaLine(video)
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(n.coerceAtMost(5)) { i ->
                        val on = i == pagerState.currentPage % n
                        Box(
                            modifier = Modifier
                                .height(4.dp)
                                .width(if (on) 13.dp else 4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (on) Color.White else Color.White.copy(alpha = 0.45f)
                                ),
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.home_hero_play),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MediaBoxColors.BrandDeep,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 14.dp)
                    .clip(RoundedCornerShape(MediaBoxRadius.Pill))
                    .background(Color.White)
                    .clickable { onCardClick(video) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 头图副标题:年份 · 分类 · 集数/备注。
 *
 * <p>⚠️ 备注(note)只在能提取出评分时才参与展示,原文一律不显示 ——
 * 站点会把站名塞进 note,回显等于泄露源身份(见 [ratingBadgeText] 的 KDoc)。
 */
private fun heroMetaLine(video: Movie.Video): String = buildString {
    if (video.year > 0) append(video.year)
    video.type?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (isNotEmpty()) append(" · ")
        append(it)
    }
    ratingBadgeText(video.note)?.let {
        if (isNotEmpty()) append(" · ")
        append(it)
        append(" 分")
    }
}
