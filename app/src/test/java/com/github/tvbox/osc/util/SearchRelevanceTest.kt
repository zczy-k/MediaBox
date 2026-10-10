package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相关度排序的正片优先规则(2026-10-10):
 * 正片(任何命中档) > 非正片(预告/花絮/幕后/特辑/片花,压到 ≤2) > 纯别名命中(1)。
 */
class SearchRelevanceTest {

    @Test
    fun `正片贴词高分不受影响`() {
        assertEquals(5, SearchSettings.relevanceScore("花开锦绣", null, "花开锦绣"))
        assertEquals(4, SearchSettings.relevanceScore("花开锦绣2026", null, "花开锦绣"))
        assertEquals(3, SearchSettings.relevanceScore("花开锦绣 电视版", null, "花开锦绣"))
    }

    @Test
    fun `预告类标题即使贴词也压到正片之下`() {
        val main = SearchSettings.relevanceScore("花开锦绣", null, "花开锦绣")
        val trailer = SearchSettings.relevanceScore("花开锦绣 预告", null, "花开锦绣")
        val trailerExact = SearchSettings.relevanceScore("花开锦绣预告", null, "花开锦绣")
        assertTrue(trailer in 1..2)
        assertTrue(trailerExact in 1..2)
        assertTrue(main > trailer)
        // 正片"包含"命中(3) 也压过预告的"几乎全等"(2)
        val partial = SearchSettings.relevanceScore("花开锦绣之花好月圆", null, "花开锦绣")
        assertTrue(partial > trailerExact)
    }

    @Test
    fun `note命中预告同样降权`() {
        assertEquals(2, SearchSettings.relevanceScore("花开锦绣", "官方预告片", "花开锦绣"))
        assertEquals(5, SearchSettings.relevanceScore("花开锦绣", "共36集", "花开锦绣"))
        assertEquals(5, SearchSettings.relevanceScore("花开锦绣", "国语中字", "花开锦绣"))
    }

    @Test
    fun `花絮_幕后_特辑_片花同样降权`() {
        for (suffix in listOf("花絮", "幕后", "特辑", "片花")) {
            val s = SearchSettings.relevanceScore("花开锦绣$suffix", null, "花开锦绣")
            assertTrue(suffix, s in 1..2)
        }
    }

    @Test
    fun `关键词本身含预告词时不降权_搜索意图豁免`() {
        assertEquals(5, SearchSettings.relevanceScore("花开锦绣 预告", null, "花开锦绣 预告"))
        assertEquals(3, SearchSettings.relevanceScore("花开锦绣 先导预告", null, "花开锦绣 预告"))
        assertFalse(SearchSettings.isNonMainContent("某片预告合集", null, "预告"))
    }

    @Test
    fun `非正片只降权不过滤_仍可被搜索命中`() {
        assertTrue(SearchSettings.matches("花开锦绣 预告", null, "花开锦绣", SearchSettings.MatchMode.Smart))
        assertEquals(2, SearchSettings.relevanceScore("花开锦绣 预告", null, "花开锦绣"))
    }

    @Test
    fun `降权后仍高于纯别名命中`() {
        // 别名完全相等 = 1;预告类标题命中 = 2
        assertEquals(
            1,
            SearchSettings.relevanceScore("完全无关的标题", "花开锦绣", "花开锦绣"),
        )
        assertEquals(
            2,
            SearchSettings.relevanceScore("花开锦绣 片花", "花开锦绣海外版", "花开锦绣"),
        )
    }
}
