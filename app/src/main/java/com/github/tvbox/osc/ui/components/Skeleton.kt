package com.github.tvbox.osc.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import com.github.tvbox.osc.ui.theme.cardContainer
import kotlin.math.hypot

/**
 * 骨架屏的共享动画时钟。
 *
 * 为什么共享:首页加载态一次铺 18 个骨架格子(见 `HomeGridSkeletonCount`),原实现每个格子
 * 各自 `rememberInfiniteTransition` —— 18 条独立动画,每帧 18 次进度更新、18 次重组。
 * 现在由 [ShimmerHost] 建一条 transition 下发,所有格子读同一个值:每帧只更新 1 次。
 *
 * 为 null 时(没有 Host 包裹,例如单点使用)由 [rememberShimmerProgress] 自建,行为与原来一致。
 */
internal val LocalShimmerProgress = compositionLocalOf<State<Float>?> { null }

/** 把内部所有骨架屏接到同一条动画时钟上。包住列表/网格即可,真实内容不受影响。 */
@Composable
fun ShimmerHost(content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
        ),
        label = "shimmerProgress",
    )
    CompositionLocalProvider(LocalShimmerProgress provides progress) { content() }
}

@Composable
private fun rememberShimmerProgress(): State<Float> {
    val transition = rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
        ),
        label = "shimmerProgress",
    )
}

/**
 * 骨架块。
 *
 * 状态读取刻意放在 `drawWithCache` 的绘制 lambda 里 —— 进度每帧变,但只让**绘制阶段**失效,
 * 不触发重组。原实现用 `Modifier.composed`(已废弃,且每次调用都会新开一个组合作用域)。
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
) {
    val baseColor = MaterialTheme.colorScheme.cardContainer
    val shineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val progress = LocalShimmerProgress.current ?: rememberShimmerProgress()

    Box(
        modifier = modifier
            .clip(shape)
            .background(baseColor)
            .drawWithCache {
                val w = size.width
                val h = size.height
                onDrawBehind {
                    val startX = shimmerStartX(w, h, progress.value)
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(baseColor, shineColor, baseColor),
                            start = Offset(startX, 0f),
                            end = Offset(startX + w, h),
                        ),
                    )
                }
            },
    )
}

internal fun shimmerStartX(width: Float, height: Float, progress: Float): Float {
    val diagonal = hypot(width, height)
    if (width <= 0f || diagonal <= 0f) return 0f
    val travel = diagonal * diagonal / width
    return -travel + 2f * travel * progress
}
