package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.MovieSort
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分类白名单分流的口径回归。
 *
 * 存在理由:一个都没匹配上时若产出空列表,首页会整页"暂无内容"(源改分类名即触发) ⇒ 口径 = 退化为全量。
 */
class DefaultConfigSortTest {

    private fun sort(name: String) = MovieSort.SortData(name, name)

    @Test
    fun pickByCategories_keepsWhitelistOrder() {
        val list = listOf(sort("A"), sort("B"), sort("C"))
        val picked = DefaultConfig.pickByCategories(list, listOf("C", "A"))
        assertEquals(listOf("C", "A"), picked.map { it.name })
    }

    @Test
    fun pickByCategories_unmatchedFallsBackToAll() {
        val list = listOf(sort("A"), sort("B"))
        val picked = DefaultConfig.pickByCategories(list, listOf("X", "Y"))
        assertEquals(listOf("A", "B"), picked.map { it.name })
    }

    @Test
    fun pickByCategories_emptyListStaysEmpty() {
        val picked = DefaultConfig.pickByCategories(emptyList(), listOf("X"))
        assertEquals(0, picked.size)
    }
}
