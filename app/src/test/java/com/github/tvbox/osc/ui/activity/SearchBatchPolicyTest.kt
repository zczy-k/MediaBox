package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.SourceBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchBatchPolicyTest {

    private fun source(key: String, quick: Boolean): SourceBean {
        val bean = SourceBean()
        bean.key = key
        // ⚠️ 必须调 setter:字段是 private,而 getter 叫 isQuickSearch()(布尔命名),
        // Kotlin 属性合成不认这组访问器,写 `bean.quickSearch = …` 会去碰私有字段而编译不过
        bean.setQuickSearch(if (quick) 1 else 0)
        return bean
    }

    @Test
    fun picksOnlyNextBatchAndHonorsNonPositiveLimit() {
        val queued = (1..200).map { "S$it" }
        val batch = SearchBatchPolicy.DEFAULT_BATCH_SIZE
        assertEquals((1..batch).map { "S$it" }, SearchBatchPolicy.nextQueuedKeys(queued))
        assertEquals(listOf("S1", "S2"), SearchBatchPolicy.nextQueuedKeys(queued, 2))
        assertTrue(SearchBatchPolicy.nextQueuedKeys(queued, 0).isEmpty())
        // 队列比一批还短时只取现有的,不能凭空补
        assertEquals(listOf("S1", "S2", "S3"), SearchBatchPolicy.nextQueuedKeys(listOf("S1", "S2", "S3")))
    }

    @Test
    fun defaultBatchCoversEnoughSourcesToBeUseful() {
        // 首批必须显著大于"一屏能看到的站点数",否则首批无命中时用户只会看到空屏
        assertTrue(SearchBatchPolicy.DEFAULT_BATCH_SIZE >= 20)
    }

    @Test
    fun autoContinuesOnlyWhenNoResultsAndSourcesRemain() {
        assertTrue(SearchBatchPolicy.shouldAutoContinue(hasAnyResult = false, queuedSourceCount = 2))
        assertFalse(SearchBatchPolicy.shouldAutoContinue(hasAnyResult = true, queuedSourceCount = 2))
        assertFalse(SearchBatchPolicy.shouldAutoContinue(hasAnyResult = false, queuedSourceCount = 0))
    }

    @Test
    fun scrollPrefetchRequiresActiveUserScrollNearEndAndAvailableSources() {
        assertTrue(SearchBatchPolicy.shouldLoadMoreOnScroll(true, 8, 10, true, false))
        assertFalse(SearchBatchPolicy.shouldLoadMoreOnScroll(false, 8, 10, true, false))
        assertFalse(SearchBatchPolicy.shouldLoadMoreOnScroll(true, 1, 10, true, false))
        assertFalse(SearchBatchPolicy.shouldLoadMoreOnScroll(true, 8, 10, false, false))
        assertFalse(SearchBatchPolicy.shouldLoadMoreOnScroll(true, 8, 10, true, true))
        assertFalse(SearchBatchPolicy.shouldLoadMoreOnScroll(true, -1, 0, true, false))
    }

    /**
     * 首轮只搜快速源(2026-10-08)。
     *
     * <p>断言的重点是"延后而非丢弃":慢源必须仍出现在延后列表里 —— 静默丢弃会让
     * "库里明明有这部片却搜不到",那是准确度事故,不是优化。
     */
    @Test
    fun fastRoundTakesHomeAndQuickSourcesButNeverDropsTheRest() {
        val sources = listOf(
            source("home", quick = false),
            source("quick1", quick = true),
            source("slow1", quick = false),
            source("quick2", quick = true),
            source("slow2", quick = false),
        )
        val (fast, deferred) = SearchBatchPolicy.splitFastRoundSources(sources, homeKey = "home")
        // 首页源即便 quickSearch=0 也必须进首轮:用户刚从这个源看到片子,最可能就在这
        assertEquals(listOf("home", "quick1", "quick2"), fast)
        assertEquals(listOf("slow1", "slow2"), deferred)
        // 全部源一个都不能少
        assertEquals(sources.size, (fast + deferred).toSet().size)
        // 顺序稳定:快速源在前,拼接后即"先快后全"
        assertEquals(listOf("home", "quick1", "quick2", "slow1", "slow2"), fast + deferred)
    }

    @Test
    fun fastRoundDedupesHomeWhenItIsAlsoQuick() {
        val sources = listOf(source("home", quick = true), source("x", quick = false))
        val (fast, deferred) = SearchBatchPolicy.splitFastRoundSources(sources, homeKey = "home")
        assertEquals(listOf("home"), fast)
        assertEquals(listOf("x"), deferred)
    }

    /**
     * 降权(2026-10-09):近期搜索超时的源排到队尾,但**照搜**。
     *
     * <p>断言三件事:① 降权的快源被挤出首轮;② 降权源排在健康延后源**之后**(真正的"队尾");
     * ③ 一个都没少 —— 降权不是屏蔽,静默丢弃就是准确度事故。
     */
    @Test
    fun penalizedSourcesGoToTailButAreNeverDropped() {
        val sources = listOf(
            source("home", quick = true),
            source("quickHealthy", quick = true),
            source("quickDead", quick = true),
            source("slowHealthy", quick = false),
            source("slowDead", quick = false),
        )
        val penalized = setOf("quickDead", "slowDead")
        val (fast, deferred) = SearchBatchPolicy.splitFastRoundSources(sources, homeKey = "home", penalized = penalized)
        assertEquals(listOf("home", "quickHealthy"), fast)
        // 健康的延后源在前,降权的垫底
        assertEquals(listOf("slowHealthy", "quickDead", "slowDead"), deferred)
        assertEquals(sources.size, (fast + deferred).toSet().size)
    }

    @Test
    fun homeSourceIsNeverPenalized() {
        // 首页源即便刚超时也必须进首轮:用户正从这个源浏览,把它排到队尾等于"首轮一定慢"。
        // 这与"照样记它的失败证据"不矛盾 —— 记不记,和首轮让不让它上,是两件事。
        //
        // 对照项刻意用一个**非快源**(quick=false):它只能靠 penalized 进延后列表,
        // 这样"首页源豁免"才是唯一让 home 留在首轮的原因。
        val sources = listOf(source("home", quick = false), source("x", quick = false))
        val (fast, deferred) = SearchBatchPolicy.splitFastRoundSources(
            sources,
            homeKey = "home",
            penalized = setOf("home"),
        )
        assertEquals(listOf("home"), fast)
        assertEquals(listOf("x"), deferred)
    }

    @Test
    fun penalizedUnknownKeysAreIgnored() {
        // 台账里可能留着已不在源清单里的 key(换仓/删源):不能因此改变任何源的位置
        val sources = listOf(source("a", quick = true), source("b", quick = false))
        val (fast, deferred) = SearchBatchPolicy.splitFastRoundSources(
            sources,
            homeKey = "home",
            penalized = setOf("已经不存在的源"),
        )
        assertEquals(listOf("a"), fast)
        assertEquals(listOf("b"), deferred)
    }

    @Test
    fun firstRoundUsesFastLimitAndLaterRoundsFallBackToDefault() {
        val queued = (1..500).map { "S$it" }
        assertEquals(
            SearchBatchPolicy.FAST_ROUND_SOURCE_LIMIT,
            SearchBatchPolicy.batchSizeFor(queued, isFirstRound = true),
        )
        assertEquals(
            SearchBatchPolicy.DEFAULT_BATCH_SIZE,
            SearchBatchPolicy.batchSizeFor(queued, isFirstRound = false),
        )
        // 队列比上限还短时只能取现有的,不能凭空补
        assertEquals(3, SearchBatchPolicy.batchSizeFor(listOf("a", "b", "c"), isFirstRound = true))
        assertEquals(0, SearchBatchPolicy.batchSizeFor(emptyList(), isFirstRound = false))
        // 首轮上限必须小于后续批,否则"快速首轮"没有意义(退化成普通分批)
        assertTrue(SearchBatchPolicy.FAST_ROUND_SOURCE_LIMIT < SearchBatchPolicy.DEFAULT_BATCH_SIZE)
    }

    // ---------- 换源链的"降权垫底"(2026-10-13) ----------

    /**
     * 换源链此前**只滤封禁、不读降权**,于是同一个慢源在搜索页被排到队尾、
     * 在换源链里却照旧占着前几批的并发额度。本组用例锁住它的两条硬约束:
     * ① 降权源垫底且保持原有相对顺序;② **元素一个不少**。
     *
     * <p>②是重点:调用方先 `take(上限)` 再降权,若这里把源剔掉,就从"降权"变成了"屏蔽" ——
     * 正是《源与线路的权重模型》第 2 节警告的"没有安全网的事实封禁"。
     */
    @Test
    fun penalizedLastMovesToTailAndDropsNothing() {
        val sources = listOf(
            source("a", quick = true),
            source("dead", quick = true),
            source("b", quick = true),
            source("dead2", quick = true),
            source("c", quick = true),
        )
        val ordered = SearchBatchPolicy.penalizedLast(sources, setOf("dead", "dead2"))
        // 健康的保持原相对顺序在前,降权的按原相对顺序垫底
        assertEquals(listOf("a", "b", "c", "dead", "dead2"), ordered.map { it.key })
        // 一个都不能少(降权 ≠ 屏蔽)
        assertEquals(sources.size, ordered.size)
        assertEquals(sources.map { it.key }.toSet(), ordered.map { it.key }.toSet())
    }

    @Test
    fun penalizedLastIsNoOpWithoutPenalizedSources() {
        val sources = listOf(source("a", quick = true), source("b", quick = true))
        // 没有降权源(或台账里只剩已不存在的 key)⇒ 原样返回,连重排都省掉
        assertSame(sources, SearchBatchPolicy.penalizedLast(sources, emptySet()))
        assertSame(sources, SearchBatchPolicy.penalizedLast(sources, setOf("已经不存在的源")))
    }

    @Test
    fun penalizedLastKeepsDegenerateInputsIntact() {
        val one = listOf(source("only", quick = true))
        assertSame(one, SearchBatchPolicy.penalizedLast(one, setOf("only")))
        val empty = emptyList<SourceBean>()
        assertSame(empty, SearchBatchPolicy.penalizedLast(empty, setOf("x")))
    }

    @Test
    fun penalizedLastIsStableWhenEverythingIsPenalized() {
        // 全部被降权 ⇒ 顺序必须与入参完全一致。否则"整池都慢"时会把原调度优先级彻底打乱,
        // 反而比不降权更差(比如把首页源排到了最后)。
        val sources = listOf(
            source("s1", quick = true),
            source("s2", quick = true),
            source("s3", quick = true),
        )
        val ordered = SearchBatchPolicy.penalizedLast(sources, setOf("s1", "s2", "s3"))
        assertEquals(listOf("s1", "s2", "s3"), ordered.map { it.key })
    }

    // ---------- 详情换源链的"快探"(2026-10-13) ----------

    /**
     * 快探窗口 = **一个并发波次**,且其超时远小于常规 8s。
     *
     * <p>真机依据(15:17/15:18 两次起播实测):30 源 ÷ 并发 12 = 3 波,
     * 首批收口要 +10.8s / +9.8s,而取流出画面只要 2.6~2.9s。
     *
     * <p>⚠️ 这里钉的是**超时窗口**,不是"批次":源不再被切批(批次屏障是"第一个候选
     * 被推后 8~16s"的成因,见 startSourceSearch 的说明)。前 12 个源只是先拿到额度的那批。
     */
    @Test
    fun detailProbeWindowIsOneWaveAndMuchShorterThanNormalTimeout() {
        // 窗口必须恰好一个并发波次:短超时若覆盖到第二波,就等于"第二批源还没来得及跑就被掐"
        assertEquals(12, SearchBatchPolicy.DETAIL_PROBE_SIZE)
        // 窗口超时必须显著短于常规 8s —— 否则等于没改(这正是起播慢的成因)
        assertTrue(SearchBatchPolicy.DETAIL_PROBE_TIMEOUT_MS < 8_000L)
        // 窗口内(前 12 个)一律短路超时;窗口外一律常规,与下标大小无关
        for (i in 0 until SearchBatchPolicy.DETAIL_PROBE_SIZE) {
            assertEquals(
                SearchBatchPolicy.DETAIL_PROBE_TIMEOUT_MS,
                SearchBatchPolicy.detailSourceTimeoutMs(i, SearchBatchPolicy.DETAIL_PROBE_SIZE,
                    SearchBatchPolicy.DETAIL_PROBE_TIMEOUT_MS, 8_000L),
            )
        }
        assertEquals(
            8_000L,
            SearchBatchPolicy.detailSourceTimeoutMs(SearchBatchPolicy.DETAIL_PROBE_SIZE,
                SearchBatchPolicy.DETAIL_PROBE_SIZE, SearchBatchPolicy.DETAIL_PROBE_TIMEOUT_MS, 8_000L),
        )
        assertEquals(8_000L, SearchBatchPolicy.detailSourceTimeoutMs(119, 12, 4_000L, 8_000L))
    }

    /**
     * 负数下标**不得**落进窗口:那是"最后一个源"的写法(如 `size - 1` 的误算),
     * 判成快探会让它在 4s 被掐掉 —— 比不做快探更糟。
     */
    @Test
    fun detailSourceTimeoutTreatsNegativeIndexAsOutOfWindow() {
        assertEquals(8_000L, SearchBatchPolicy.detailSourceTimeoutMs(-1, 12, 4_000L, 8_000L))
        assertEquals(8_000L, SearchBatchPolicy.detailSourceTimeoutMs(-7, 12, 4_000L, 8_000L))
    }

    /**
     * 冻结判据(2026-10-13,P1):**两个条件同时成立**才冻结 ——
     * 没起播时搜索是"起播失败"的救命通道,不能冻;已收工的搜索不该被再置一次状态。
     */
    @Test
    fun freezesSearchOnlyWhenPlaybackStartedAndSearchStillRunning() {
        assertTrue(SearchBatchPolicy.shouldFreezeOnPlaybackStarted(playbackStarted = true, searchRunning = true))
        assertFalse(SearchBatchPolicy.shouldFreezeOnPlaybackStarted(playbackStarted = false, searchRunning = true))
        assertFalse(SearchBatchPolicy.shouldFreezeOnPlaybackStarted(playbackStarted = true, searchRunning = false))
        assertFalse(SearchBatchPolicy.shouldFreezeOnPlaybackStarted(playbackStarted = false, searchRunning = false))
    }

    // ==================== 弱网超时自适应(P2-2) ====================

    @Test
    fun batchTimeout_defaultWhenEnoughAnswers() {
        // 样本够大但超时不过半 ⇒ 常规超时
        assertEquals(
            SearchBatchPolicy.BATCH_TIMEOUT_MS,
            SearchBatchPolicy.batchTimeoutMs(settledCount = 50, timeoutCount = 10),
        )
    }

    @Test
    fun batchTimeout_degradedWhenTimeoutMajority() {
        // 超时 ≥ 一半 ⇒ 降级
        assertEquals(
            SearchBatchPolicy.BATCH_TIMEOUT_DEGRADED_MS,
            SearchBatchPolicy.batchTimeoutMs(settledCount = 50, timeoutCount = 25),
        )
        assertEquals(
            SearchBatchPolicy.BATCH_TIMEOUT_DEGRADED_MS,
            SearchBatchPolicy.batchTimeoutMs(settledCount = 10, timeoutCount = 9),
        )
    }

    @Test
    fun batchTimeout_smallSampleNotDegraded() {
        // 小批次的比例不可信:1/2 过半但不降级
        assertEquals(
            SearchBatchPolicy.BATCH_TIMEOUT_MS,
            SearchBatchPolicy.batchTimeoutMs(settledCount = 2, timeoutCount = 2),
        )
        assertEquals(
            SearchBatchPolicy.BATCH_TIMEOUT_MS,
            SearchBatchPolicy.batchTimeoutMs(
                settledCount = SearchBatchPolicy.MIN_SAMPLE_FOR_DEGRADE - 1,
                timeoutCount = SearchBatchPolicy.MIN_SAMPLE_FOR_DEGRADE - 1,
            ),
        )
    }

    @Test
    fun batchTimeout_boundaryRatio() {
        // 恰好 8 个全超时 ⇒ 降级;7 个全超时 ⇒ 不降(样本不足)
        assertEquals(
            SearchBatchPolicy.BATCH_TIMEOUT_DEGRADED_MS,
            SearchBatchPolicy.batchTimeoutMs(settledCount = 8, timeoutCount = 8),
        )
        assertEquals(
            SearchBatchPolicy.BATCH_TIMEOUT_MS,
            SearchBatchPolicy.batchTimeoutMs(settledCount = 7, timeoutCount = 7),
        )
    }

    // ---------- 详情换源链并发自适应(2026-10-14) ----------

    @Test
    fun detailConcurrencyCap_halvesUserThreadsAndClamps() {
        // 默认 32 线程 ⇒ 详情页 16(封顶);64 也还是 16 —— 换源并发要给正在缓冲的播放流让路
        assertEquals(16, SearchBatchPolicy.detailConcurrencyCap(32))
        assertEquals(16, SearchBatchPolicy.detailConcurrencyCap(64))
        assertEquals(8, SearchBatchPolicy.detailConcurrencyCap(16))
        // 极小值不许低于下限
        assertEquals(SearchBatchPolicy.DETAIL_CONCURRENCY_MIN, SearchBatchPolicy.detailConcurrencyCap(1))
    }

    @Test
    fun nextDetailConcurrency_weakNetworkHalvesHealthyClimbsBack() {
        val cap = 16
        // 超时占比过半 ⇒ 砍一半(16 → 8)
        assertEquals(8, SearchBatchPolicy.nextDetailConcurrency(20, 12, 16, cap))
        // 占比不到一半 ⇒ +4 逐步升回上限,且绝不超过上限
        assertEquals(12, SearchBatchPolicy.nextDetailConcurrency(20, 4, 8, cap))
        assertEquals(cap, SearchBatchPolicy.nextDetailConcurrency(20, 0, 16, cap))
        // 再砍也不低于下限
        assertEquals(
            SearchBatchPolicy.DETAIL_CONCURRENCY_MIN,
            SearchBatchPolicy.nextDetailConcurrency(20, 20, 4, cap),
        )
    }

    @Test
    fun nextDetailConcurrency_needsSameSampleAsTimeoutDegrade() {
        // 判据与 batchTimeoutMs 共用同一最小样本:小批次的比例不可信(1/2 也过半)
        assertEquals(
            12,
            SearchBatchPolicy.nextDetailConcurrency(settledCount = 7, timeoutCount = 7, current = 12, cap = 16),
        )
    }
}
