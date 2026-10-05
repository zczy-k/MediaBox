package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.ui.theme.cardContainer

private val MockupScaleBase = 480.dp

private const val MockupMaxScale = 2.2f

private val MockupWidth = 168.dp

private val MockupHeight = 308.dp

private val MockupPadding = 10.dp

private val MockupCorner = 26.dp

private val SourcePillWidth = 70.dp

private val SourcePillHeight = 16.dp

private val SourcePillLogoSize = 10.dp

private val SourcePillTextWidth = 28.dp

private val SourcePillTextHeight = 5.dp

private val TopIconSize = 16.dp

private val TopGap = 8.dp

private val TabRowHeight = 17.dp

private val TabWidth = 20.dp

private val TabBarHeight = 7.dp

private val TabGap = 8.dp

private val TabIndicatorHeight = 2.dp

private val PosterShape = RoundedCornerShape(7.dp)

private val PosterRowGap = 10.dp

private val PosterColumnGap = 5.dp

private const val PosterRowCount = 3

private const val PosterColumnCount = 3

private val NavHeight = 26.dp

private val NavSlotHeight = 22.dp

private val NavInnerPadding = 2.dp

private val NavSlotGap = 2.dp

private val NavIconSize = 9.dp

private val NavLabelWidth = 13.dp

private val NavLabelHeight = 4.dp

private val NavShadow = 4.dp

private const val NavSelectedWeight = 1.5f

@Composable
fun PhoneMockupPreview(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val scale = (maxWidth / MockupScaleBase).coerceIn(1f, MockupMaxScale)
        Box(
            modifier = Modifier.size(MockupWidth * scale, MockupHeight * scale),
            contentAlignment = Alignment.Center,
        ) {
            PhoneMockupBody(
                Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            )
        }
    }
}

@Composable
private fun PhoneMockupBody(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val cardColor = scheme.cardContainer
    val ghostColor = scheme.onSurfaceVariant.copy(alpha = 0.5f)
    Surface(
        modifier = modifier
            .width(MockupWidth)
            .height(MockupHeight),
        shape = RoundedCornerShape(MockupCorner),
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(MockupPadding),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(SourcePillWidth)
                            .height(SourcePillHeight)
                            .background(cardColor, CircleShape),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(5.dp))
                            Box(
                                modifier = Modifier
                                    .size(SourcePillLogoSize)
                                    .background(scheme.primary, CircleShape),
                            )
                            Spacer(Modifier.width(5.dp))
                            Box(
                                modifier = Modifier
                                    .width(SourcePillTextWidth)
                                    .height(SourcePillTextHeight)
                                    .background(ghostColor, CircleShape),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    repeat(2) { index ->
                        if (index > 0) Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(TopIconSize)
                                .background(cardColor, CircleShape),
                        )
                    }
                }
                Spacer(Modifier.height(TopGap))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TabRowHeight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TabGap),
                ) {
                    Box(
                        modifier = Modifier
                            .width(TabWidth)
                            .height(TabBarHeight)
                            .background(scheme.primary, CircleShape),
                    )
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .width(TabWidth)
                                .height(TabBarHeight)
                                .background(ghostColor, CircleShape),
                        )
                    }
                }
                Spacer(Modifier.height(1.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .width(TabWidth)
                            .height(TabIndicatorHeight)
                            .background(scheme.primary, CircleShape),
                    )
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(TopGap))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(PosterRowGap),
                ) {
                    repeat(PosterRowCount) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(PosterColumnGap),
                        ) {
                            repeat(PosterColumnCount) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(3f / 4f)
                                        .background(cardColor, PosterShape),
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(MockupPadding)
                    .fillMaxWidth()
                    .height(NavHeight)
                    .shadow(NavShadow, CircleShape)
                    .background(scheme.surfaceContainerHigh, CircleShape)
                    .border(1.dp, scheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = NavInnerPadding),
                horizontalArrangement = Arrangement.spacedBy(NavSlotGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(NavSelectedWeight)
                        .height(NavSlotHeight)
                        .background(scheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(NavIconSize)
                                .background(scheme.onPrimaryContainer, CircleShape),
                        )
                        Spacer(Modifier.width(3.dp))
                        Box(
                            modifier = Modifier
                                .width(NavLabelWidth)
                                .height(NavLabelHeight)
                                .background(scheme.onPrimaryContainer.copy(alpha = 0.7f), CircleShape),
                        )
                    }
                }
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(NavSlotHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(NavIconSize)
                                .background(ghostColor, CircleShape),
                        )
                    }
                }
            }
        }
    }
}
