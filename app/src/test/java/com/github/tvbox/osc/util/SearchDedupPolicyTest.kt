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
    ) = Item(id, SearchDedupPolicy.FilmIdentity(normalizeTitle(title), year, area, type))

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
}
