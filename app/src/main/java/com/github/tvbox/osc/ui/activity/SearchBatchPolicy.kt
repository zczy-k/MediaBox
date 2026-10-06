package com.github.tvbox.osc.ui.activity

/** 纯逻辑分批策略,避免一次把数百源都启动。 */
internal object SearchBatchPolicy {

    /**
     * 首批/每批的来源数。
     *
     * <p>取 50 的理由:全站搜索的目的是"找到这部片",而命中率取决于覆盖了多少源 —— 只搜 8 个源时
     * 常见的情况是首批一条都没有,用户看到空屏也不会去滑。50 个源基本能覆盖主流站点,
     * 又远小于聚合订阅动辄数百的量级,不会把线程池和爬虫装载锁一次性占满。
     *
     * <p>并发仍由 `SEARCH_THREADS` 的信号量兜住(默认 32),所以"排队 50 个"不等于"同时打 50 个请求"。
     */
    const val DEFAULT_BATCH_SIZE = 50

    private const val PREFETCH_ROWS = 3

    fun nextQueuedKeys(queuedKeys: List<String>, limit: Int = DEFAULT_BATCH_SIZE): List<String> =
        if (limit <= 0) emptyList() else queuedKeys.take(limit)

    /** 首批/本批完全无命中时自动续搜,避免空屏被误认为全库无结果。 */
    fun shouldAutoContinue(hasAnyResult: Boolean, queuedSourceCount: Int): Boolean =
        !hasAnyResult && queuedSourceCount > 0

    /** 仅用户正在滚动且接近列表尾部时预取;短列表不会因初始布局把所有源自动搜完。 */
    fun shouldLoadMoreOnScroll(
        isScrollInProgress: Boolean,
        lastVisibleItemIndex: Int,
        totalItemCount: Int,
        hasMore: Boolean,
        running: Boolean,
    ): Boolean = isScrollInProgress && hasMore && !running && totalItemCount > 0 &&
        lastVisibleItemIndex >= (totalItemCount - 1 - PREFETCH_ROWS).coerceAtLeast(0)
}
