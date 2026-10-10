package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相关度排序的正片优先规则(2026-10-10):
 * 正片(任何命中档) > 非正片(预告/花絮/幕后/特辑/片花,压到 ≤2) > 纯别名命中(1)。
 */
class SearchRelevanceTest {

    @Test
    fun `正片贴词高分不受影响`() {
        assertEquals(5, SearchSettings.relevanceScore("花开锦绣", null, "花开锦绣"))
        assertEquals(4, SearchSettings.relevanceScore("花开锦绣2026", null, "花开锦绣"))
        assertEquals(4, SearchSettings.relevanceScore("花开锦绣 电视版", null, "花开锦绣"))
    }

    @Test
    fun `预告类标题即使贴词也压到正片之下`() {
        val main = SearchSettings.relevanceScore("花开锦绣", null, "花开锦绣")
        val trailer = SearchSettings.relevanceScore("花开锦绣 预告", null, "花开锦绣")
        val trailerExact = SearchSettings.relevanceScore("花开锦绣预告", null, "花开锦绣")
        assertTrue(trailer in 1..2)
        assertTrue(trailerExact in 1..2)
        assertTrue(main > trailer)
        // 正片"包含"命中(3) 也压过预告的"几乎全等"(2)
        val partial = SearchSettings.relevanceScore("花开锦绣之花好月圆", null, "花开锦绣")
        assertTrue(partial > trailerExact)
    }

    @Test
    fun `note命中预告同样降权`() {
        assertEquals(2, SearchSettings.relevanceScore("花开锦绣", null, "花开锦绣", "官方预告片"))
        assertEquals(5, SearchSettings.relevanceScore("花开锦绣", "共36集", "花开锦绣"))
        assertEquals(5, SearchSettings.relevanceScore("花开锦绣", "国语中字", "花开锦绣"))
    }

    @Test
    fun `花絮_幕后_特辑_片花同样降权`() {
        for (suffix in listOf("花絮", "幕后", "特辑", "片花")) {
            val s = SearchSettings.relevanceScore("花开锦绣$suffix", null, "花开锦绣")
            assertTrue(suffix, s in 1..2)
        }
    }

    @Test
    fun `关键词本身含预告词时不降权_搜索意图豁免`() {
        assertEquals(5, SearchSettings.relevanceScore("花开锦绣 预告", null, "花开锦绣 预告"))
        assertEquals(2, SearchSettings.relevanceScore("花开锦绣 先导预告", null, "花开锦绣 预告"))
        assertFalse(SearchSettings.isNonMainContent("某片预告合集", null, "预告"))
    }

    @Test
    fun `路透与开机等资讯类识别词命中降权_真机截图案例`() {
        val k = "花开锦绣"
        // 真机截图(2026-10-10 08:42)的 4 条资讯条目
        assertEquals(2, SearchSettings.relevanceScore("开机大吉！丁禹兮邓恩熙《花开锦绣》养眼同框", null, k))
        assertEquals(2, SearchSettings.relevanceScore("邓恩熙《花开锦绣》夜戏路透 清雅造型温婉灵动", null, k))
        assertEquals(2, SearchSettings.relevanceScore("丁禹兮《花开锦绣》骑马路透 新造型亮眼吸睛", null, k))
        assertEquals(2, SearchSettings.relevanceScore("丁禹兮《花开锦绣》杀青专访 风度翩翩", null, k))
        // 正片仍是 4-5 分,排最前
        assertTrue(
            SearchSettings.relevanceScore("花开锦绣2026", null, k) >
                SearchSettings.relevanceScore("邓恩熙《花开锦绣》夜戏路透 清雅造型温婉灵动", null, k),
        )
    }

    @Test
    fun `标题长度启发式_无识别词的超长资讯标题也降权`() {
        val k = "花开锦绣"
        // 无任何识别词,但"关键词+长描述"的典型资讯标题(归一化后富余 17 字)
        assertEquals(2, SearchSettings.relevanceScore("丁禹兮邓恩熙《花开锦绣》养眼同框高清剧照大图", null, k))
        // 长度富余在阈值内的正片不受影响(宁漏杀不误杀)
        assertEquals(4, SearchSettings.relevanceScore("花开锦绣之花好月圆", null, k))
        assertEquals(4, SearchSettings.relevanceScore("花开锦绣2026", null, k))
        assertEquals(
            4,
            SearchSettings.relevanceScore("上错花轿嫁对郎之花好月圆", null, "上错花轿嫁对郎"),
        )
    }

    @Test
    fun `番号与拉丁字母关键词豁免长度启发式_长副题正片不误伤`() {
        // 番号正片 = 编号 + 长日文标题,长度差天然大 —— 不能按"超长=资讯"误判
        assertEquals(4, SearchSettings.relevanceScore("MIAA-195 深田えいみ 10000回の絶頂", null, "MIAA-195"))
        assertEquals(4, SearchSettings.relevanceScore("FC2 PPV 完整版原片", null, "FC2"))
    }

    @Test
    fun `日文平片假名折叠_全角半角与半角浊音归一`() {
        // 片假名查询 vs 平假名标题(同侧折叠后相等)
        assertEquals(5, SearchSettings.relevanceScore("ゲーティア", null, "げーてぃあ"))
        // 半角片假名+浊点 NFKC → 全角 → 折叠
        assertEquals(5, SearchSettings.relevanceScore("ｶﾞﾝﾀﾞﾑ", null, "ガンダム"))
        // 全角连字符 NFKC → 半角 → 噪声剥离
        assertEquals(5, SearchSettings.relevanceScore("ＡＢＣ－１２３", null, "ABC-123"))
    }

    @Test
    fun `长音符辅助匹配降档_不越主形式`() {
        // 主形式未命中(标题多一个ー),剥长音辅助命中 → 2 分(低于主形式命中,高于别名 1)
        assertEquals(2, SearchSettings.relevanceScore("ゲーセン", null, "ゲセン"))
        assertEquals(2, SearchSettings.relevanceScore("レイアウト", null, "レイアウトー"))
        assertTrue(SearchSettings.matches("ゲーセン", null, "ゲセン", SearchSettings.MatchMode.Smart))
        // 主形式命中的不受影响
        assertEquals(5, SearchSettings.relevanceScore("ゲーセン", null, "げーせん"))
    }

    @Test
    fun `非正片只降权不过滤_仍可被搜索命中`() {
        assertTrue(SearchSettings.matches("花开锦绣 预告", null, "花开锦绣", SearchSettings.MatchMode.Smart))
        assertEquals(2, SearchSettings.relevanceScore("花开锦绣 预告", null, "花开锦绣"))
    }

    @Test
    fun `降权后仍高于纯别名命中`() {
        // 别名完全相等 = 1;预告类标题命中 = 2
        assertEquals(
            1,
            SearchSettings.relevanceScore("完全无关的标题", "花开锦绣", "花开锦绣"),
        )
        assertEquals(
            2,
            SearchSettings.relevanceScore("花开锦绣 片花", "花开锦绣海外版", "花开锦绣"),
        )
    }
}
