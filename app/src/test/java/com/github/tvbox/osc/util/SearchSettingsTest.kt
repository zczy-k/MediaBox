package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSettingsTest {

    @Test
    fun exactMatch_ignoresBracketNotes() {
        assertTrue(SearchSettings.isExactMatch("庆余年(2019)", "庆余年"))
        assertTrue(SearchSettings.isExactMatch("庆余年【全46集】", "庆余年"))
        assertTrue(SearchSettings.isExactMatch("庆余年 [1080P]", "庆余年"))
        assertTrue(SearchSettings.isExactMatch("庆余年（第一季）", "庆余年"))
    }

    @Test
    fun exactMatch_ignoresSpacePunctuationAndCase() {
        assertTrue(SearchSettings.isExactMatch(" 庆 余年 ", "庆余年"))
        assertTrue(SearchSettings.isExactMatch("Stranger-Things", "stranger things"))
    }

    @Test
    fun exactMatch_distinguishesTitleBody() {
        // 括号外的差异(季数/续集/子标题)必须区分 —— 否则"精准"等于没开
        assertFalse(SearchSettings.isExactMatch("庆余年 第二季", "庆余年"))
        assertFalse(SearchSettings.isExactMatch("庆余年2", "庆余年"))
        assertFalse(SearchSettings.isExactMatch("庆余年之少年纵横", "庆余年"))
    }

    @Test
    fun exactMatch_emptyAndNullAreSafe() {
        assertFalse(SearchSettings.isExactMatch(null, "庆余年"))
        assertFalse(SearchSettings.isExactMatch("庆余年", null))
        assertFalse(SearchSettings.isExactMatch("", ""))
    }

    @Test
    fun smartMatch_keepsContains_dropsUnrelated() {
        val m = SearchSettings.MatchMode.Smart
        // 含关键词(即使带季数/后缀)保留
        assertTrue(SearchSettings.matches("庆余年 第二季", "庆余年", m))
        assertTrue(SearchSettings.matches("庆余年之少年纵横", "庆余年", m))
        assertTrue(SearchSettings.matches("庆余年(2019)", "庆余年", m))
        // 不含关键词的跑题条目滤掉
        assertFalse(SearchSettings.matches("琅琊榜", "庆余年", m))
        // 关键词为空不保留任何结果(避免空搜索把所有条目放行)
        assertFalse(SearchSettings.matches("庆余年", "", m))
    }

    @Test
    fun allMode_keepsEverything() {
        assertTrue(SearchSettings.matches("琅琊榜", "庆余年", SearchSettings.MatchMode.All))
        assertTrue(SearchSettings.matches(null, "庆余年", SearchSettings.MatchMode.All))
    }

    @Test
    fun exactMode_matchesCompleteNumberPrefixInsideLongTitle() {
        val exact = SearchSettings.MatchMode.Exact
        // 番号精准 = 整个番号 token 精确匹配,允许标题继续跟片名/副标题
        assertTrue(SearchSettings.matches("MIAA-195 后续标题", "MIAA-195", exact))
        assertTrue(SearchSettings.matches("MIAA-195 后续标题", "MIAA195", exact))
        assertTrue(SearchSettings.matches("FC2-PPV-4925855 [其他描述]", "FC2PPV4925855", exact))
        // 不得把较短前缀当成号码命中
        assertFalse(SearchSettings.matches("MIAA-195 后续标题", "MIAA-19", exact))
        // 普通文字仍保留原来的全标题相等语义
        assertFalse(SearchSettings.matches("庆余年 第二季", "庆余年", exact))
    }

    @Test
    fun relevanceScore_ranksExactAboveContains() {
        // 相等 > 前缀 > 包含
        assertTrue(
            SearchSettings.relevanceScore("庆余年", "庆余年") >
                SearchSettings.relevanceScore("庆余年 第二季", "庆余年"),
        )
        assertTrue(
            SearchSettings.relevanceScore("庆余年 第二季", "庆余年") >
                SearchSettings.relevanceScore("我的庆余年", "庆余年"),
        )
        // 不匹配为 0
        assertEquals(0, SearchSettings.relevanceScore("琅琊榜", "庆余年"))
        // 多词:每个词都出现给低档正分
        assertTrue(SearchSettings.relevanceScore("三体 动画版 2023", "三体 动画") > 0)
    }

    @Test
    fun normalize_convertsFullWidthToHalfWidth() {
        // 全角数字/字母/空格应与半角等价
        assertTrue(SearchSettings.isExactMatch("Ｑing ２０２４", "qing 2024"))
    }

    @Test
    fun queryVariants_addsCompactFormForNumberedTitle() {
        // 番号带分隔符:补一个去分隔符的紧凑形式(源库常以无横杠存番号)
        assertEquals(listOf("MIAA-195", "MIAA195"), SearchSettings.queryVariants("MIAA-195"))
        assertEquals(listOf("FC2-PPV-4925855", "FC2PPV4925855"), SearchSettings.queryVariants("FC2-PPV-4925855"))
        // 两端空白先裁掉
        assertEquals(listOf("MIAA-195", "MIAA195"), SearchSettings.queryVariants("  MIAA-195  "))
    }

    @Test
    fun queryVariants_noSeparatorYieldsOnlyRaw() {
        // 无分隔符关键词不产生额外请求
        assertEquals(listOf("庆余年"), SearchSettings.queryVariants("庆余年"))
        assertEquals(listOf("MIAA195"), SearchSettings.queryVariants("MIAA195"))
    }

    @Test
    fun queryVariants_blankYieldsEmpty() {
        assertEquals(emptyList<String>(), SearchSettings.queryVariants(null))
        assertEquals(emptyList<String>(), SearchSettings.queryVariants("   "))
    }
}
