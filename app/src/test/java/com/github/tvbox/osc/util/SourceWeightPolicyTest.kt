package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源权重模型判据单测:锁住《源与线路的权重模型》的三条底气 ——
 * ① 时间衰减(自动向中性回归,不死锁);② 证据强度不对称(播放类扣分 < 搜索类);
 * ③ 保底配额(低权重源仍会被选中,且能轮换)。
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