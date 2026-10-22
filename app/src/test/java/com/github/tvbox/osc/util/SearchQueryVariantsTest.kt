package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查询变体([SearchSettings.queryVariants])的行为锁。
 *
 * <p>存在理由:站方写片名没有规范 —— 带年份、带"HD/第2季"、用繁体、把番号里的连接符去掉,
 * 都会让"原词问一次"的源回答"没有这部剧",表现为**明明有源却搜不到**。变体是补这个漏的,
 * 但每个变体都按源数放大请求量,所以**顺序与上限**同样要钉住:值钱的先要,总数封顶。
 *
 * <p>简繁转换靠 `android.icu`,JVM 单测环境里没有该类 ⇒ 断言只覆盖不依赖 ICU 的部分,
 * 并额外锁一条"ICU 不可用时必须静默降级、不许抛"。
 */
class SearchQueryVariantsTest {

    @Test
    fun `原词总是第一个变体`() {
        val variants = SearchSettings.queryVariants("功夫女足")
        assertEquals("功夫女足", variants.first())
    }

    @Test
    fun `番号补出去连接符的紧凑形`() {
        val variants = SearchSettings.queryVariants("MIAA-195")
        assertTrue("变体=$variants", variants.contains("MIAA195"))
    }

    @Test
    fun `剥掉年份与版本后缀得到核心片名`() {
        val variants = SearchSettings.queryVariants("猎鹰 2024 HD 第2季")
        assertTrue("变体=$variants", variants.contains("猎鹰"))
    }

    @Test
    fun `全角括号年份也要剥`() {
        val variants = SearchSettings.queryVariants("功夫女足（2026）")
        assertTrue("变体=$variants", variants.contains("功夫女足"))
    }

    @Test
    fun `数量封顶且不含重复与空串`() {
        val variants = SearchSettings.queryVariants("知否, 知否应是绿肥红瘦 2018 完结")
        assertTrue("变体数=${variants.size}", variants.size <= 4)
        assertEquals("变体不许重复: $variants", variants.size, variants.distinct().size)
        assertFalse("变体不许为空串", variants.any { it.isBlank() })
    }

    @Test
    fun `空输入不给变体`() {
        assertEquals(0, SearchSettings.queryVariants(null).size)
        assertEquals(0, SearchSettings.queryVariants("   ").size)
    }

    @Test
    fun `icu不可用时静默降级不抛`() {
        // JVM 环境没有 android.icu —— 这里就是跑那条降级路径:能出其余变体、不抛异常
        val variants = SearchSettings.queryVariants("夢華錄")
        assertTrue("至少要返回原词", variants.isNotEmpty())
    }

    // ---------- 番号写法归一(2026-10-22:ymdd370 搜得比 ymdd-370 少) ----------

    @Test
    fun `番号规范形补横杠并转大写`() {
        assertEquals("YMDD-370", SearchSettings.canonicalCodeForm("ymdd370"))
        assertEquals("YMDD-370", SearchSettings.canonicalCodeForm("YMDD370"))
        assertEquals("YMDD-370", SearchSettings.canonicalCodeForm("ymdd-370"))
        assertEquals("YMDD-370", SearchSettings.canonicalCodeForm("YMDD-370"))
        assertEquals("ADN-811", SearchSettings.canonicalCodeForm("adn811"))
        assertEquals("ADN-811", SearchSettings.canonicalCodeForm("adn_811"))
    }

    @Test
    fun `取不到规范形的输入返回 null`() {
        // 纯数字 / 纯字母 / 中文 / 字母块里夹数字 —— 都不重排,保持调用方原顺序
        assertNull(SearchSettings.canonicalCodeForm("2024"))
        assertNull(SearchSettings.canonicalCodeForm("abc"))
        assertNull(SearchSettings.canonicalCodeForm("三体"))
        assertNull(SearchSettings.canonicalCodeForm("fc2ppv4925855"))
        assertNull(SearchSettings.canonicalCodeForm(""))
        assertNull(SearchSettings.canonicalCodeForm(null))
    }

    @Test
    fun `四种番号写法的首个查询词完全相同`() {
        // 这就是用户要的"四种输入搜到的数量一样多"的**机制保证**:
        // 交到源站手里的第一个问题一模一样,源站凭什么给出不同的条数?
        val firsts = listOf("ymdd370", "ymdd-370", "YMDD370", "YMDD-370")
            .map { SearchSettings.orderedQueryVariants(it).first() }
            .distinct()
        assertEquals("四种写法的首词必须一致,实际=$firsts", 1, firsts.size)
        assertEquals("YMDD-370", firsts.first())
    }

    @Test
    fun `粘连形会补出带横杠的候选`() {
        // 回归:queryVariants 只"去分隔符",不"补分隔符",所以 ymdd370 的变体表里
        // 原本**没有**带横杠的写法 —— 想补的候选压根不存在,也就谈不上排到第一位。
        assertTrue(SearchSettings.orderedQueryVariants("ymdd370").contains("YMDD-370"))
        assertTrue(SearchSettings.orderedQueryVariants("adn811").contains("ADN-811"))
    }

    @Test
    fun `原词仍然保留在变体表里`() {
        // 规范形只抢"第一位",不许把用户的输入挤掉 —— 有些源站恰好只认用户那种写法
        assertTrue(SearchSettings.orderedQueryVariants("ymdd370").contains("ymdd370"))
        assertTrue(SearchSettings.orderedQueryVariants("adn811").contains("adn811"))
    }

    @Test
    fun `非番号查询顺序一位不动`() {
        // 普通片名不该被这条改动影响:首词必须还是用户敲的那个
        assertEquals("功夫女足", SearchSettings.orderedQueryVariants("功夫女足").first())
        assertEquals("猎鹰 2024 HD", SearchSettings.orderedQueryVariants("猎鹰 2024 HD").first())
        assertEquals(
            SearchSettings.queryVariants("功夫女足"),
            SearchSettings.orderedQueryVariants("功夫女足"),
        )
    }

    @Test
    fun `番号重排后仍不超上限且无重复`() {
        listOf("ymdd370", "ymdd-370", "YMDD370", "ADN811", "MIAA195", "FC2-PPV-4925855").forEach { k ->
            val v = SearchSettings.orderedQueryVariants(k)
            assertTrue("$k 变体数=${v.size}", v.size <= 4)
            assertEquals("$k 有重复:$v", v.size, v.distinct().size)
            assertFalse("$k 有空串", v.any { it.isBlank() })
        }
    }

    @Test
    fun `夹数字的字母块番号不被重排`() {
        // FC2-PPV-4925855:去分隔符后是 fc2ppv4925855,不是"一段字母+一段数字",
        // 硬补横杠会得到 FC2PPV-4925855,比用户输入的 FC2-PPV-4925855 离站方写法更远
        assertEquals(
            listOf("FC2-PPV-4925855", "FC2PPV4925855"),
            SearchSettings.orderedQueryVariants("FC2-PPV-4925855"),
        )
    }
}
