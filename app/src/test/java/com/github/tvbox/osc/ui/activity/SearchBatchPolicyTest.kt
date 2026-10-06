package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchBatchPolicyTest {

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
}
