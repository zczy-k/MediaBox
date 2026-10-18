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

    // ==================== 番号宽松匹配(2026-10-12 真机反馈) ====================

    /**
     * ⚠️ 真机回归:用户搜 `ADN-811-RM` 时**时好时坏**。
     *
     * <p>根因:原匹配要求整串 `adn811rm` 完整出现在标题里,而源站标题常常只写
     * `ADN-811`(不带 `-RM`)⇒ 只有标题恰好带 -RM 的源命中。
     * 真机日志证据:`echo-searchplan ... hit=0 empty=23`(源答话了,但全被判不匹配)。
     *
     * <p>修法:抽出番号**主体**(字母块+数字块)比对,版本后缀不参与。
     */
    @Test
    fun codeMatch_ignoresVersionSuffix() {
        val m = SearchSettings.MatchMode.Smart
        // 查询带版本后缀,标题不带 —— 必须命中
        assertTrue(SearchSettings.matches("ADN-811 I Want You To Love Me", null, "ADN-811-RM", m))
        assertTrue(SearchSettings.matches("ADN-811 I Want You To Love Me", null, "ADN811", m))
        assertTrue(SearchSettings.matches("ADN-811 I Want You To Love Me", null, "adn-811", m))
        assertTrue(SearchSettings.matches("ADN-811 I Want You To Love Me", null, "ADN811RM", m))
        // 番号出现在标题中段(带前置描述)也要命中
        assertTrue(
            SearchSettings.matches("[Reducing Mosaic] ADN-811 xxx", null, "ADN-811-RM", m),
        )
    }

    @Test
    fun codeMatch_uppercaseLowercaseEquivalent() {
        val m = SearchSettings.MatchMode.Smart
        assertTrue(SearchSettings.matches("adn-811 title", null, "ADN-811", m))
        assertTrue(SearchSettings.matches("ADN-811 title", null, "adn811", m))
        assertTrue(SearchSettings.matches("Adn-811 title", null, "ADN-811", m))
    }

    @Test
    fun codeMatch_neverMatchesShorterOrLongerCode() {
        val m = SearchSettings.MatchMode.Smart
        // 短号不得命中长号
        assertFalse(SearchSettings.matches("ADN-811 title", null, "ADN-81", m))
        // 长号不得命中短号(反向)
        assertFalse(SearchSettings.matches("ADN-81 title", null, "ADN-811", m))
        // 数字后仍跟数字 ⇒ 是更长的番号
        assertFalse(SearchSettings.matches("ADN-8112 title", null, "ADN-811", m))
    }

    @Test
    fun codeMatch_doesNotAffectPlainChineseQuery() {
        val m = SearchSettings.MatchMode.Smart
        // 中文查询走原路径:不含关键词仍被滤掉
        assertFalse(SearchSettings.matches("琅琊榜", null, "庆余年", m))
        assertTrue(SearchSettings.matches("庆余年 第二季", null, "庆余年", m))
    }

    @Test
    fun extractCode_pullsMainBodyOnly() {
        assertEquals("adn811", SearchSettings.extractCode("ADN-811"))
        assertEquals("adn811", SearchSettings.extractCode("ADN811"))
        assertEquals("adn811", SearchSettings.extractCode("ADN-811-RM"))
        assertEquals("adn811", SearchSettings.extractCode("adn-811"))
        assertEquals("miaa195", SearchSettings.extractCode("MIAA-195"))
        // 纯中文 / 纯数字 / 纯字母(无数字)都不是番号
        assertEquals("", SearchSettings.extractCode("庆余年"))
        assertEquals("", SearchSettings.extractCode("12345"))
        assertEquals("", SearchSettings.extractCode("thematrix"))
        assertEquals("", SearchSettings.extractCode(""))
        assertEquals("", SearchSettings.extractCode(null))
    }

    @Test
    fun codeMatch_worksInExactModeToo() {
        // 番号查询在精准模式下同样宽松:用户要的是"这个番号",不是"这个带后缀的整串"
        val exact = SearchSettings.MatchMode.Exact
        assertTrue(SearchSettings.matches("ADN-811 标题", null, "ADN-811-RM", exact))
    }

    @Test
    fun codeMatch_scoreNotPenalizedByVersionSuffix() {
        // 番号命中必须能排到前面,不能因为 note 里有"无码"之类被压到 2 分
        val s = SearchSettings.relevanceScore("ADN-811 标题", null, "ADN-811-RM", "无码")
        assertTrue("番号命中不该被非正片降权压到 2 分以下,实际 $s", s >= 3)
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

    // ==================== 归一化双变体(2026-10-18) ====================

    /**
     * 双变体要同时给出"去括注"与"保留括注"两种形式。
     *
     * <p>这是修「搜『三体 2023』命中不了标题『三体（2023）』」的关键：
     * 前者归一化成 `三体2023`，而单形式的后者被删成 `三体`，contains 必然不成立。
     */
    @Test
    fun `双变体同时给出删括注与保括注两种形式`() {
        assertEquals(
            listOf("三体", "三体2023"),
            SearchSettings.normalizeVariants("三体（2023）"),
        )
        // 第一个变体必须与旧的 normalizeCached 一致(保证纯增量、不破坏既有判定)
        assertEquals(SearchSettings.normalizeCached("三体（2023）"), SearchSettings.normalizeVariants("三体（2023）").first())
    }

    @Test
    fun `没有括号时只返回一个变体`() {
        assertEquals(listOf("三体"), SearchSettings.normalizeVariants("三体"))
        assertEquals(listOf("qianjunyifa"), SearchSettings.normalizeVariants("qianjunyifa"))
    }

    @Test
    fun `空输入返回空列表`() {
        assertEquals(emptyList<String>(), SearchSettings.normalizeVariants(null))
        assertEquals(emptyList<String>(), SearchSettings.normalizeVariants(""))
        // 纯标点归一化后为空 ⇒ 也给空
        assertEquals(emptyList<String>(), SearchSettings.normalizeVariants("（）"))
    }

    /** 核心验收：搜「三体 2023」能命中标题「三体（2023）」 */
    @Test
    fun `搜带年份的关键词能命中括注年份的标题`() {
        assertEquals(
            true,
            SearchSettings.matches("三体（2023）", null, "三体 2023", SearchSettings.MatchMode.Smart),
        )
        // 不回归：搜「三体」照旧命中
        assertEquals(
            true,
            SearchSettings.matches("三体（2023）", null, "三体", SearchSettings.MatchMode.Smart),
        )
        // 年份不同不该命中（`三体2023` vs `三体2019`）
        assertEquals(
            false,
            SearchSettings.matches("三体（2019）", null, "三体 2023", SearchSettings.MatchMode.Smart),
        )
    }

    /** 相关度也要跟着双变体走，否则会"准入通过但分数为 0" */
    @Test
    fun `双变体下的相关度不为零`() {
        assertEquals(
            true,
            SearchSettings.relevanceScore("三体（2023）", null, "三体 2023", null) > 0,
        )
        // ⚠️ 实际给的是 **5（全等）**，不是 3（包含）——
        // 因为「三体（2023）」的第二个变体就是 `三体2023`，与查询归一化结果**完全相等**。
        // 这比"包含"更准：搜「三体 2023」时，标题「三体（2023）」确实就是同一部片，
        // 而不只是"标题里恰好含这几个字"。
        assertEquals(
            5,
            SearchSettings.relevanceScore("三体（2023）", null, "三体 2023", null),
        )
    }

    // ==================== 番号判据收紧(2026-10-18) ====================

    /**
     * 「片名 + 年份」不是番号。
     *
     * <p>旧实现只要 `extractCode` 非空就短路进番号语义，于是 `IT 1990` 被判成番号
     * ⇒ 要求标题里出现带边界检查的 `it1990`，而标题「IT (1990) 小丑回魂」归一化后
     * 年份被括号删掉 ⇒ 必然判不匹配、结果丢失。
     */
    @Test
    fun `片名加年份不算番号查询`() {
        assertEquals(false, SearchSettings.looksLikeCodeQuery("IT 1990"))
        assertEquals(false, SearchSettings.looksLikeCodeQuery("IT1990"))
        // 真番号照旧
        assertEquals(true, SearchSettings.looksLikeCodeQuery("ADN-811"))
        assertEquals(true, SearchSettings.looksLikeCodeQuery("ADN811"))
        assertEquals(true, SearchSettings.looksLikeCodeQuery("MIAA-195"))
        assertEquals(true, SearchSettings.looksLikeCodeQuery("FC2-PPV-4925855"))
        // 短号**必须仍走番号语义** —— 否则番号语义的边界检查失效，
        // `ADN-81` 会经通用匹配的裸 contains 命中 `ADN-811`（老缺陷回归）
        assertEquals(true, SearchSettings.looksLikeCodeQuery("ADN-81"))
        // 中文查询不是番号
        assertEquals(false, SearchSettings.looksLikeCodeQuery("三体"))
        assertEquals(false, SearchSettings.looksLikeCodeQuery("三体 2023"))
    }

    /** 短号误命中长号必须继续被挡住（这是番号语义存在的理由） */
    @Test
    fun `短号不误命中长号`() {
        assertEquals(
            false,
            SearchSettings.matches("ADN-811", null, "ADN-81", SearchSettings.MatchMode.Smart),
        )
        // 版本后缀不参与匹配
        assertEquals(
            true,
            SearchSettings.matches("ADN-811", null, "ADN-811-RM", SearchSettings.MatchMode.Smart),
        )
    }
}
