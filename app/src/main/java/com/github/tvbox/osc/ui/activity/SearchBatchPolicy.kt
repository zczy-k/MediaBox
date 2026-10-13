package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.SourceBean

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

    /**
     * 快速首轮的来源数上限(2026-10-08)。
     *
     * <p>为什么单独设一个更小的值:全站搜索的首要目标是"尽快给出第一条可用结果",不是"一轮把库扫完"。
     * 数百源里真正又快又高命中的只是一部分(配置里已有 {@code quickSearch} 标记),首轮先搜这些,
     * 其余源留在队列里等用户滑动或点"搜索更多来源"。
     *
     * <p>取 24 的依据:大于 [DEFAULT_BATCH_SIZE] 的一半,能在多数订阅里覆盖首页源 + 主流快源,
     * 又让"首轮"与"后续批"的体感差异明显 —— 用户先看到结果,再决定要不要全搜。
     */
    const val FAST_ROUND_SOURCE_LIMIT = 24

    private const val PREFETCH_ROWS = 3

    fun nextQueuedKeys(queuedKeys: List<String>, limit: Int = DEFAULT_BATCH_SIZE): List<String> =
        if (limit <= 0) emptyList() else queuedKeys.take(limit)

    /**
     * 把参与搜索的源分成"首轮快速源"与"其余按需源"(2026-10-08)。
     *
     * <p>快速源 = 首页源 + {@code quickSearch != 0} 的源;其余源排到后面。
     *
     * <p>⚠️ **不排除**任何源:慢源/非快速源只是延后,仍可通过"搜索更多来源"或滑动搜到。
     * 静默丢弃慢源会让"库里明明有这部片子却搜不到",那是准确度事故,不是优化。
     *
     * @param sources   已按用户勾选与 searchable 过滤、并排好序的源
     * @param penalized 本轮要**降权**的源(近期搜索超时,见 SourceHealthPolicy.shouldDefer)。
     *                  降权 ≠ 屏蔽:它们照搜,只是排到**整个队列的最后**,不占首轮的并发额度。
     * @return 首轮 key(去重)与延后 key(健康延后源在前、降权源垫底)
     */
    fun splitFastRoundSources(
        sources: List<SourceBean>,
        homeKey: String,
        penalized: Set<String> = emptySet(),
    ): Pair<List<String>, List<String>> {
        val fast = LinkedHashSet<String>()
        val healthyDeferred = ArrayList<String>()
        val penalizedDeferred = ArrayList<String>()
        for (source in sources) {
            val isHome = source.key == homeKey
            val isPenalized = penalized.isNotEmpty() && penalized.contains(source.key)
            // 首页源**永不被降权**:用户刚从这个源看到片子,最可能就在这,把它排到队尾等于"首轮一定慢"。
            // 这与 SourceHealthMemory 里"首页源不豁免"不矛盾 —— 那里说的是"照样记它的失败证据",
            // 不是"首轮不许它上"。
            if (isHome || (source.isQuickSearch() && !isPenalized)) {
                fast.add(source.key)
            } else if (isPenalized) {
                penalizedDeferred.add(source.key)
            } else {
                healthyDeferred.add(source.key)
            }
        }
        return fast.toList() to (healthyDeferred + penalizedDeferred)
    }

    /**
     * 把**降权源**排到队尾,**不改变候选取舍**(返回列表与入参元素完全相同,只变顺序)。
     *
     * <p>## 为什么单独有它,而不是复用 [splitFastRoundSources]
     * 那个函数回答的是"首轮快速源该放哪几个",会把**非快速源也一并延后**;
     * 而详情页换源链的候选池**已经**全是快速源(`isQuickSearch()` 已在池构造时过滤),
     * 它只需要"降权垫底"这一个动作。两者混用会让"换源链到底降了谁"变得难读。
     *
     * <p>## ⚠️ 调用方必须**先做上限截断(take)再调本函数**
     * 若先降权后截断,被降权的源会被上限直接砍掉 —— 那就不是"降权"而是"屏蔽"了,
     * 正是《源与线路的权重模型》第 2 节警告的"没有安全网的事实封禁"。
     * 本函数只重排不剔除,所以它本身没有这个风险;风险在调用顺序上。
     *
     * <p>刻意**不豁免首页源**(与 [splitFastRoundSources] 不同):换源链的当前源
     * 正是刚刚失败的那个,给它任何优先都没有依据;不豁免让语义更简单、少一个特例。
     *
     * @param sources   已完成过滤与上限截断的候选(顺序即原调度优先级)
     * @param penalized 本轮要降权的源 key(近期搜索超时,见 `SourceHealthPolicy.shouldDefer`)
     */
    fun penalizedLast(sources: List<SourceBean>, penalized: Set<String>): List<SourceBean> {
        if (sources.size < 2 || penalized.isEmpty()) return sources
        val normal = ArrayList<SourceBean>(sources.size)
        val deferred = ArrayList<SourceBean>()
        for (source in sources) {
            if (penalized.contains(source.key)) deferred.add(source) else normal.add(source)
        }
        // 一个降权源都没有 ⇒ 原样返回(省一次重排,也确实无需改动顺序)
        if (deferred.isEmpty()) return sources
        normal.addAll(deferred)
        return normal
    }

    /**
     * 本次应该启动多少个源:首轮用 [FAST_ROUND_SOURCE_LIMIT],之后每批恢复 [DEFAULT_BATCH_SIZE]。
     *
     * <p>用"队列里是否还含延后源"判断首轮:首轮只取快速源,队列一旦被消耗过就说明已经进过后续批。
     */
    fun batchSizeFor(queuedKeys: List<String>, isFirstRound: Boolean): Int {
        val limit = if (isFirstRound) FAST_ROUND_SOURCE_LIMIT else DEFAULT_BATCH_SIZE
        return limit.coerceAtMost(queuedKeys.size)
    }

    /** 首批/本批完全无命中时自动续搜,避免空屏被误认为全库无结果。 */
    fun shouldAutoContinue(hasAnyResult: Boolean, queuedSourceCount: Int): Boolean =
        !hasAnyResult && queuedSourceCount > 0

    // ==================== 详情换源链的"快探首批"(2026-10-13) ====================

    /**
     * 详情换源链**快探首批**的源数。
     *
     * <p>取值 = 搜索并发数(12):恰好**一个并发波次**。
     * 首批的目的不是"搜到全部",而是"尽快拿到**第一个可用候选**"——
     * 候选一到位就能起播,剩下的源照常在后批里搜完。
     */
    const val DETAIL_PROBE_SIZE = 12

    /**
     * 快探首批的单源超时(ms)。
     *
     * <p>常规单源超时是 8s(`SOURCE_SEARCH_TIMEOUT_MS`),而真机源超时率 75~83% ⇒
     * 首批 30 源 ÷ 并发 12 = 3 波 ≈ 最坏 24s,实测 **10.8s** 才等到第一个候选
     * (真机 15:17/15:18 两次起播实测:首批收口 +10.8s / +9.8s,而取流出画面只要 2.6~2.9s)。
     *
     * <p>取 4s:快源(1~2s)完全来得及;**慢源本来也来不及救首屏**,让它们占着并发额度
     * 只会把"第一个候选"往后拖 —— 它们仍会在后续批次里被搜到(准确度不变)。
     */
    const val DETAIL_PROBE_TIMEOUT_MS = 4_000L

    /**
     * 把详情换源链的候选切成"快探首批 + 其余按常规批"。
     *
     * <p>⚠️ **只改顺序与批次边界,不增删任何源** —— 全部源最终仍会被搜到,
     * 因此"库里明明有这部片却搜不到"的准确度风险为零(与 [splitFastRoundSources] 同一取向)。
     * 这与 `penalizedLast` 的"先 take 截断再降权"是两件事:那个管取舍,这个只管次序。
     *
     * @param keys      已排好优先级的候选(首页源在前、降权源垫底)
     * @param probeSize 快探首批源数
     * @param batchSize 其余批次的源数
     */
    fun <T> detailSearchBatches(
        keys: List<T>,
        probeSize: Int = DETAIL_PROBE_SIZE,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ): List<List<T>> {
        if (keys.isEmpty()) return emptyList()
        val probe = keys.take(probeSize.coerceAtLeast(1))
        val rest = keys.drop(probe.size)
        val out = ArrayList<List<T>>(1 + rest.size / batchSize.coerceAtLeast(1) + 1)
        out.add(probe)
        if (rest.isNotEmpty()) out.addAll(rest.chunked(batchSize.coerceAtLeast(1)))
        return out
    }

    /**
     * 某一批的单源超时:首批走快探超时,其余走常规超时。
     *
     * <p>单独抽成纯函数是为了让"首批必须短"这条口径可被单测钉住 ——
     * 它很容易在后续重构里被无意改回统一超时,而那正是起播慢的成因。
     */
    fun detailBatchTimeoutMs(
        batchIndex: Int,
        probeMs: Long = DETAIL_PROBE_TIMEOUT_MS,
        normalMs: Long = 8_000L,
    ): Long = if (batchIndex <= 0) probeMs else normalMs

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
