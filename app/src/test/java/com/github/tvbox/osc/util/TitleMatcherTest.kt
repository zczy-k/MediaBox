package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TitleMatcher] 纯函数单测:归一化、同片判定、保守字符集边界。 */
class TitleMatcherTest {

    // ==================== normalize ====================

    @Test
    fun `全角折半角`() {
        assertEquals("功夫女足2026", TitleMatcher.normalize("功夫女足２０２６"))
    }

    @Test
    fun `空白与常见标点被剔除`() {
        assertEquals("功夫女足", TitleMatcher.normalize(" 功 夫 女 足 "))
        assertEquals("功夫女足", TitleMatcher.normalize("《功夫女足》"))
        assertEquals("功夫女足2026", TitleMatcher.normalize("功夫女足：2026"))
        assertEquals("功夫女足2026", TitleMatcher.normalize("功夫女足: 2026!"))
    }

    @Test
    fun `大小写归一`() {
        assertEquals("thelovehypothesis", TitleMatcher.normalize("The Love Hypothesis"))
    }

    @Test
    fun `空与只剩标点的输入归一为空串`() {
        assertEquals("", TitleMatcher.normalize(null))
        assertEquals("", TitleMatcher.normalize(""))
        assertEquals("", TitleMatcher.normalize("《》：！"))
    }

    // ==================== isSameTitle ====================

    @Test
    fun `同片不同写法判同`() {
        assertTrue(TitleMatcher.isSameTitle("功夫女足", "《功夫女足》"))
        assertTrue(TitleMatcher.isSameTitle("功夫女足", "功 夫 女 足"))
        assertTrue(TitleMatcher.isSameTitle("功夫女足：2026", "功夫女足-2026"))
        assertTrue(TitleMatcher.isSameTitle("功夫女足２０２６", "功夫女足2026"))
        assertTrue(TitleMatcher.isSameTitle("The Love Hypothesis", "the-love-hypothesis"))
    }

    @Test
    fun `不同片判不同`() {
        assertFalse(TitleMatcher.isSameTitle("功夫女足", "功夫女篮"))
        assertFalse(TitleMatcher.isSameTitle("爱情假说", "爱情假说2"))
    }

    @Test
    fun `空标题或只剩标点不算同片(宁漏不误合)`() {
        assertFalse(TitleMatcher.isSameTitle(null, "功夫女足"))
        assertFalse(TitleMatcher.isSameTitle("", "功夫女足"))
        assertFalse(TitleMatcher.isSameTitle("《》", "功夫女足"))
        assertFalse(TitleMatcher.isSameTitle("《》：", "：！"))
    }

    @Test
    fun `可能参与片名的字符保留(保守集)`() {
        // "3%" 与 "3" 不是同一部片:% 参与片名区分,不能删
        assertFalse(TitleMatcher.isSameTitle("3%", "3"))
        assertTrue(TitleMatcher.isSameTitle("3%", "３ %"))
    }
}
