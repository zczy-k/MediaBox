package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SearchDedupPolicy] 纯函数单测:身份相容、同名不同片拆组、组序稳定性。
 * 刻意不引入 [SearchSettings](其对象初始化依赖 Android KV),归一化由调用方注入 ——
 * 归一化本身的口径已在 TitleMatcher/SearchSettings 侧覆盖。
 */
class SearchDedupPolicyTest {

    private data class Item(val id: String, val idn: SearchDedupPolicy.FilmIdentity)

    /**
     * 模拟调用方(搜索页)的归一化契约:策略收到的 titleKey 是**已归一化**的标题
     * (生产环境走 SearchSettings.normalizedTitle)。测试里用等价的简化归一化。
     */
    private fun normalizeTitle(t: String): String =
        t.filter { !it.isWhitespace() && !PUNCT.contains(it) }.lowercase()

    private val PUNCT = "：:．.,，、()（）[]【】《》-—_·"

    private fun item(
        id: String,
        title: String,
        year: Int = 0,
        area: String = "",
        type: String = "",
        note: String = "",
    ) = Item(id, SearchDedupPolicy.FilmIdentity(normalizeTitle(title), year, area, type, SearchDedupPolicy.normalizeNote(note)))

    private fun group(items: List<Item>) = SearchDedupPolicy.group(items) { it.idn }

    // ==================== compatible ====================

    @Test
    fun `年份双方确知且不同即不相容`() {
        val a = SearchDedupPolicy.FilmIdentity("x", 2004, "", "")
        val b = SearchDedupPolicy.FilmIdentity("x", 2017, "", "")
        assertFalse(SearchDedupPolicy.compatible(a, b))
    }

    @Test
    fun `任一方年份未知不算冲突`() {
        val known = SearchDedupPolicy.FilmIdentity("x", 2004, "", "")
        val unknown = SearchDedupPolicy.FilmIdentity("x", 0, "", "")
        assertTrue(SearchDedupPolicy.compatible(known, unknown))
    }

    @Test
    fun `地区与类型冲突同样拦截(忽略大小写)`() {
        val a = SearchDedupPolicy.FilmIdentity("x", 0, "大陆", "剧情")
        val b = SearchDedupPolicy.FilmIdentity("x", 0, "韩国", "剧情")
        assertFalse(SearchDedupPolicy.compatible(a, b))
        val c = SearchDedupPolicy.FilmIdentity("x", 0, "大陆", "喜剧")
        assertFalse(SearchDedupPolicy.compatible(a, c))
        // ignoreCase 用同长度的大小写差异验证(中文无大小写,借 ASCII 段验证)
        val base = SearchDedupPolicy.FilmIdentity("x", 0, "HongKong", "剧情")
        val d = SearchDedupPolicy.FilmIdentity("x", 0, "hongkong", "剧情")
        assertTrue(SearchDedupPolicy.compatible(base, d))
    }

    // ==================== group ====================

    @Test
    fun `同名同身份跨源归并为一组`() {
        val g = group(
            listOf(
                item("s1|1", "神探狄仁杰", 2004, "大陆", "剧情"),
                item("s2|1", "神探狄仁杰", 2004, "大陆", "剧情"),
            ),
        )
        assertEquals(1, g.size)
        assertEquals(2, g[0].members.size)
    }

    @Test
    fun `同名不同年拆为两组(同名翻拍不误并)`() {
        val g = group(
            listOf(
                item("s1|1", "西游记", 1986, "", ""),
                item("s2|1", "西游记", 2010, "", ""),
            ),
        )
        assertEquals(2, g.size)
    }

    @Test
    fun `三方场景 未知年与已知年同组 两个已知年互相拆`() {
        val g = group(
            listOf(
                item("s1|1", "西游记", 0),
                item("s2|1", "西游记", 2019),
                item("s3|1", "西游记", 2020),
            ),
        )
        // s1 与 s2 相容同组;s3 与 s2 冲突 → 新子组
        assertEquals(2, g.size)
        assertEquals(listOf("s1|1", "s2|1"), g[0].members.map { it.id })
        assertEquals(listOf("s3|1"), g[1].members.map { it.id })
        // 同桶冲突产生的子组:subIndex 0 与 1
        assertEquals(0, g[0].subIndex)
        assertEquals(1, g[1].subIndex)
    }

    @Test
    fun `标题带标点与空格差异归并键一致`() {
        val g = group(
            listOf(
                item("s1|1", "神探狄仁杰：凶案实录"),
                item("s2|1", "神探狄仁杰凶案实录 "),
            ),
        )
        assertEquals(1, g.size)
    }

    @Test
    fun `不同标题永不归并(神探狄仁杰1 与 2 是两部片)`() {
        val g = group(
            listOf(
                item("s1|1", "神探狄仁杰1"),
                item("s2|1", "神探狄仁杰2"),
            ),
        )
        assertEquals(2, g.size)
    }

    @Test
    fun `空标题各自独立成组且不与其他组混淆`() {
        val g = group(
            listOf(
                item("s1|1", ""),
                item("s2|1", ""),
                item("s3|1", "功夫女足"),
            ),
        )
        assertEquals(3, g.size)
        assertTrue(g.all { it.members.size == 1 })
    }

    @Test
    fun `组序等于各组首成员的出现顺序`() {
        val g = group(
            listOf(
                item("s1|1", "B片"),
                item("s2|1", "A片"),
                item("s3|1", "B片"),
            ),
        )
        // titleKey 是助手归一化后的小写形式
        assertEquals(listOf("b片", "a片"), g.map { it.titleKey })
        assertEquals(listOf("s1|1", "s3|1"), g[0].members.map { it.id })
    }

    @Test
    fun `键相同但身份冲突的两个组键互不相同(subIndex 区分)`() {
        val g = group(
            listOf(
                item("s1|1", "西游记", 1986),
                item("s2|1", "西游记", 2010),
                item("s3|1", "西游记", 1986),
            ),
        )
        // s3 与 s1 相容应进 s1 的组,而不是开第三组
        assertEquals(2, g.size)
        assertEquals(listOf("s1|1", "s3|1"), g[0].members.map { it.id })
        assertEquals(g[0].titleKey, g[1].titleKey)
        assertTrue(g[0].subIndex != g[1].subIndex)
    }

    // ==================== 版本角标(有码/无码):2026-10-11 真机事故 ====================

    @Test
    fun `同番号有码与无码必须拆成两组_真机事故回归`() {
        // 真机现象:两个源标题完全一样(番号),一个标"无码"一个标"有码",
        // 结果被并成一张卡 + 角标"2 个源",而无码那个版本在列表上点不到。
        val g = group(
            listOf(
                item("s1|1", "MIAA-195", note = "无码"),
                item("s2|1", "MIAA-195", note = "有码"),
            ),
        )
        assertEquals("有码/无码是不同内容,必须拆组", 2, g.size)
        assertEquals(1, g[0].members.size)
        assertEquals(1, g[1].members.size)
    }

    @Test
    fun `无码写法差异(繁简与附加修饰)仍归为一组`() {
        // 同一版本在不同源里的写法:"无码" / "無碼" / "无码高清" / "无码 中文字幕"
        val g = group(
            listOf(
                item("s1|1", "MIAA-195", note = "无码"),
                item("s2|1", "MIAA-195", note = "無碼"),
                item("s3|1", "MIAA-195", note = "无码高清"),
            ),
        )
        assertEquals("归一化后都是'无码',应同组", 1, g.size)
        assertEquals(3, g[0].members.size)
    }

    @Test
    fun `一侧无角标不判死_仍与有角标同组`() {
        // 多数源根本不标 note;若"缺角标"也算冲突,常规片名会被整批拆散。
        val g = group(
            listOf(
                item("s1|1", "MIAA-195", note = "无码"),
                item("s2|1", "MIAA-195", note = ""),
            ),
        )
        assertEquals(1, g.size)
        assertEquals(2, g[0].members.size)
    }

    @Test
    fun `集数与评分类note不参与身份判定_避免组键随更新进度跳动`() {
        // 剧集 note 常是"共36集"/"更新至12集";评分源会给"4.4"。
        // 这些是同一部片的不同进度,拿它当身份会让组键一集一变、列表跳动。
        assertEquals("", SearchDedupPolicy.normalizeNote("共36集"))
        assertEquals("", SearchDedupPolicy.normalizeNote("更新至12集"))
        assertEquals("", SearchDedupPolicy.normalizeNote("4.4"))
        val g = group(
            listOf(
                item("s1|1", "神探狄仁杰", note = "共36集"),
                item("s2|1", "神探狄仁杰", note = "更新至12集"),
            ),
        )
        assertEquals(1, g.size)
    }

    @Test
    fun `normalizeNote 抽取版本关键词_与顺序和修饰词无关`() {
        assertEquals("无码", SearchDedupPolicy.normalizeNote("无码"))
        assertEquals("无码", SearchDedupPolicy.normalizeNote("高清无码"))
        assertEquals("中字", SearchDedupPolicy.normalizeNote("HD中字"))
        assertEquals("中字", SearchDedupPolicy.normalizeNote("中文字幕"))
        assertEquals("无码+中字", SearchDedupPolicy.normalizeNote("无码中文字幕"))
        assertEquals("无码+中字", SearchDedupPolicy.normalizeNote("中文字幕 无码"))
        assertEquals("破解", SearchDedupPolicy.normalizeNote("流出破解"))
        // 空与 null 一律空串
        assertEquals("", SearchDedupPolicy.normalizeNote(null))
        assertEquals("", SearchDedupPolicy.normalizeNote(""))
    }

    // ---- 英文与日文口径(2026-10-11 复查补:此前只测了中文路径) ----

    @Test
    fun `英文标注同样识别_uncensored与censored`() {
        assertEquals("无码", SearchDedupPolicy.normalizeNote("uncensored"))
        assertEquals("有码", SearchDedupPolicy.normalizeNote("censored"))
        assertEquals("无码", SearchDedupPolicy.normalizeNote("UNCENSORED"))
        assertEquals("中字", SearchDedupPolicy.normalizeNote("Chinese Sub"))
        assertEquals("破解", SearchDedupPolicy.normalizeNote("leaked"))
    }

    /**
     * ⚠️ 顺序陷阱:`"uncensored"` 里**包含** `"censored"` 子串。
     *
     * <p>若实现里先判"有码",`uncensored` 会被判成**有码** —— 两个版本仍被分开,
     * 但**标签互换**,提示与实情相反。这是最坏的一种错法(比不分开更难发现,
     * 因为界面上"看起来是分开的")。所以必须用断言把顺序钉死。
     */
    @Test
    fun `uncensored 不得被判成有码_顺序陷阱`() {
        val s = SearchDedupPolicy.normalizeNote("uncensored")
        assertEquals("uncensored 必须判为无码,不能被子串 censored 抢先命中", "无码", s)
        assertTrue(s != SearchDedupPolicy.normalizeNote("censored"))
    }

    /**
     * 日文口径:番号片的主流写法是 `無修正`(无码)/`修正`(有码),而不是中文的"无码/有码"。
     *
     * <p>此前只认中文与英文 ⇒ 日文源返回空串,而空串在 [compatible] 里等于"该源没标",
     * **不与"无码"冲突** ⇒ 用户报的那个误并事故会在日文源上原样复现。
     */
    @Test
    fun `日文 無修正 与 修正 同样识别`() {
        assertEquals("无码", SearchDedupPolicy.normalizeNote("無修正"))
        assertEquals("无码", SearchDedupPolicy.normalizeNote("无修正"))
        assertEquals("有码", SearchDedupPolicy.normalizeNote("修正"))
        // 無修正 含子串 修正,必须靠顺序判对
        assertTrue(
            SearchDedupPolicy.normalizeNote("無修正") != SearchDedupPolicy.normalizeNote("修正")
        )
    }

    @Test
    fun `中文无码 与 日文無修正 视为同一版本_跨语言不误拆`() {
        // 同一部片:中文源标"无码"、日文源标"無修正" ⇒ 必须归为同一组,
        // 否则用户会看到同一版本被拆成两张卡(过度拆分的另一种错误)
        val g = group(
            listOf(
                item("s1|1", "MIAA-195", note = "无码"),
                item("s2|1", "MIAA-195", note = "無修正"),
            ),
        )
        assertEquals(1, g.size)
        assertEquals(2, g[0].members.size)
    }

    @Test
    fun `中文无码 与 日文修正 必须拆组_跨语言误并回归`() {
        // 这是本轮真正要防的场景:一个源标"无码"、另一个标日文"修正"(即有码)。
        // 修复前:日文侧返回空串 ⇒ 不冲突 ⇒ 误并成一张卡。
        val g = group(
            listOf(
                item("s1|1", "MIAA-195", note = "无码"),
                item("s2|1", "MIAA-195", note = "修正"),
            ),
        )
        assertEquals("无码与有码(日文写作'修正')是不同内容,必须拆组", 2, g.size)
    }

    @Test
    fun `中英日三语混标不误拆`() {
        val g = group(
            listOf(
                item("s1|1", "MIAA-195", note = "无码中文字幕"),
                item("s2|1", "MIAA-195", note = "uncensored chinese"),
                item("s3|1", "MIAA-195", note = "無修正 字幕"),
            ),
        )
        assertEquals("三种写法都是'无码+中字',应同组", 1, g.size)
        assertEquals(3, g[0].members.size)
    }

    /**
     * ⚠️ 第二个**判反陷阱**:`"无字幕"` 里包含 `"字幕"`。
     *
     * <p>"无字幕"(明确表示没有字幕)若被判成"中字",结论正好相反 ——
     * 与 `uncensored`/`censored` 是同一类错误。
     */
    @Test
    fun `无字幕 不得被判成中字_判反陷阱`() {
        assertEquals("无字", SearchDedupPolicy.normalizeNote("无字幕"))
        assertEquals("无字", SearchDedupPolicy.normalizeNote("無字幕"))
        assertEquals("中字", SearchDedupPolicy.normalizeNote("字幕"))
        // 两者必须是不同版本,不能被合并
        assertTrue(
            SearchDedupPolicy.normalizeNote("无字幕") != SearchDedupPolicy.normalizeNote("中文字幕")
        )
    }

    @Test
    fun `无字幕与中文字幕必须拆组`() {
        val g = group(
            listOf(
                item("s1|1", "MIAA-195", note = "无字幕"),
                item("s2|1", "MIAA-195", note = "中文字幕"),
            ),
        )
        assertEquals("无字幕与有中字是不同版本", 2, g.size)
    }
}
