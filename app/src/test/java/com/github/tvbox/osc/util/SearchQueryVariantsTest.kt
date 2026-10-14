package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
