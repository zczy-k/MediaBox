@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

sealed interface LoadState {
    data object Loading : LoadState
    data object Empty : LoadState
    data class Error(val message: String? = null) : LoadState
}

@Composable
fun LoadStateBox(
    state: LoadState,
    emptyText: String,
    errorText: String,
    retryText: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    emptyIconRes: Int? = null,
    loadingContent: @Composable () -> Unit = { ContainedLoadingIndicator(Modifier.size(64.dp)) },
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (state) {
            LoadState.Loading -> loadingContent()

            // 空态统一"图标 + 文案"竖排居中(2026-10-09 美化):之前无 icon 时只有一行裸文字,
            // 长文案换行后行首左对齐,观感像靠左排版。带 icon 的页面(详情页等)与不带 icon 的
            // 页面(搜索/历史等,emptyIconRes=null 走原有纯文字)由此保持同一视觉语言。
            LoadState.Empty -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (emptyIconRes != null) {
                    Icon(
                        painter = painterResource(emptyIconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(64.dp),
                    )
                }
                StateText(emptyText)
            }

            is LoadState.Error -> Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StateText(state.message ?: errorText)
                if (onRetry != null) {
                    FilledTonalButton(onClick = onRetry) {
                        Text(text = retryText)
                    }
                }
            }
        }
    }
}

/**
 * 状态文案统一排版(2026-10-09 美化):
 * - `fillMaxWidth` + `textAlign = Center`:多行长文案**每一行**都居中 —— 修复"整块居中但
 *   换行后看起来靠左"的观感问题;
 * - `widthIn(max = 320.dp)`:限制单行过长,避免在大屏上拉成一条横幅。
 */
@Composable
private fun StateText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp),
    )
}
