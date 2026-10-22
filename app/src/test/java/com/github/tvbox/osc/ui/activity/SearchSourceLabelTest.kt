package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 搜索结果行的来源文字解析。
 *
 * <p>为什么需要这份测试(2026-10-22 真机事故):用户的订阅里只有 **1 条源**,但那条源的
 * 爬虫是「多站合一」—— 一个 .py 里装着 sextb / av01 / javrate / 123av 四个网站,
 * 四站的搜索结果**合并在同一次回包**里,站名由爬虫写进 `vod_remarks`。
 *
 * <p>结果行曾直接用 `SourceResult.sourceName`(那条源的名字,"sextb_tvbox")当站名,
 * 于是四站的结果全都标成同一个名字,表现成"只搜到了 sextb"。
 * [siteTagOf] / [sourceLabelOf] 就是为了修这个 —— 站名必须从每条自己的 note 里取。
 */
class SearchSourceLabelTest {

    private fun video(note: String?): Movie.Video = Movie.Video().apply {
        name = "test"
        this.note = note
    }

    @Test
    fun `四个站的站名都能从真实回包里认出来`() {
        // 样本全部取自 2026-10-22 13:05 真机 logcat 的 searchContent 回包,未经改写
        assertEquals("SEXTB", siteTagOf(video("SEXTB · 1 min")))
        assertEquals("123AV", siteTagOf(video("123AV · 1:53:52")))
        assertEquals("AV01", siteTagOf(video("AV01 · MIDA-474 | 150分 | 播放6284")))
        assertEquals("JAVRATE", siteTagOf(video("JAVRATE · ADN-811")))
        assertEquals("AV01", siteTagOf(video("AV01 · 318LADY-474 | 60分 | 播放469")))
        assertEquals("123AV", siteTagOf(video("123AV · 1:08:07")))
    }

    @Test
    fun `普通源的备注不会被误认成站名`() {
        // 单站源的 note 是"共30集""HD"这类,没有站名前缀 → 必须返回 null 交给回落
        assertNull(siteTagOf(video("共30集,更新至12集")))
        assertNull(siteTagOf(video("HD")))
        assertNull(siteTagOf(video("")))
        assertNull(siteTagOf(video(null)))
    }

    @Test
    fun `前缀相同但不是站名的串不能误匹配`() {
        // \b 边界:SEXTBX 不是 SEXTB。没有这条,"SEXTB 番外"这类备注也会被误判。
        assertNull(siteTagOf(video("SEXTBX · 1 min")))
        assertNull(siteTagOf(video("AV011 · x")))
        assertNull(siteTagOf(video("JAVRATED · x")))
    }

    @Test
    fun `站名不在开头时不匹配`() {
        // 只认前缀。站名出现在标题中间(如某部片名里带 AV01)不能当成来源。
        assertNull(siteTagOf(video("推荐 AV01 的作品")))
    }

    @Test
    fun `前后空白不影响识别`() {
        assertEquals("AV01", siteTagOf(video("  AV01 · x  ")))
        assertEquals("SEXTB", siteTagOf(video("\tSEXTB · 1 min\n")))
    }

    @Test
    fun `取到真实站名时优先于源名`() {
        // 这是本次修复的核心:源名是"sextb_tvbox"(打包名),真实站名是 AV01
        assertEquals("AV01", sourceLabelOf(video("AV01 · MIDA-474 | 150分"), "sextb_tvbox"))
        assertEquals("123AV", sourceLabelOf(video("123AV · 1:53:52"), "sextb_tvbox"))
    }

    @Test
    fun `没有站名时回落到源名`() {
        // 普通单站源:源名本身就是站名,这是既有行为,不能被这次修复改坏
        assertEquals("某某影视", sourceLabelOf(video("共30集"), "某某影视"))
        assertEquals("某某影视", sourceLabelOf(video(null), "某某影视"))
    }

    @Test
    fun `站名和源名都取不到时返回 null`() {
        // 返回 null 时结果行不画标签,而不是画一个空胶囊
        assertNull(sourceLabelOf(video("HD"), null))
        assertNull(sourceLabelOf(video("HD"), ""))
        assertNull(sourceLabelOf(video("HD"), "   "))
    }
}
