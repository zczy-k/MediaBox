package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.components.MediaBoxBottomSheet
import com.github.tvbox.osc.ui.theme.Fluid

/**
 * 详情页操作条能放几个次级入口(含溢出槽)。
 *
 * <p>**按可用宽度算,不按设备判断** —— 折叠屏展开 673dp 的右栏可能比 412dp 的手机还窄,
 * 用设备类型判断会给出错误的答案。主按钮保底 140dp(要放得下「从第 5 集播放」),
 * 放不下的入口收进「⋯」。封顶 4:四个都放下时不需要溢出槽。
 *
 * <p>优先级:收藏(带状态)> 换源(核心救急)> 投屏 > 音乐播放器。
 */
internal fun detailActionSlots(availWidth: Dp, windowWidth: Dp): Int =
    Fluid.detailActionSlots(availWidth, windowWidth)

/**
 * 详情页操作条。
 *
 * <p>形态随可用宽度变化,不是随设备:
 * - 槽位 ≥ 4:主按钮 + 四个入口全平铺(宽屏带文字标签)
 * - 槽位 = 3:主按钮 + 收藏 + 换源 + ⋯(投屏/音乐进弹层)
 *
 * <p>⚠️ 主按钮不缩小。它承载「从第 N 集播放」,同时告诉用户上次看到哪 ——
 * 压到 100dp 会先丢掉「第 N 集」,那是这页信息量最大的部分。
 */
@Composable
internal fun DetailActionRow(
    availWidth: Dp,
    windowWidth: Dp,
    primaryText: String,
    onPlay: () -> Unit,
    collected: Boolean,
    onCollect: () -> Unit,
    onCast: () -> Unit,
    onMusic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slots = detailActionSlots(availWidth, windowWidth)
    val btnSize = Fluid.actionButtonSize(windowWidth)
    val withLabels = slots >= 4 && availWidth.value >= 420f
    var showMore by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 主按钮:占满剩余宽度,但不低于 140dp 的可读底线
        Row(
            modifier = Modifier
                .weight(1f)
                .height(btnSize + 4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onPlay)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_detail_episodes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = primaryText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        ActionItem(
            iconRes = if (collected) R.drawable.ic_tab_collect_filled else R.drawable.ic_tab_collect,
            label = stringResource(if (collected) R.string.detail_uncollect else R.string.detail_collect),
            active = collected,
            size = btnSize,
            withLabel = withLabels,
            onClick = onCollect,
        )
        // 「换源」圆钮已移除(2026-10-21 用户定案):下方「搜源」卡就在首屏,
        // 两个 ⇄ 同屏、图标相同、功能同指 —— 圆钮只是个滚动快捷键,多余。
        // 候选源数/搜索进度本来就在搜源卡的标题行里(「问过 63 个源…」),信息没丢。
        if (slots >= 4) {
            ActionItem(
                iconRes = R.drawable.ic_detail_cast,
                label = stringResource(R.string.common_cast),
                size = btnSize,
                withLabel = withLabels,
                onClick = onCast,
            )
            ActionItem(
                iconRes = R.drawable.ic_detail_music_player,
                label = stringResource(R.string.detail_music_player),
                size = btnSize,
                withLabel = withLabels,
                onClick = onMusic,
            )
        } else {
            // 溢出槽永远在最右,不随优先级插队 —— 用户第二次找它不用重新扫一遍
            ActionItem(
                iconRes = R.drawable.ic_more_vert,
                label = stringResource(R.string.common_more),
                size = btnSize,
                withLabel = false,
                onClick = { showMore = true },
            )
        }
    }

    if (showMore) {
        MediaBoxBottomSheet(
            onDismissRequest = { showMore = false },
            title = stringResource(R.string.common_more),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            isScrollable = false,
        ) {
            MoreRow(
                iconRes = R.drawable.ic_detail_cast,
                text = stringResource(R.string.common_cast),
                onClick = {
                    showMore = false
                    onCast()
                },
            )
            MoreRow(
                iconRes = R.drawable.ic_detail_music_player,
                text = stringResource(R.string.detail_music_player),
                onClick = {
                    showMore = false
                    onMusic()
                },
            )
        }
    }
}

@Composable
private fun ActionItem(
    iconRes: Int,
    label: String,
    size: Dp,
    withLabel: Boolean,
    onClick: () -> Unit,
    active: Boolean = false,
    badge: Int? = null,
) {
    val container = if (active) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainer
    val tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .height(size)
            .then(
                if (withLabel) Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(container)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 13.dp)
                else Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(container)
                    .clickable(onClick = onClick)
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(if (withLabel) 18.dp else 21.dp),
                )
                if (badge != null) {
                    // 角标 = 可用源数量:让用户知道"换得动",不用点进去才发现没得换
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 0.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 3.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = badge.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (withLabel) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MoreRow(iconRes: Int, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
