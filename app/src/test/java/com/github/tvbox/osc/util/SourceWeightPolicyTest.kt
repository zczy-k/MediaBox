package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源权重模型判据单测:锁住《源与线路的权重模型》的底气 ——
 * ① 时间衰减(自动向中性回归,不死锁);② 证据强度不对称(播放类扣分 < 搜索类);
 * ③ 保底配额(低权重源仍会被选中,且能轮换);④ 长期分(用得越久越懂哪个源好,但只降位次不封禁)。
 */
class SourceWeightPolicyTest {

    private val t0 = 1_000_000_000_000L

    // ==================== 时间衰减 ====================

    @Test
    fun `无记录时按中性分`() {
        assertEquals(SourceWeightPolicy.NEUTRAL, SourceWeightPolicy.scoreOf(null, t0))
        assertEquals(SourceWeightPolicy.NEUTRAL, SourceWeightPolicy.scoreOf(SourceWeightPolicy.Weight(), t0))
        assertEquals(SourceWeightPolicy.NEUTRAL.toDouble(), SourceWeightPolicy.effective(null, t0), 0.0001)
    }

    @Test
    fun `刚记录未衰减_读数等于落账分`() {
        val w = SourceWeightPolicy.Weight(100, t0)
        assertEquals(100, SourceWeightPolicy.scoreOf(w, t0))
        val low = SourceWeightPolicy.Weight(20, t0)
        assertEquals(20, SourceWeightPolicy.scoreOf(low, t0))
    }

    @Test
    fun `半个半衰期后向中性回归`() {
        // 100 → 半衰期后应为 60 + 40*0.5 = 80
        val high = SourceWeightPolicy.Weight(100, t0)
        assertEquals(80, SourceWeightPolicy.scoreOf(high, t0 + SourceWeightPolicy.HALF_LIFE_MS))
        // 0  → 60 + (0-60)*0.5 = 30
        val low = SourceWeightPolicy.Weight(0, t0)
        assertEquals(30, SourceWeightPolicy.scoreOf(low, t0 + SourceWeightPolicy.HALF_LIFE_MS))
        // 两个半衰期:100 → 60 + 40*0.25 = 70
        assertEquals(70, SourceWeightPolicy.scoreOf(high, t0 + 2 * SourceWeightPolicy.HALF_LIFE_MS))
    }

    @Test
    fun `始终朝中性收敛_不会越过中性`() {
        val high = SourceWeightPolicy.Weight(100, t0)
        val low = SourceWeightPolicy.Weight(0, t0)
        val far = t0 + 100 * SourceWeightPolicy.HALF_LIFE_MS
        assertEquals(SourceWeightPolicy.NEUTRAL, SourceWeightPolicy.scoreOf(high, far))
        assertEquals(SourceWeightPolicy.NEUTRAL, SourceWeightPolicy.scoreOf(low, far))
    }

    @Test
    fun `时钟回拨按0处理_不放大衰减`() {
        val w = SourceWeightPolicy.Weight(100, t0)
        // now 在未来时间戳之前:elapsed 取 0 ⇒ 不衰减,读数仍是 100
        assertEquals(100, SourceWeightPolicy.scoreOf(w, t0 - 5 * SourceWeightPolicy.HALF_LIFE_MS))
    }

    // ==================== 证据强度与更新 ====================

    @Test
    fun `证据强度不对称_播放类扣分轻于搜索类`() {
        assertTrue(
            SourceWeightPolicy.deltaFor(SourceWeightPolicy.EvidenceKind.PLAY_FAIL_LINE) >
                SourceWeightPolicy.deltaFor(SourceWeightPolicy.EvidenceKind.SEARCH_TIMEOUT),
        )
        // 最强正向:成功播放
        assertTrue(
            SourceWeightPolicy.deltaFor(SourceWeightPolicy.EvidenceKind.PLAY_SUCCESS) > 0,
        )
    }

    @Test
    fun `记录时先衰减旧分再叠加增量`() {
        // 100 在半衰期后为 80,再扣 25 ⇒ 55(而不是 100-25=75)
        val w = SourceWeightPolicy.Weight(100, t0)
        val after = SourceWeightPolicy.record(
            w, SourceWeightPolicy.EvidenceKind.SEARCH_TIMEOUT, t0 + SourceWeightPolicy.HALF_LIFE_MS,
        )
        assertEquals(55, after.score)
        assertEquals(t0 + SourceWeightPolicy.HALF_LIFE_MS, after.at)
    }

    @Test
    fun `分值与上下限被裁剪`() {
        val high = SourceWeightPolicy.record(SourceWeightPolicy.Weight(95, t0),
            SourceWeightPolicy.EvidenceKind.PLAY_SUCCESS, t0)
        assertEquals(SourceWeightPolicy.MAX_SCORE, high.score)
        val low = SourceWeightPolicy.record(SourceWeightPolicy.Weight(10, t0),
            SourceWeightPolicy.EvidenceKind.PLAY_FAIL_SOURCE, t0)
        assertEquals(SourceWeightPolicy.MIN_SCORE, low.score)
    }

    @Test
    fun `无旧记录时从中性分起算`() {
        val w = SourceWeightPolicy.record(null, SourceWeightPolicy.EvidenceKind.SEARCH_ANSWERED, t0)
        assertEquals(SourceWeightPolicy.NEUTRAL + 20, w.score)
    }

    // ==================== 长期分(用得越久越懂哪个源好) ====================

    @Test
    fun `样本不足时没有长期意见_读数与旧版一致`() {
        assertNull(SourceWeightPolicy.longTermScore(3.0, 0.0))
        assertNull(SourceWeightPolicy.longTermScore(0.0, 0.0))
        // 只有 3 条证据 ⇒ effective 退化为纯短期分
        val w = SourceWeightPolicy.Weight(100, t0, ok = 3.0, bad = 0.0)
        assertEquals(100, SourceWeightPolicy.scoreOf(w, t0))
    }

    @Test
    fun `攒够样本后长期分与短期分五五开`() {
        val good = SourceWeightPolicy.Weight(SourceWeightPolicy.NEUTRAL, t0, ok = 4.0, bad = 0.0)
        // rate=(4+1)/6=0.8333 ⇒ long=25+65*0.8333≈79.17 ⇒ (60+79.17)/2≈69.58
        assertEquals(70, SourceWeightPolicy.scoreOf(good, t0))
        val bad = SourceWeightPolicy.Weight(SourceWeightPolicy.NEUTRAL, t0, ok = 0.0, bad = 4.0)
        // rate=1/6 ⇒ long≈35.83 ⇒ (60+35.83)/2≈47.92
        assertEquals(48, SourceWeightPolicy.scoreOf(bad, t0))
    }

    @Test
    fun `长期分只降位次不封禁_最差也只落到黄档`() {
        val worst = SourceWeightPolicy.Weight(SourceWeightPolicy.NEUTRAL, t0, ok = 0.0, bad = 999.0)
        // 长期分被压在 [25,90],与中性 60 五五开 ⇒ 读数 ≥ 30,永远进不了 RED
        assertTrue(SourceWeightPolicy.scoreOf(worst, t0) >= SourceWeightPolicy.TIER_YELLOW)
        assertSame(
            SourceWeightPolicy.Tier.YELLOW,
            SourceWeightPolicy.tier(worst, t0),
        )
    }

    @Test
    fun `长期意见给坏日子兜底_同分有回忆的排前面`() {
        val remembered = SourceWeightPolicy.Weight(20, t0, ok = 8.0, bad = 0.0)
        val unknown = SourceWeightPolicy.Weight(20, t0)
        assertTrue(
            "长期全好的源不该被一次落账分压到和没记忆的源同级: " +
                SourceWeightPolicy.scoreOf(remembered, t0),
            SourceWeightPolicy.scoreOf(remembered, t0) > SourceWeightPolicy.scoreOf(unknown, t0),
        )
    }

    @Test
    fun `长期计数按7天半衰_久用不上就淡出`() {
        val w = SourceWeightPolicy.Weight(100, t0, ok = 8.0, bad = 0.0)
        // 1 个长期半衰期:计数 8→4 刚好够样本,长期意见仍拉住读数(短期分早已回到 60)
        assertEquals(70, SourceWeightPolicy.scoreOf(w, t0 + SourceWeightPolicy.LONG_HALF_LIFE_MS))
        // 2 个半衰期:计数 8→2 不够样本 ⇒ 只剩中性分,意见自然淡出
        assertEquals(
            SourceWeightPolicy.NEUTRAL,
            SourceWeightPolicy.scoreOf(w, t0 + 2 * SourceWeightPolicy.LONG_HALF_LIFE_MS),
        )
    }

    @Test
    fun `记证据时同步累积长期计数`() {
        val answered = SourceWeightPolicy.EvidenceKind.SEARCH_ANSWERED
        val timeout = SourceWeightPolicy.EvidenceKind.SEARCH_TIMEOUT
        var w = SourceWeightPolicy.record(null, answered, t0)
        assertEquals(1.0, w.ok, 0.0001)
        assertEquals(0.0, w.bad, 0.0001)
        w = SourceWeightPolicy.record(w, answered, t0)
        assertEquals(2.0, w.ok, 0.0001)
        w = SourceWeightPolicy.record(w, timeout, t0)
        assertEquals(1.0, w.bad, 0.0001)
        assertEquals(2.0, w.ok, 0.0001)
        // 播放类证据同样计入长期账(只是短期扣分轻)
        val played = SourceWeightPolicy.record(
            w, SourceWeightPolicy.EvidenceKind.PLAY_FAIL_SOURCE, t0,
        )
        assertEquals(2.0, played.bad, 0.0001)
    }

    @Test
    fun `计数落账前先衰减_久远的证据不会一直压着`() {
        val w = SourceWeightPolicy.record(
            SourceWeightPolicy.Weight(60, t0, ok = 0.0, bad = 8.0),
            SourceWeightPolicy.EvidenceKind.PLAY_SUCCESS,
            t0 + 2 * SourceWeightPolicy.LONG_HALF_LIFE_MS,
        )
        // 8 条坏账经 2 个半衰期只剩 2,再加 1 条好账 ⇒ 好账占到一半
        assertEquals(1.0, w.ok, 0.0001)
        assertEquals(2.0, w.bad, 0.0001)
    }

    // ==================== 显示档位 ====================

    @Test
    fun `显示三档按阈值映射`() {
        assertEquals(SourceWeightPolicy.Tier.GREEN, SourceWeightPolicy.tier(SourceWeightPolicy.Weight(60, t0), t0))
        assertEquals(SourceWeightPolicy.Tier.GREEN, SourceWeightPolicy.tier(SourceWeightPolicy.Weight(55, t0), t0))
        assertEquals(SourceWeightPolicy.Tier.YELLOW, SourceWeightPolicy.tier(SourceWeightPolicy.Weight(54, t0), t0))
        assertEquals(SourceWeightPolicy.Tier.YELLOW, SourceWeightPolicy.tier(SourceWeightPolicy.Weight(30, t0), t0))
        assertEquals(SourceWeightPolicy.Tier.RED, SourceWeightPolicy.tier(SourceWeightPolicy.Weight(29, t0), t0))
    }

    // ==================== 保底配额 ====================

    @Test
    fun `保底配额取权重最低的N个`() {
        val sources = listOf("a", "b", "c", "d")
        val score = mapOf("a" to 80, "b" to 20, "c" to 60, "d" to 30)
        val picked = SourceWeightPolicy.floorQuota(
            sources,
            scoreAt = { score.getValue(sources[it]) },
            lastSearchedAtAt = { 0L },
            n = 3,
        )
        // 最低三个:b(20) < d(30) < c(60)
        assertEquals(listOf("b", "d", "c"), picked)
    }

    @Test
    fun `保底配额在权重并列时优先最久未搜的`() {
        val sources = listOf("old", "new", "mid")
        val lastSearched = mapOf("old" to 0L, "new" to 300L, "mid" to 100L)
        val picked = SourceWeightPolicy.floorQuota(
            sources,
            scoreAt = { 50 },
            lastSearchedAtAt = { lastSearched.getValue(sources[it]) },
            n = 2,
        )
        // 权重相同 ⇒ 按上次被搜时间升序:old(0) → mid(100)
        assertEquals(listOf("old", "mid"), picked)
    }

    @Test
    fun `候选不足返回全部_配额非正返回空`() {
        val sources = listOf("a", "b")
        // 候选数 <= n:全部返回(无需保底)
        val all = SourceWeightPolicy.floorQuota(sources, { 0 }, { 0L }, n = 3)
        assertSame(sources, all)
        // n <= 0 = 关闭保底:返回空(绝不能返回全部,否则"关闭"反而把全网都塞进首轮)
        val off = SourceWeightPolicy.floorQuota(sources, { 0 }, { 0L }, n = 0)
        assertTrue(off.isEmpty())
        assertTrue(SourceWeightPolicy.floorQuota(sources, { 0 }, { 0L }, n = -1).isEmpty())
        val empty = SourceWeightPolicy.floorQuota(emptyList<String>(), { 0 }, { 0L }, n = 3)
        assertTrue(empty.isEmpty())
    }
}