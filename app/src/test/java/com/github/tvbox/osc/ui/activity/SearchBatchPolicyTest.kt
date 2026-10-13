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

    // ---------- 详情换源链的"快探首批"(2026-10-13) ----------

    /**
     * 快探首批:只用**一个并发波次**的源、且超时远小于常规 8s,
     * 目的是尽快拿到"第一个可用候选"而不是搜完。
     *
     * <p>真机依据(15:17/15:18 两次起播实测):首批 30 源 ÷ 并发 12 = 3 波,
     * 首批收口要 +10.8s / +9.8s,而取流出画面只要 2.6~2.9s。
     */
    @Test
    fun detailProbeBatchIsOneWaveAndMuchShorterThanNormalTimeout() {
        // 首批必须恰好一个并发波次,否则又要等第二波
        assertEquals(12, SearchBatchPolicy.DETAIL_PROBE_SIZE)
        // 首批超时必须显著短于常规 8s —— 否则等于没改(这正是起播慢的成因)
        assertTrue(SearchBatchPolicy.DETAIL_PROBE_TIMEOUT_MS < 8_000L)
        assertEquals(
            SearchBatchPolicy.DETAIL_PROBE_TIMEOUT_MS,
            SearchBatchPolicy.detailBatchTimeoutMs(0, SearchBatchPolicy.DETAIL_PROBE_TIMEOUT_MS, 8_000L),
        )
        assertEquals(8_000L, SearchBatchPolicy.detailBatchTimeoutMs(1, SearchBatchPolicy.DETAIL_PROBE_TIMEOUT_MS, 8_000L))
        assertEquals(8_000L, SearchBatchPolicy.detailBatchTimeoutMs(7, SearchBatchPolicy.DETAIL_PROBE_TIMEOUT_MS, 8_000L))
        // 负数下标也按首批处理(防御:调用方不该传,但传了不能变成"用常规超时")
        assertEquals(
            SearchBatchPolicy.DETAIL_PROBE_TIMEOUT_MS,
            SearchBatchPolicy.detailBatchTimeoutMs(-1, SearchBatchPolicy.DETAIL_PROBE_TIMEOUT_MS, 8_000L),
        )
    }

    /**
     * 分批只改**次序与批次边界**,一个源都不能少、也不能重复 ——
     * 与"降权源只排后不剔除"是同一条硬约束:少了源就是"库里明明有却搜不到"。
     */
    @Test
    fun detailSearchBatchesProbeFirstAndDropsNothing() {
        val keys = (1..100).map { "S$it" }
        val batches = SearchBatchPolicy.detailSearchBatches(keys, probeSize = 12, batchSize = 30)
        // 首批 = 前 12 个(优先级最高的那批)
        assertEquals((1..12).map { "S$it" }, batches.first())
        // 其余按 30 一批:13..42 / 43..72 / 73..100
        assertEquals(listOf(30, 30, 28), batches.drop(1).map { it.size })
        // 一个都不能少、也不能重复,且总顺序与入参一致(只切边界)
        val flat = batches.flatten()
        assertEquals(keys, flat)
        assertEquals(keys.size, flat.toSet().size)
    }

    @Test
    fun detailSearchBatchesKeepsDegenerateInputsIntact() {
        assertTrue(SearchBatchPolicy.detailSearchBatches(emptyList<String>()).isEmpty())
        // 池子比快探批还小 ⇒ 只有一批,内容原样
        val small = listOf("a", "b", "c")
        val batches = SearchBatchPolicy.detailSearchBatches(small, probeSize = 12, batchSize = 30)
        assertEquals(1, batches.size)
        assertEquals(small, batches.first())
        // probeSize 传 0/负数时按 1 兜底:不能退化成"没有首批"(否则首批为空、白跑一轮)。
        // ⚠️ 兜底只影响**首批大小**:剩下的源仍会被照常分批,所以 3 个源 ⇒ [["a"], ["b","c"]]
        //    (第一版断言只写了 [["a"]],把"兜底"误当成"其余丢掉"—— 测试写错,代码行为正确)
        val degenerate = SearchBatchPolicy.detailSearchBatches(small, probeSize = 0, batchSize = 30)
        assertEquals(listOf(listOf("a"), listOf("b", "c")), degenerate)
        assertEquals(small, degenerate.flatten())
    }
}
