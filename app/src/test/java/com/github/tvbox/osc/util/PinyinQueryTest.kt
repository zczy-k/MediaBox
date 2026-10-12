package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拼音匹配纯逻辑单测。
 *
 * <p>本类只测 [PinyinQuery](无 Android 依赖)。汉字→拼音的转换依赖平台 ICU
 * ([PinyinTransliterator]),在 JVM 单测里没有 ICU 实现,因此**不在这里测** ——
 * 它的正确性靠真机验证,失败时会降级返回空串(不影响中文搜索)。
 *
 * <p>重点锁住三件事:
 * ① 全拼档位高于首字母(否则 `hy` 会把《红颜》压过《汉月》);
 * ② 首字母全等与首字母前缀**同分**(碰撞率高的信息不该产生强区分);
 * ③ `looksLikePinyin` 绝不把中文当拼音(否则搜「三体」会去索引里乱撞)。
 */
class PinyinQueryTest {

    // ==================== looksLikePinyin ====================

    @Test
    fun `纯字母与数字算拼音`() {
        assertTrue(PinyinQuery.looksLikePinyin("qjyf"))
        assertTrue(PinyinQuery.looksLikePinyin("QJYF"))
        assertTrue(PinyinQuery.looksLikePinyin("qian jun yi fa"))
        assertTrue(PinyinQuery.looksLikePinyin("qian-jun"))
        assertTrue(PinyinQuery.looksLikePinyin("miya195"))
    }

    @Test
    fun `含中文一律不算拼音`() {
        // 这是最关键的一条:若把「三体」当拼音,就会拿它去拼音索引里乱撞
        assertFalse(PinyinQuery.looksLikePinyin("三体"))
        assertFalse(PinyinQuery.looksLikePinyin("千钧一发"))
        assertFalse(PinyinQuery.looksLikePinyin("三体 3"))
        assertFalse(PinyinQuery.looksLikePinyin("三体qjyf"))
    }

    @Test
    fun `纯数字与空串不算拼音`() {
        // 纯数字更可能是番号或年份,按普通关键词搜更合适
        assertFalse(PinyinQuery.looksLikePinyin("12345"))
        assertFalse(PinyinQuery.looksLikePinyin(""))
        assertFalse(PinyinQuery.looksLikePinyin("   "))
        assertFalse(PinyinQuery.looksLikePinyin(null))
    }

    @Test
    fun `含标点不算拼音`() {
        assertFalse(PinyinQuery.looksLikePinyin("qjyf!"))
        assertFalse(PinyinQuery.looksLikePinyin("a.b"))
    }

    // ==================== normalize / compact ====================

    @Test
    fun `归一化去空格连字符与撇号`() {
        assertEquals("qianjunyifa", PinyinQuery.normalize("qian jun yi fa"))
        assertEquals("qianjunyifa", PinyinQuery.normalize("qian-jun-yi-fa"))
        assertEquals("qianjunyifa", PinyinQuery.normalize("Qian Jun Yi Fa"))
        assertEquals("xian", PinyinQuery.normalize("xi'an"))
        assertEquals("", PinyinQuery.normalize(null))
    }

    @Test
    fun `compact 与 normalize 同口径`() {
        assertEquals("hanyue", PinyinQuery.compact("han yue"))
        assertEquals("hanyue", PinyinQuery.compact("han-yue"))
    }

    // ==================== initials ====================

    @Test
    fun `首字母按音节边界取`() {
        assertEquals("hy", PinyinQuery.initials("han yue"))
        assertEquals("qjyf", PinyinQuery.initials("qian jun yi fa"))
        assertEquals("qjyf", PinyinQuery.initials("qian-jun-yi-fa"))
        assertEquals("", PinyinQuery.initials(""))
        assertEquals("", PinyinQuery.initials(null))
    }

    @Test
    fun `无音节分隔时退化为整串首字母`() {
        // 已压紧的输入切不出音节,只能给整串首字母 —— 调用方不该依赖这种情形
        assertEquals("h", PinyinQuery.initials("hanyue"))
    }

    @Test
    fun `ICU 带出的撇号当作音节分隔`() {
        // ICU 对 xi'an 这类音节会加撇号;若不处理,首字母会算错
        assertEquals("xa", PinyinQuery.initials("xi'an"))
    }

    // ==================== score:档位 ====================

    @Test
    fun `全拼全等得最高分`() {
        assertEquals(5, PinyinQuery.score("qian jun yi fa", "qianjunyifa"))
        assertEquals(5, PinyinQuery.score("han yue", "hanyue"))
    }

    @Test
    fun `全拼前缀与包含分档`() {
        assertEquals(4, PinyinQuery.score("qian jun yi fa", "qianjun"))
        assertEquals(3, PinyinQuery.score("qian jun yi fa", "junyi"))
    }

    @Test
    fun `首字母全等与前缀同分_都低于任何全拼命中`() {
        val initialsExact = PinyinQuery.score("han yue", "hy")
        val initialsPrefix = PinyinQuery.score("qian jun yi fa", "qj")
        assertEquals(2, initialsExact)
        assertEquals(2, initialsPrefix)
        // 关键:首字母命中必须低于全拼"包含"(3),否则信息量低的输入会压过信息量高的
        assertTrue(initialsExact < PinyinQuery.score("qian jun yi fa", "junyi"))
    }

    @Test
    fun `首字母包含得 1 分`() {
        assertEquals(1, PinyinQuery.score("qian jun yi fa", "jy"))
    }

    @Test
    fun `不匹配得 0 分`() {
        assertEquals(0, PinyinQuery.score("han yue", "zzz"))
        assertEquals(0, PinyinQuery.score("", "hy"))
        assertEquals(0, PinyinQuery.score("han yue", ""))
        assertEquals(0, PinyinQuery.score(null, "hy"))
    }

    @Test
    fun `matches 与 score 一致`() {
        assertTrue(PinyinQuery.matches("han yue", "hy"))
        assertTrue(PinyinQuery.matches("han yue", "hanyue"))
        assertFalse(PinyinQuery.matches("han yue", "zzz"))
    }

    /**
     * 真机场景:输入 `hy` 时《汉月》与《红颜》**必须同分**。
     *
     * <p>用户想要哪一个完全无法从 `hy` 判断 —— 此时应该并列,让后续的中文相关度
     * 与到达序决定,而不是让拼音打分去猜(猜错就是把用户要的片挤下去)。
     */
    @Test
    fun `首字母碰撞时并列而非强分高下`() {
        val hanyue = PinyinQuery.score("han yue", "hy")   // 《汉月》
        val hongyan = PinyinQuery.score("hong yan", "hy") // 《红颜》
        assertEquals("首字母碰撞必须同分", hanyue, hongyan)
    }

    /**
     * 真机主场景:搜 `qjyf` 应命中《千钧一发》。
     */
    @Test
    fun `qjyf 命中千钧一发`() {
        assertTrue(PinyinQuery.matches("qian jun yi fa", "qjyf"))
        assertTrue(PinyinQuery.matches("qian jun yi fa", "qianjunyifa"))
        assertTrue(PinyinQuery.matches("qian jun yi fa", "qianjun"))
        assertTrue(PinyinQuery.matches("qian jun yi fa", "qj"))
    }
}
