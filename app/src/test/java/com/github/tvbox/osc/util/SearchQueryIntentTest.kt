package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查询意图解析与冲突保护(2026-10-10,《多语言排序引擎》§6/§7.5/§10.3):
 * 结构化约束只在可靠识别时产出;一致加分、明确冲突惩罚、缺失不判死。
 */
class SearchQueryIntentTest {

    // ==================== parseIntent ====================

    @Test
    fun `年份独立token被识别`() {
        val i = SearchSettings.parseIntent("花开锦绣 2024")
        assertEquals(2024, i.year)
        assertEquals(0, i.season)
        assertEquals("", i.code)
    }

    @Test
    fun `中文数字季数被识别`() {
        assertEquals(2, SearchSettings.parseIntent("某作品 第二季").season)
        assertEquals(3, SearchSettings.parseIntent("某作品 第三季").season)
        assertEquals(12, SearchSettings.parseIntent("某作品 第12季").season)
        assertEquals(2, SearchSettings.parseIntent("某作品 S02").season)
        assertEquals(2, SearchSettings.parseIntent("某作品 S2").season)
    }

    @Test
    fun `番号被识别为归一化码`() {
        assertEquals("miaa195", SearchSettings.parseIntent("MIAA-195").code)
        assertEquals("miaa195", SearchSettings.parseIntent("MIAA-195 深田").code)
        assertEquals("abc123", SearchSettings.parseIntent("ABC-123 中文标题").code)
    }

    @Test
    fun `普通标题不产生结构化约束`() {
        val i = SearchSettings.parseIntent("花开锦绣")
        assertEquals(0, i.year)
        assertEquals(0, i.season)
        assertEquals("", i.code)
        val i2 = SearchSettings.parseIntent("流浪地球2")
        assertEquals(0, i2.year)
        assertEquals(0, i2.season)
        assertEquals("", i2.code)
    }

    @Test
    fun `字母加年份数字不误判为番号`() {
        val i = SearchSettings.parseIntent("HD2024 花开锦绣")
        assertEquals("", i.code)
        assertEquals(2024, i.year)
    }

    // ==================== intentModifier ====================

    @Test
    fun `年份一致加分_冲突惩罚_缺失不判死`() {
        val intent = SearchSettings.parseIntent("花开锦绣 2024")
        assertEquals(1, SearchSettings.intentModifier(intent, "花开锦绣", 2024))
        // 标题自带年份优先于源字段:2026 与查询 2024 冲突
        assertEquals(-2, SearchSettings.intentModifier(intent, "花开锦绣2026", 0))
        assertEquals(0, SearchSettings.intentModifier(intent, "花开锦绣", 0))
    }

    @Test
    fun `季数一致加分_冲突惩罚_缺失不判死`() {
        val intent = SearchSettings.parseIntent("某作品 第二季")
        assertEquals(1, SearchSettings.intentModifier(intent, "某作品 第二季", 0))
        assertEquals(-2, SearchSettings.intentModifier(intent, "某作品 第一季", 0))
        assertEquals(0, SearchSettings.intentModifier(intent, "某作品", 0))
    }

    @Test
    fun `番号命中强证据_不同番号明确冲突_无番号不视为冲突`() {
        val intent = SearchSettings.parseIntent("MIAA-195")
        assertEquals(2, SearchSettings.intentModifier(intent, "MIAA-195 深田えいみ", 0))
        assertEquals(-2, SearchSettings.intentModifier(intent, "MIAA-196 深田えいみ", 0))
        assertEquals(0, SearchSettings.intentModifier(intent, "完全无关的标题", 0))
    }

    @Test
    fun `组合排序_正确番号远超编号冲突_描述词不能救回冲突`() {
        // 查询:番号+描述词。A=编号对+描述词对;B=编号错+描述词对(文档 §12.3:大量描述词不能压过编号冲突)
        val keyword = "MIAA-195 深田"
        val a = SearchSettings.relevanceScore("MIAA-195 深田えいみ", null, keyword) +
            SearchSettings.intentModifier(SearchSettings.parseIntent(keyword), "MIAA-195 深田えいみ", 0)
        val b = SearchSettings.relevanceScore("MIAA-196 深田えいみ", null, keyword) +
            SearchSettings.intentModifier(SearchSettings.parseIntent(keyword), "MIAA-196 深田えいみ", 0)
        assertTrue("A=$a B=$b", a > b)
        assertTrue(a >= 5)
        assertTrue(b < 0)
    }
}
