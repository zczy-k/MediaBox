package com.github.tvbox.osc.util

import com.github.tvbox.osc.util.PlayTitleMatch.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同名判据(2026-10-16)。真机那一条必须拦住,而别名/译名/写法差异必须放行 ——
 * 两半都要钉住:拦错=用户丢一个能看的源,放错=播成另一部片还算"播放成功"。
 */
class PlayTitleMatchTest {

    @Test
    fun `完全同名算本部片`() {
        assertEquals(Verdict.SAME, PlayTitleMatch.verdict("希望", "希望"))
        assertEquals("归一化吃掉空白与标点", Verdict.SAME, PlayTitleMatch.verdict("希望 ！", "希望"))
    }

    /** 站方把年份/画质/集数写在片名后面是常态,这些都还是同一部 */
    @Test
    fun `修饰后缀算本部片`() {
        assertEquals(Verdict.SAME, PlayTitleMatch.verdict("希望(2023)", "希望"))
        assertEquals(Verdict.SAME, PlayTitleMatch.verdict("希望 HD", "希望"))
        assertEquals(Verdict.SAME, PlayTitleMatch.verdict("希望 更新至20集", "希望"))
        assertEquals(Verdict.SAME, PlayTitleMatch.verdict("希望第2季", "希望"))
        assertEquals("反方向也要成立(本片名带后缀、源站写裸名)", Verdict.SAME, PlayTitleMatch.verdict("希望", "希望 正片 HD"))
    }

    /** 真机 v1.1.49 的那一条:同前缀续写片名 = 另一部电影 */
    @Test
    fun `同前缀但后面接的是正文就是另一部片`() {
        assertEquals(Verdict.DIFFERENT, PlayTitleMatch.verdict("希望一次过", "希望"))
        assertEquals(Verdict.DIFFERENT, PlayTitleMatch.verdict("希望之怒", "希望"))
        assertEquals("续集编号不是修饰词", Verdict.DIFFERENT, PlayTitleMatch.verdict("希望2", "希望"))
        assertEquals("反方向同理", Verdict.DIFFERENT, PlayTitleMatch.verdict("希望", "希望的一次过"))
    }

    /**
     * 判不了就放行 —— 这一档是这套判据的安全阀:
     * 错杀一条源等于让用户少一个能看的来源,比放过一部错片更糟。
     */
    @Test
    fun `别名译名繁简等写法差异一律放行`() {
        assertEquals(Verdict.UNKNOWN, PlayTitleMatch.verdict("My Hope", "希望"))
        assertEquals(Verdict.UNKNOWN, PlayTitleMatch.verdict("夢華錄", "梦华录"))
        assertEquals(Verdict.UNKNOWN, PlayTitleMatch.verdict("电影希望", "希望"))
    }

    @Test
    fun `空标题不参与判定`() {
        assertEquals(Verdict.UNKNOWN, PlayTitleMatch.verdict(null, "希望"))
        assertEquals(Verdict.UNKNOWN, PlayTitleMatch.verdict("", "希望"))
        assertEquals(Verdict.UNKNOWN, PlayTitleMatch.verdict("希望", ""))
        assertEquals(Verdict.UNKNOWN, PlayTitleMatch.verdict(null, null))
    }
}
