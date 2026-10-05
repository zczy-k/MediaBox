package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transitionFactory
import coil3.transition.Transition

private const val UnnamedPosterKey = "！"

private const val POSTER_CHAR_WIDTH_RATIO = 0.46f

private val PosterPalette = intArrayOf(
    0xFFEF5350.toInt(),
    0xFFEC407A.toInt(),
    0xFFAB47BC.toInt(),
    0xFF7E57C2.toInt(),
    0xFF5C6BC0.toInt(),
    0xFF42A5F5.toInt(),
    0xFF29B6F6.toInt(),
    0xFF26C6DA.toInt(),
    0xFF26A69A.toInt(),
    0xFF66BB6A.toInt(),
    0xFF9CCC65.toInt(),
    0xFFD4E157.toInt(),
    0xFFFFEE58.toInt(),
    0xFFFFCA28.toInt(),
    0xFFFFA726.toInt(),
    0xFFFF7043.toInt(),
    0xFF8D6E63.toInt(),
    0xFFBDBDBD.toInt(),
    0xFF78909C.toInt(),
)

internal fun posterSeedColor(name: String?): Int {
    val key = posterFirstChar(name)
    return PosterPalette[(key.hashCode() and Int.MAX_VALUE) % PosterPalette.size]
}

/** 取单个 UTF-16 码元会把 emoji 切成高位代理,渲染成豆腐块 */
internal fun posterFirstChar(name: String?): String {
    val text = name?.trim().orEmpty()
    if (text.isEmpty()) return UnnamedPosterKey
    return text.take(if (text[0].isHighSurrogate()) 2 else 1)
}

/**
 * Poster renderer shared by cards, history and detail views.
 *
 * @param fitContent true for the home grid: show the complete source image without cropping;
 *                   the caller chooses a source/category-wide frame ratio so grid rows stay aligned.
 * @param onAspectRatio called once after the intrinsic image dimensions are known.
 */
@Composable
internal fun VodPoster(
    name: String?,
    pic: String?,
    modifier: Modifier = Modifier,
    fitContent: Boolean = false,
    onAspectRatio: ((Float) -> Unit)? = null,
) {
    var showFallback by remember(pic) { mutableStateOf(false) }
    var reportedAspect by remember(pic) { mutableStateOf(false) }
    val context = LocalPlatformContext.current
    val request = remember(context, pic) {
        ImageRequest.Builder(context)
            .data(pic)
            // Avoid a crossfade flashing the fallback through the image.
            .transitionFactory(Transition.Factory.NONE)
            .build()
    }

    val posterModifier = if (fitContent) {
        modifier.background(MaterialTheme.colorScheme.surfaceContainer)
    } else {
        modifier
    }
    Box(modifier = posterModifier) {
        if (showFallback) PosterFallback(name)
        AsyncImage(
            model = request,
            contentDescription = name,
            contentScale = if (fitContent) ContentScale.Fit else ContentScale.Crop,
            onState = { state ->
                showFallback = state is AsyncImagePainter.State.Error
                if (!reportedAspect && onAspectRatio != null && state is AsyncImagePainter.State.Success) {
                    val size = state.painter.intrinsicSize
                    if (size.width.isFinite() && size.height.isFinite() && size.width > 0f && size.height > 0f) {
                        reportedAspect = true
                        onAspectRatio(size.width / size.height)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PosterFallback(name: String?) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(posterSeedColor(name))),
        contentAlignment = Alignment.Center,
    ) {
        // Use the short edge: a 1.5 landscape Hero should not derive text size from its width.
        val charHeight = minOf(maxWidth, maxHeight) * POSTER_CHAR_WIDTH_RATIO
        val charSize = with(LocalDensity.current) { charHeight.toSp() }
        Text(
            text = posterFirstChar(name),
            style = TextStyle(
                color = Color.White,
                fontSize = charSize,
                lineHeight = charSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            maxLines = 1,
            modifier = Modifier.padding(2.dp),
        )
    }
}
