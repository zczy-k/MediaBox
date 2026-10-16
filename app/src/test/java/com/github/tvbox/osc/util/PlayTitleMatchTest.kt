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

    /**
     * 真机 v1.1.58 回归(必须钉住):同一个爬虫源里,详情回包的标题与入口标题只差"谁被截短了尾巴",
     * 前缀规则会把它判成另一部片 ⇒ 记一次源失败 → 换源链候选 0 → 详情收口「此影片暂无法播放」,
     * 而画面其实一直在正常播。同片号 = 发行方的唯一身份 ⇒ 先判同一部。
     */
    @Test
    fun `同片号就是同一部,哪怕一方被截短`() {
        assertEquals(
            Verdict.SAME,
            PlayTitleMatch.verdict(
                "MFYD-197 “把老师的尿都喝光！”一位母爱泛滥的幼儿园",
                "MFYD-197 “把老师的尿都喝光！”一位母爱泛滥的幼儿园老师"
            )
        )
        assertEquals(Verdict.SAME, PlayTitleMatch.verdict("DVMM-457 我在妓院里", "DVMM-457 我在妓院里偶遇了班上最受欢迎的女生"))
        assertEquals(
            "多段编号也要认(CARIBBEANCOM-101626-001)",
            Verdict.SAME,
            PlayTitleMatch.verdict("CARIBBEANCOM-101626-001 我未婚夫的朋", "CARIBBEANCOM-101626-001 我未婚夫的朋友")
        )
        assertEquals("大小写与前导零不参与片号比较", Verdict.SAME, PlayTitleMatch.verdict("ssni-001 标题", "SSNI-1 标题的尾巴"))
    }

    @Test
    fun `两边都有片号却不相等就是另一部片`() {
        assertEquals(Verdict.DIFFERENT, PlayTitleMatch.verdict("SSNI-456 同一个系列标题", "SSNI-123 同一个系列标题"))
    }

    /** 只有一边提得出片号 ⇒ 这一维不作数,退回原有形状;绝不能把"提不到号"当成"号不同" */
    @Test
    fun `单边有片号时不参与比较`() {
        assertEquals(Verdict.UNKNOWN, PlayTitleMatch.verdict("ABC-123 一个英文标题", "希望"))
        assertEquals(Verdict.UNKNOWN, PlayTitleMatch.verdict("希望", "ABC-123 一个英文标题"))
    }

    /** 年份/集数不是片号(它们前面没有字母段),别把《希望 2023》判成另一部 */
    @Test
    fun `年份与集数不会被误当成片号`() {
        assertEquals(Verdict.SAME, PlayTitleMatch.verdict("希望 2023", "希望"))
        assertEquals(Verdict.SAME, PlayTitleMatch.verdict("希望 第2集", "希望"))
    }
}
