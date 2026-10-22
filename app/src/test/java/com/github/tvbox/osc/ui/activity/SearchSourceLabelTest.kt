package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索结果行的来源文字解析 + 「这个源是不是一站多网」的判定。
 *
 * <p>## 事故 1:源名冒充站名(2026-10-22 真机)
 * 用户的订阅里只有 **1 条源**,但那条源的爬虫是「多站合一」—— 一个 .py 里装着
 * sextb / av01 / javrate / 123av 四个网站,四站的搜索结果**合并在同一次回包**里,
 * 站名由爬虫写进 `vod_remarks`。结果行曾直接用 `SourceResult.sourceName`
 * (那条源的名字,"sextb_tvbox")当站名 ⇒ 四站结果全标成同一个名字,
 * 表现成"只搜到了 sextb"。[siteTagOf] 修的就是这个:站名必须从每条自己的 note 里取。
 *
 * <p>## 事故 2:单站源不该标站名(用户明确要求,反复强调两次)
 * "单站源搜索列表中不显示灰色字来源"。单站源的源名本身就是站名、首页已经露过一遍,
 * 搜索列表再标是纯噪音。判定落在 [isMultiSiteSource] —— 只有**同一个源的多次回包**
 * 里出现 ≥2 种反复出现的站名,才认它是"一站多网",才逐条标站名。
 */
class SearchSourceLabelTest {

    private fun video(note: String?): Movie.Video = Movie.Video().apply {
        name = "test"
        this.note = note
    }

    private fun videos(vararg notes: String?): List<Movie.Video> = notes.map { video(it) }

    // ---------- siteTagOf:这一条的站名叫什么 ----------

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
        // 单站源的 note 是"共30集""HD"这类,没有站名前缀 ⇒ null
        assertNull(siteTagOf(video("共30集,更新至12集")))
        assertNull(siteTagOf(video("HD")))
        assertNull(siteTagOf(video("")))
        assertNull(siteTagOf(video(null)))
    }

    @Test
    fun `中文备注不会被当成站名`() {
        // 「艾旦影视采集」这类没有分隔符;即便有分隔符,中文也过不了 ASCII 字母数字校验
        assertNull(siteTagOf(video("艾旦影视采集")))
        assertNull(siteTagOf(video("艾旦影视 · 采集")))
        assertNull(siteTagOf(video("天堂·资源")))
    }

    @Test
    fun `画质与编码备注被黑名单挡掉`() {
        // 这条是「单站源不许出现灰字」的第二道闸:单站源的备注里
        // "HD · 国语" / "BD · 粤语" 完全可能出现,单靠 ≥2 种类门槛会被误判成多站源。
        assertNull(siteTagOf(video("HD · 国语")))
        assertNull(siteTagOf(video("BD · 粤语")))
        assertNull(siteTagOf(video("1080P · 中字")))
        assertNull(siteTagOf(video("4K · 原盘")))
        assertNull(siteTagOf(video("TC · 抢先版")))
        assertNull(siteTagOf(video("WEB · 全集")))
        assertNull(siteTagOf(video("H265 · 10bit")))
        assertNull(siteTagOf(video("2024 · 新剧")))
    }

    @Test
    fun `纯数字头不是站名`() {
        // 至少要含一个字母 —— 否则"2024 · 新剧"这类会漏网
        assertNull(siteTagOf(video("2024 · 新剧")))
        assertNull(siteTagOf(video("30 · 集全")))
    }

    @Test
    fun `前缀相同但不是站名的串不能误匹配`() {
        // 站名在 note 头里必须是**完整 token**:SEXTBX / AV011 / JAVRATED 都不是站名。
        // 这里靠的是"整个头部必须匹配 ^[A-Za-z0-9]{2,12}$",长度与字符集一起收紧。
        // ⚠️ SEXTBX 长度 6、纯字母数字 ⇒ 字符集过不了,必须靠黑名单之外的手段拦:
        //    它与真实站名不同 ⇒ 在 isMultiSiteSource 里凑不出第二个**反复出现**的候选,
        //    单条污染不会让整个源变多站源(见下面的 `单条像站名的污染不会让单站源变成多站源`)。
        assertEquals("SEXTBX", siteTagOf(video("SEXTBX · 1 min")))
        assertEquals("AV011", siteTagOf(video("AV011 · x")))
        assertEquals("JAVRATED", siteTagOf(video("JAVRATED · x")))
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

    // ---------- isMultiSiteSource:这个源是不是一站多网 ----------

    @Test
    fun `四个站都有结果时判为多站源`() {
        assertTrue(
            isMultiSiteSource(
                videos(
                    "SEXTB · 1 min", "SEXTB · 2 min",
                    "AV01 · MIDA-474 | 150分", "AV01 · 318LADY-474",
                    "123AV · 1:53:52", "123AV · 1:08:07",
                    "JAVRATE · ADN-811",
                ),
            ),
        )
    }

    @Test
    fun `只有两个站也能判出来`() {
        assertTrue(isMultiSiteSource(videos("SEXTB · 1 min", "AV01 · x", "SEXTB · 2 min", "AV01 · y")))
    }

    @Test
    fun `单站源一个标签都不画`() {
        // 用户 2026-10-22 明确要求:单站源搜索列表中不显示灰色字来源。
        // 判据是 false ⇒ 调用方 sourceLabelOf 恒返回 null ⇒ 一条都不画。
        assertFalse(isMultiSiteSource(videos("共30集,更新至12集", "HD")))
        assertFalse(isMultiSiteSource(videos("HD", "1080P", "BD")))
        assertFalse(isMultiSiteSource(videos("某某影视", "某某影视")))
    }

    @Test
    fun `单条像站名的污染不会让单站源变成多站源`() {
        // 单站源里偶然有一条备注头看着像站名(如 "SEXTBX · 1 min"),其余都是普通备注。
        // 只有 1 种候选 ⇒ 不够 ≥2 种 ⇒ 仍是单站源 ⇒ 不画标签。
        // 这正是前面"前缀相同但不是站名"那组断言在整体判定层的意义。
        assertFalse(
            isMultiSiteSource(
                videos("SEXTBX · 1 min", "共12集", "HD · 1080P", "更新至8集", "完结"),
            ),
        )
    }

    @Test
    fun `画质备注反复出现也不会被误判成多站源`() {
        // 真机最容易出的一种误判:单站源里 "HD · 国语" 与 "BD · 粤语" 各来两条,
        // 种类凑够 2 且都重复 ≥2 次 —— 但它们是画质词,已在 siteTagOf 里被黑名单挡掉。
        assertFalse(
            isMultiSiteSource(
                videos("HD · 国语", "BD · 粤语", "HD · 国语", "BD · 粤语"),
            ),
        )
    }

    @Test
    fun `结果太少时不下多站源的结论`() {
        // 只有一条结果连"重复"都谈不上,判多站源没有依据 ⇒ 保守判 false(宁可漏标,不可误标)
        assertFalse(isMultiSiteSource(videos("AV01 · x")))
        assertFalse(isMultiSiteSource(emptyList()))
    }

    @Test
    fun `多站源里本轮只命中一站时不标`() {
        // 一处刻意接受的代价:sextb 这一轮只回来 2 条,站名候选只有 1 种 (SEXTB)。
        // 判 false ⇒ 不标。方向是**故意**的:用户要的是"单站源不许出现灰字",
        // 误标(单站源冒出灰字)比漏标(多站源少标一次)严重得多。
        assertFalse(isMultiSiteSource(videos("SEXTB · 1 min", "SEXTB · 2 min")))
    }

    // ---------- sourceLabelOf:结果行到底画不画 ----------

    @Test
    fun `多站源取本条自己的站名`() {
        // 这是事故 1 的核心:源名是"sextb_tvbox"(打包名),真实站名是 AV01
        assertEquals("AV01", sourceLabelOf(video("AV01 · MIDA-474 | 150分"), multiSite = true))
        assertEquals("123AV", sourceLabelOf(video("123AV · 1:53:52"), multiSite = true))
    }

    @Test
    fun `单站源恒不画`() {
        // 这是事故 2 的核心:不论 note 里写着什么,单站源一律 null。
        // ⚠️ 关键是它**不会回落成源名** —— "某某影视" 这种源名在首页已经露过,
        //    再在搜索列表标一遍正是用户要求去掉的东西。
        assertNull(sourceLabelOf(video("共30集"), multiSite = false))
        assertNull(sourceLabelOf(video(null), multiSite = false))
        assertNull(sourceLabelOf(video("某某影视"), multiSite = false))
        assertNull(sourceLabelOf(video("AV01 · x"), multiSite = false))
    }

    @Test
    fun `多站源里认不出站名的单条也不画`() {
        // 多站源里夹了一条没有站名前缀的结果(爬虫漏写)⇒ 返回 null ⇒ 那一行不画标签,
        // 而不是画一个空胶囊
        assertNull(sourceLabelOf(video("HD"), multiSite = true))
        assertNull(sourceLabelOf(video(null), multiSite = true))
    }
}
