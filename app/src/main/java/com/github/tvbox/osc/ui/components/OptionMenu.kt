package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val OptionMenuMinWidth = 156.dp

@Composable
fun MediaBoxOptionMenu(
    expanded: Boolean,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    disabledIndices: Set<Int> = emptySet(),
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = OptionMenuMinWidth)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEachIndexed { index, option ->
                OptionMenuCard(
                    label = option,
                    selected = index == selectedIndex,
                    enabled = index !in disabledIndices,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

/**
 * 单个菜单项。
 *
 * <p>[enabled] 为 false 时只做**视觉降权**(文字转次级色 + 容器转次级面),**仍然可点** ——
 * 调用方要在点击后给出"为什么不可选"的解释(见 [SettingsOptionMenuRow.onDisabledSelect])。
 * 彻底禁用而无反馈会让用户以为界面坏了。
 */
@Composable
private fun OptionMenuCard(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (enabled) {
            MaterialTheme.colorScheme.surfaceBright
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Spacer(Modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun SettingsOptionMenuRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    valueText: String? = null,
    iconRes: Int? = null,
    leadingIconRes: Int? = null,
    enabled: Boolean = true,
    disabledIndices: Set<Int> = emptySet(),
    onDisabledSelect: ((Int) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        SettingsRow(
            title = title,
            subtitle = subtitle,
            valueText = valueText,
            enabled = enabled,
            iconRes = iconRes,
            leadingIconRes = leadingIconRes,
            onClick = if (enabled) ({ expanded = true }) else null,
        )
        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
            MediaBoxOptionMenu(
                expanded = expanded,
                options = options,
                selectedIndex = selectedIndex,
                disabledIndices = disabledIndices,
                onSelect = { index ->
                    if (index in disabledIndices) {
                        // 不写入、**不关闭菜单** —— 菜单留着,提示才看得懂在说哪一项
                        onDisabledSelect?.invoke(index)
                    } else {
                        expanded = false
                        onSelect(index)
                    }
                },
                onDismissRequest = { expanded = false },
            )
        }
    }
}
