package com.github.tvbox.osc.ui.page

/**
 * 是否该预取下一页。
 *
 * <p>末尾"加载更多"哨兵压在视口外时不会组合 ⇒ 首屏末行右侧会空一格,
 * 离末尾不足一行就先取下一页。
 *
 * <p>原先定义在 HomeGridLayout.kt 里;那份旧首页实现已整体删除,
 * 但本函数被 [PartitionListActivity] 与 [HomeFeedScreen] 共用,故独立成文件。
 */
internal fun shouldPrefetchNextPage(
    lastVisibleIndex: Int,
    totalItemsCount: Int,
    columns: Int,
): Boolean = totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - 1 - columns
