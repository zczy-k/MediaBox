package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable

/**
 * TV 端搜索键盘面板(2026-10-22):全屏 Dialog,替代系统 IME。
 *
 * <p>设计依据:三星 Smart TV 规范要求 TV 文本输入提供自绘屏幕键盘;
 * Netflix/YouTube 模式 = QWERTY 网格 + 即时过滤 + 焦点常驻键盘。
 *
 * <p>交互:
 * - D-pad 在键位间几何移动,OK 输入;**输入后焦点不动**(输错可立刻删)
 * - 每键即搜([onQueryChange] 直接触发上层搜索),面板半透明可见背后结果刷新
 * - 数字切换(番号刚需)/空格/单删/清空/收起
 * - 首键自动聚焦,避免"点开键盘还要再按一次"
 */
@Composable
fun TvSearchKeyboardPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var numeric by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { firstKey.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6141210))
                .systemBarsPadding()
                .padding(horizontal = 28.dp, vertical = 20.dp),
        ) {
            // ── 输入回显条:纯展示 + 单删/清空 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = query.ifEmpty { " " },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                KeyCap(text = "⌫ 删除", modifier = Modifier.width(96.dp)) { onQueryChange(query.dropLast(1)) }
                Spacer(Modifier.width(8.dp))
                KeyCap(text = "清空", modifier = Modifier.width(76.dp)) { onQueryChange("") }
            }
            Spacer(Modifier.height(14.dp))

            // ── 字母/数字网格 ──
            val rows: List<String> = if (numeric) {
                listOf("1234567890", "-._")
            } else {
                listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
            }
            rows.forEachIndexed { ri, rowText ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowText.forEachIndexed { ki, ch ->
                        val fr = if (ri == 0 && ki == 0) firstKey else null
                        KeyCap(
                            text = if (numeric) ch.toString() else ch.toString(),
                            modifier = Modifier.weight(1f),
                            focusRequester = fr,
                        ) {
                            // 统一小写:源站归一化本就 lowercase,大写只会被拼音逻辑误伤
                            onQueryChange(query + ch.lowercase())
                        }
                    }
                    repeat(10 - rowText.length) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── 功能行 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KeyCap(
                    text = if (numeric) "ABC" else "123",
                    modifier = Modifier.weight(1.3f),
                    primary = false,
                ) { numeric = !numeric }
                KeyCap(text = "空格", modifier = Modifier.weight(2.2f)) { onQueryChange(query + " ") }
                KeyCap(text = "⌫", modifier = Modifier.weight(1.3f)) { onQueryChange(query.dropLast(1)) }
                KeyCap(
                    text = "收起键盘",
                    modifier = Modifier.weight(1.8f),
                    primary = true,
                ) { onDismiss() }
            }
        }
    }
}

/** 单个键帽:D-pad 可聚焦,聚焦态主色高亮;焦点请求器供面板打开时落焦首键。 */
@Composable
private fun KeyCap(
    text: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val ia = remember { MutableInteractionSource() }
    val focused by ia.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    primary -> MaterialTheme.colorScheme.primary
                    focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                    else -> MaterialTheme.colorScheme.surfaceContainer
                }
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusable()
            .clickable(
                interactionSource = ia,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
