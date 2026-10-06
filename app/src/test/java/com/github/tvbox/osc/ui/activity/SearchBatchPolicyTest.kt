package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchBatchPolicyTest {

    @Test
    fun picksOnlyNextBatchAndHonorsNonPositiveLimit() {
        val queued = (1..20).map { "S$it" }
        assertEquals((1..8).map { "S$it" }, SearchBatchPolicy.nextQueuedKeys(queued))
        assertEquals(listOf("S1", "S2"), SearchBatchPolicy.nextQueuedKeys(queued, 2))
        assertTrue(SearchBatchPolicy.nextQueuedKeys(queued, 0).isEmpty())
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
