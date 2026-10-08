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

    /**
     * 别名参与匹配(2026-10-08)。
     *
     * <p>这是准确度修复的核心:JSON 型源把副标题/英文名放在 vod_sub / vod_en,
     * 客户端此前只按 name 过滤,"源站搜得到、客户端判不匹配"的结果被整批丢掉。
     */
    @Test
    fun smartMatch_acceptsAliasHits() {
        val smart = SearchSettings.MatchMode.Smart
        // 标题不含关键词,但别名里有 —— 必须保留
        assertTrue(SearchSettings.matches("流浪地球", "三体的异世界|Fated", "三体", smart))
        // 别名以多种分隔符连接(口径同 AbsJson.joinAliases):逗号、顿号、分号都要能切
        assertTrue(SearchSettings.matches("流浪地球", "三体剧版,官方中文", "三体", smart))
        assertTrue(SearchSettings.matches("流浪地球", "译名A、三体、译名B", "三体", smart))
        assertTrue(SearchSettings.matches("流浪地球", "译名A；译名B、三体", "三体", smart))
        // 别名与标题都不含才滤掉
        assertFalse(SearchSettings.matches("琅琊榜", "琅琊榜|琅琊榜风起", "庆余年", smart))
        // 关键词为空仍不放行(空搜索不能把所有条目都收进来)
        assertFalse(SearchSettings.matches("三体", "三体", "", smart))
    }

    @Test
    fun exactMatch_acceptsAliasExactHit() {
        val exact = SearchSettings.MatchMode.Exact
        // 标题本身与关键词相等(靠标题命中),别名同时也对得上
        assertTrue(SearchSettings.matches("三体", "三体|三体的异世界", "三体", exact))
        // 标题与关键词不等,只有别名精确等于关键词 —— 这才是别名在精准模式下的作用
        assertTrue(SearchSettings.matches("流浪地球", "三体|三体的异世界", "三体", exact))
        // 别名只是"包含"不算精准命中
        assertFalse(SearchSettings.matches("流浪地球", "三体的异世界", "三体", exact))
        // 标题与别名都不对得上
        assertFalse(SearchSettings.matches("庆余年", "庆余年第二季", "三体", exact))
    }

    /**
     * 相关度分级:标题命中永远高于仅别名命中。
     *
     * <p>如果别名与"标题包含"同级,一部片的英文名会把真正的标题匹配压下去 ——
     * 用户看到的最前一条就不一定是自己要搜的那部。
     *
     * <p>⚠️ 构造"仅别名命中"必须让**标题归一化后与关键词毫无子串关系**。
     * 用「流浪地球」搜「三体」才测得到 aliasScore;若标题写成「三体动画版」,
     * 会先命中 startsWith(4 分)返回,断言测到的根本不是别名那一档 ——
     * 这种"测试通过但测的不是目标逻辑"最费时间,故在此显式记录踩法。
     */
    @Test
    fun relevance_ranksAliasHitBelowEveryTitleHit() {
        val unrelated = "流浪地球" // 与「三体」无任何子串重叠
        val aliasOnly = SearchSettings.relevanceScore(unrelated, "三体", "三体")
        val contains = SearchSettings.relevanceScore("我的三体", null, "三体")
        val prefix = SearchSettings.relevanceScore("三体 第二季", null, "三体")
        val exact = SearchSettings.relevanceScore("三体", null, "三体")
        assertTrue(exact > prefix)
        assertTrue(prefix > contains)
        assertTrue(contains > aliasOnly)
        // 别名完全相等给低档正分,而不是 0(0 会被"不匹配"等同看待)
        assertTrue(aliasOnly > 0)
        // 标题本身已贴合时,别名不再加分(否则同一部片因源而异分)
        assertEquals(5, SearchSettings.relevanceScore("三体", "三体", "三体"))
        // 别名只是"包含"关系不算命中 —— 不给正分
        assertEquals(0, SearchSettings.relevanceScore(unrelated, "三体的异世界", "三体"))
    }

    @Test
    fun relevance_zeroWhenNeitherTitleNorAliasMatches() {
        assertEquals(0, SearchSettings.relevanceScore("琅琊榜", "琅琊榜", "庆余年"))
        assertEquals(0, SearchSettings.relevanceScore(null, null, "庆余年"))
        // 关键词为空一律 0
        assertEquals(0, SearchSettings.relevanceScore("三体", "三体", ""))
    }

    /** 归一化缓存必须与直接归一化等价 —— 否则缓存引入的是静默的匹配偏差 */
    @Test
    fun normalizeCached_isEquivalentToNormalize() {
        val samples = listOf(
            "庆余年(2019)", " 庆 余年 ", "Stranger-Things", "Ｑing ２０２４", "三体【全3集】", "",
        )
        for (s in samples) {
            assertEquals(SearchSettings.normalize(s), SearchSettings.normalizeCached(s))
        }
        // null 不进缓存,结果仍为空串
        assertEquals("", SearchSettings.normalizeCached(null))
        // 重复调用要命中缓存并给出一致结果
        assertEquals(SearchSettings.normalizeCached("庆余年(2019)"), SearchSettings.normalizeCached("庆余年(2019)"))
    }
}
