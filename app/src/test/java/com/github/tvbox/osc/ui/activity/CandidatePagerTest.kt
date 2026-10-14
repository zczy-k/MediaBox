package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选源分页判据单测。锁:每页 5、页数向上取整、双向翻页边界、越界钳制。
 */
class CandidatePagerTest {

    // ==================== pageCount ====================

    @Test
    fun pageCount_emptyAndSingle() {
        assertEquals(0, CandidatePager.pageCount(0))
        assertEquals(0, CandidatePager.pageCount(-3))
        assertEquals(1, CandidatePager.pageCount(1))
        assertEquals(1, CandidatePager.pageCount(5))
    }

    @Test
    fun pageCount_roundsUp() {
        assertEquals(2, CandidatePager.pageCount(6))
        assertEquals(2, CandidatePager.pageCount(10))
        assertEquals(3, CandidatePager.pageCount(11))
        assertEquals(21, CandidatePager.pageCount(101)) // 101/5 = 20.2 → 21
    }

    // ==================== pageSlice ====================

    @Test
    fun pageSlice_firstPage() {
        val list = (1..12).toList()
        assertEquals(listOf(1, 2, 3, 4, 5), CandidatePager.pageSlice(list, 0))
    }

    @Test
    fun pageSlice_lastPagePartial() {
        val list = (1..12).toList()
        assertEquals(listOf(11, 12), CandidatePager.pageSlice(list, 2))
    }

    @Test
    fun pageSlice_outOfRangeEmpty() {
        val list = (1..12).toList()
        assertTrue(CandidatePager.pageSlice(list, 3).isEmpty())
        assertTrue(CandidatePager.pageSlice(list, 99).isEmpty())
        assertTrue(CandidatePager.pageSlice(emptyList<Int>(), 0).isEmpty())
    }

    @Test
    fun pageSlice_negativeTreatedAsZero() {
        val list = listOf("a", "b")
        assertEquals(listOf("a", "b"), CandidatePager.pageSlice(list, -1))
    }

    @Test
    fun pageSlice_exactMultiple() {
        val list = (1..10).toList()
        assertEquals(listOf(1, 2, 3, 4, 5), CandidatePager.pageSlice(list, 0))
        assertEquals(listOf(6, 7, 8, 9, 10), CandidatePager.pageSlice(list, 1))
    }

    // ==================== hasNext / hasPrev ====================

    @Test
    fun hasNext_boundaries() {
        // 4 个(< 5):单页,无下一页
        assertFalse(CandidatePager.hasNext(total = 4, page = 0))
        // 5 个:恰好一页,无下一页
        assertFalse(CandidatePager.hasNext(total = 5, page = 0))
        // 6 个:第 0 页有下一页,第 1 页没有
        assertTrue(CandidatePager.hasNext(total = 6, page = 0))
        assertFalse(CandidatePager.hasNext(total = 6, page = 1))
        // 空列表:无下一页
        assertFalse(CandidatePager.hasNext(total = 0, page = 0))
    }

    @Test
    fun hasPrev_boundaries() {
        assertFalse(CandidatePager.hasPrev(0))
        assertFalse(CandidatePager.hasPrev(-1))
        assertTrue(CandidatePager.hasPrev(1))
        assertTrue(CandidatePager.hasPrev(3))
    }

    // ==================== clampPage ====================

    @Test
    fun clampPage_withinRangeUnchanged() {
        assertEquals(1, CandidatePager.clampPage(1, total = 12))
        assertEquals(0, CandidatePager.clampPage(0, total = 3))
    }

    @Test
    fun clampPage_overMaxClampedToLast() {
        // 12 个 = 3 页(0,1,2);页号 5 应钳到 2
        assertEquals(2, CandidatePager.clampPage(5, total = 12))
        // 列表缩短到 3 个 = 1 页;页号 2 应钳到 0
        assertEquals(0, CandidatePager.clampPage(2, total = 3))
    }

    @Test
    fun clampPage_emptyAlwaysZero() {
        assertEquals(0, CandidatePager.clampPage(0, total = 0))
        assertEquals(0, CandidatePager.clampPage(5, total = 0))
    }

    @Test
    fun clampPage_negativeToZero() {
        assertEquals(0, CandidatePager.clampPage(-2, total = 12))
    }
}