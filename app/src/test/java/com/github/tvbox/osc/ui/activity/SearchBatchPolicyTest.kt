package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.SourceBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchBatchPolicyTest {

    private fun source(key: String, quick: Boolean): SourceBean {
        val bean = SourceBean()
        bean.key = key
        bean.quickSearch = if (quick) 1 else 0
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
}
