package com.github.tvbox.osc.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tvbox.osc.ui.components.MediaBoxBottomSheet

/**
 * 「换源」底部弹层 —— 操作条 ⇄ 圆钮的唯一去处。
 *
 * <p>内容 = 原「搜源」白卡的整套逻辑([SourceSection]),零逻辑改动只换容器:
 * - 弹层自带标题与关闭手势,白卡的标题行不再需要
 * - `maxHeightFraction = 0.82`:候选源一屏能看全一页,又不顶到状态栏
 *
 * <p>开关挂在 [DetailViewModel.sourceSheet](横屏全屏侧滑时 DetailContent 不在组合里,
 * Compose 本地状态会丢,VM 状态不会)。
 */
@Composable
internal fun DetailSourceSheet(vm: DetailViewModel, revision: Int) {
    val show by vm.sourceSheet.collectAsStateWithLifecycle()
    if (!show) return

    MediaBoxBottomSheet(
        onDismissRequest = { vm.dismissSourceSheet() },
        title = androidx.compose.ui.res.stringResource(com.github.tvbox.osc.R.string.detail_source_switch_action),
        isScrollable = false,
        maxHeightFraction = 0.82f,
    ) {
        // 候选源点击后 switchSource 触发换源链,弹层保持打开让用户看到进度行
        SourceSection(vm, revision, statusInline = true)
    }
}
