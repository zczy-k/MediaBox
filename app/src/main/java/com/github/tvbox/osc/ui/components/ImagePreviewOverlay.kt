package com.github.tvbox.osc.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

/** 缩放上下限:1× 是"整图可见",5× 够看清封面上的小字,再大只是在看马赛克 */
private const val PREVIEW_MIN_SCALE = 1f
private const val PREVIEW_MAX_SCALE = 5f

/** 双击一次到 2×:封面上的片名/番号在 2× 下基本可读,再大要用户自己捏 */
private const val PREVIEW_DOUBLE_TAP_SCALE = 2f

/**
 * 搜索结果缩略图的**原图放大浮层**(2026-10-22)。
 *
 * <p>## 为什么自写而不引库
 * 项目依赖里没有任何图片缩放库(photoview / subsampling-scale-image-view / zoomage 全无),
 * 只有 `coil.compose`。引库要改 gradle、走一次网络拉包、还得用 `AndroidView` 把 View 体系包一层 ——
 * 为一个缩略图预览不划算。`detectTransformGestures` + `graphicsLayer` 约 130 行就够。
 *
 * <p>## 手势分工(踩点在这里,别随手改)
 * - **缩放 > 1 时单击不关闭**:放大之后用户会自然地点一下屏幕想"退出缩放",
 *   此时直接关掉整个浮层会让他以为点错了。单击只在 1× 时负责关闭。
 * - **拖动边界夹死**:偏移上限 = `尺寸 × (scale−1) / 2`,正好是"图放大后多出来的那一半"。
 *   不夹的话图能被拖出屏幕,剩一片黑,用户以为图坏了。
 * - 双击:1× → 2×;已放大 → 复原 1× 并把偏移归零(否则复原后图还偏在一边)。
 *
 * <p>## 图片尺寸
 * 这里**不设 `size`** —— 与缩略图相反,这是唯一需要全分辨率的场景。
 *
 * @param pic 原图地址;为空时浮层不该被打开(调用方负责拦)
 * @param title 顶部第一行(片名)
 * @param siteName 顶部第二行(站名,只有多站源才有;为空则不占位)
 */
@Composable
fun ImagePreviewOverlay(
    pic: String?,
    title: String?,
    siteName: String?,
    onDismiss: () -> Unit,
) {
    // 返回键先关浮层,不能让它冒到搜索页去(那样会把整个搜索页退掉)
    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        // usePlatformDefaultWidth = false:默认宽度是屏幕的 ~90%,原图预览要占满整屏
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f)),
        ) {
            var scale by remember(pic) { mutableStateOf(PREVIEW_MIN_SCALE) }
            var offset by remember(pic) { mutableStateOf(Offset.Zero) }
            var loading by remember(pic) { mutableStateOf(true) }
            var failed by remember(pic) { mutableStateOf(false) }

            val context = LocalPlatformContext.current
            val request = remember(context, pic) {
                ImageRequest.Builder(context).data(pic).build()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(pic) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(PREVIEW_MIN_SCALE, PREVIEW_MAX_SCALE)
                            // 放大后多出来的那一半才是可拖范围;1× 时 max=0,偏移自动归零
                            val maxX = size.width * (next - 1f) / 2f
                            val maxY = size.height * (next - 1f) / 2f
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                            scale = next
                        }
                    }
                    .pointerInput(pic) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > PREVIEW_MIN_SCALE) {
                                    scale = PREVIEW_MIN_SCALE
                                    offset = Offset.Zero
                                } else {
                                    scale = PREVIEW_DOUBLE_TAP_SCALE
                                }
                            },
                            // 1× 时单击 = 关闭;放大时单击什么都不做(见 KDoc)
                            onTap = { if (scale <= PREVIEW_MIN_SCALE) onDismiss() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (pic.isNullOrBlank()) {
                    Text(
                        text = "图片地址为空",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                    )
                } else {
                    AsyncImage(
                        model = request,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                        onState = { state ->
                            loading = state is AsyncImagePainter.State.Loading
                            failed = state is AsyncImagePainter.State.Error
                        },
                    )
                }
                if (loading) CircularProgressIndicator(color = Color.White)
                if (failed) {
                    Text(
                        text = "图片加载失败",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                    )
                }
            }

            // 顶部信息条:压一层竖向渐变,否则浅色封面上白字看不清
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                        ),
                    )
                    .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title.orEmpty(),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!siteName.isNullOrBlank()) {
                            Text(
                                text = siteName,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss)
                            .padding(10.dp),
                    )
                }
            }
        }
    }
}
