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
 * <p>重点锁住四件事:
 * ① **只认首字母**:全拼输入不再命中(2026-10-12 收敛,TV 端才是主场景);
 * ② 首字母全等与前缀**同分**(碰撞率高的信息不该产生强区分);
 * ③ `looksLikePinyin` 绝不把中文/番号当拼音(否则搜「三体」或 `MIAA-195` 会去索引里乱撞);
 * ④ 解析失败**绝不拒绝服务**(退回普通搜索,见 SearchViewModel)。
 */
class PinyinQueryTest {

    // ==================== looksLikePinyin ====================

    @Test
    fun `短字母串算首字母输入`() {
        assertTrue(PinyinQuery.looksLikePinyin("qjyf"))
        assertTrue(PinyinQuery.looksLikePinyin("QJYF"))
        assertTrue(PinyinQuery.looksLikePinyin("hy"))
        assertTrue(PinyinQuery.looksLikePinyin("qj"))
    }

    @Test
    fun `带分隔符的首字母仍算`() {
        assertTrue(PinyinQuery.looksLikePinyin("qj-yf"))
        assertTrue(PinyinQuery.looksLikePinyin("qj yf"))
    }

    /**
     * 超过 [PinyinQuery.MAX_INITIALS_LEN] 的字母串不再当拼音。
     *
     * <p>既然只匹配首字母,合法输入就是"2~6 个字母";更长的更可能是
     * 用户在敲英文片名(`thematrix`)或误粘了东西 —— 去拼音索引试也几乎必然落空。
     */
    @Test
    fun `过长的字母串不算首字母输入`() {
        assertFalse(PinyinQuery.looksLikePinyin("qianjunyifa"))
        assertFalse(PinyinQuery.looksLikePinyin("qian jun yi fa"))
        assertFalse(PinyinQuery.looksLikePinyin("thematrix"))
    }

    /**
     * ⚠️ 真机回归(2026-10-12):**番号绝不能判成拼音**。
     *
     * <p>初版允许数字,于是 `MIAA-195` 这类番号被判成拼音 ⇒ 本地索引必然查不到
     * ⇒ 走进"解析失败"分支 ⇒ 用户看到"暂无法识别,先搜一次中文片名即可建立",
     * **番号搜索整条主路径被弄坏**(用户实测确认)。
     *
     * <p>而番号搜索是本 App 的主要用例(`SearchSettings.parseIntent` 专门识别
     * `INTENT_CODE_TOKEN` 并做冲突保护)。
     *
     * <p>判据:含数字即不判为拼音 —— 拼音输入几乎不会出现"字母紧接数字",
     * 而番号的特征正是"字母块 + 数字块"。
     */
    @Test
    fun `番号不得判成拼音_真机回归`() {
        assertFalse("MIAA-195 是番号,不是拼音", PinyinQuery.looksLikePinyin("MIAA-195"))
        assertFalse(PinyinQuery.looksLikePinyin("miya195"))
        assertFalse(PinyinQuery.looksLikePinyin("SSIS-001"))
        assertFalse(PinyinQuery.looksLikePinyin("FC2-PPV"))
        assertFalse(PinyinQuery.looksLikePinyin("abc123"))
        assertFalse(PinyinQuery.looksLikePinyin("IPX 580"))
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
        // 纯数字更可能是番号或年份,按普通关键词搜更合适。
        // (含数字的字母串同样否决,见 `番号不得判成拼音_真机回归`)
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

    // ==================== normalize ====================

    @Test
    fun `归一化去空格连字符与撇号`() {
        assertEquals("qianjunyifa", PinyinQuery.normalize("qian jun yi fa"))
        assertEquals("qianjunyifa", PinyinQuery.normalize("qian-jun-yi-fa"))
        assertEquals("qianjunyifa", PinyinQuery.normalize("Qian Jun Yi Fa"))
        assertEquals("xian", PinyinQuery.normalize("xi'an"))
        assertEquals("", PinyinQuery.normalize(null))
    }

    @Test
    fun `compact 已移除_全拼不再参与匹配`() {
        // 2026-10-12 收敛为"只认首字母"后,compact 已从产品代码删除。
        // 这里改为断言"全拼输入不再命中",锁住这次收敛的意图 ——
        // 否则将来有人"顺手"把全拼档加回来,没有任何测试会红。
        assertEquals(0, PinyinQuery.score("qian jun yi fa", "qianjunyifa"))
        assertEquals(0, PinyinQuery.score("han yue", "hanyue"))
        assertEquals(0, PinyinQuery.score("qian jun yi fa", "qianjun"))
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

    // ==================== score:档位(只认首字母) ====================

    @Test
    fun `首字母全等与前缀同分`() {
        assertEquals(2, PinyinQuery.score("han yue", "hy"))
        assertEquals(2, PinyinQuery.score("qian jun yi fa", "qj"))
        assertEquals(2, PinyinQuery.score("qian jun yi fa", "qjyf"))
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
        assertFalse(PinyinQuery.matches("han yue", "zzz"))
        // 全拼不再命中(2026-10-12 收敛为只认首字母)
        assertFalse(PinyinQuery.matches("han yue", "hanyue"))
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
     * 真机主场景(TV 端):按 4 个键 `qjyf` 应命中《千钧一发》。
     *
     * <p>这是收敛为"只认首字母"后**唯一**需要支持的输入形态 ——
     * 遥控器上按 10+ 个字母的全拼不现实。
     */
    @Test
    fun `qjyf 命中千钧一发`() {
        assertTrue(PinyinQuery.matches("qian jun yi fa", "qjyf"))
        assertTrue(PinyinQuery.matches("qian jun yi fa", "qj"))
    }
}
