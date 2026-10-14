package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CandidateRankPolicy] 行为锁(2026-10-14)。
 *
 * 钉住三条最容易在后续重构里被改错的语义:
 * ①「缺手上这一集」必须沉到底,哪怕总覆盖率很高;
 * ②「只更到一半」的源在早期集数上必须算可用,不能被总覆盖率误杀;
 * ③数据不足一律中性,绝不因"还不知道"就降权。
 */
class CandidateRankPolicyTest {

    private val authority = (1..50).toSet()

    @Test
    fun `缺当前集的源判为不可用 - 即使总覆盖率很高`() {
        val almostComplete = ((1..50).toSet() - 25) // 49/50 = 98%
        assertTrue(CandidateRankPolicy.unavailableNow(almostComplete, 49, authority, 25))
        assertEquals(
            CandidateRankPolicy.SCORE_MISSING_CURRENT,
            CandidateRankPolicy.watchabilityRank(almostComplete, 49, authority, 25),
        )
    }

    @Test
    fun `只更到一半的源在看第5集时完全可用 - 不得被总覆盖率误杀`() {
        val upto20 = (1..20).toSet()
        assertFalse(CandidateRankPolicy.unavailableNow(upto20, 20, authority, 5))
        // 往后连续 16 集(5..20)未到权威末尾 ⇒ 部分档,分数明显高于"未知"
        val score = CandidateRankPolicy.watchabilityRank(upto20, 20, authority, 5)
        assertTrue("应高于中性分,实际=$score", score > CandidateRankPolicy.SCORE_UNKNOWN)
    }

    @Test
    fun `往后一集不缺的源排在只覆盖到中间之前`() {
        val full = (1..50).toSet()
        val upto20 = (1..20).toSet()
        assertTrue(
            CandidateRankPolicy.watchabilityRank(full, 50, authority, 5) >
                CandidateRankPolicy.watchabilityRank(upto20, 20, authority, 5),
        )
    }

    @Test
    fun `先验自述不足以播当前集判不可用 - 足够则可用`() {
        // 站方自述"更新至20集",用户要看第25集 ⇒ 该源此刻拿不到
        assertTrue(CandidateRankPolicy.unavailableNow(null, 20, authority, 25))
        assertEquals(
            CandidateRankPolicy.SCORE_MISSING_CURRENT,
            CandidateRankPolicy.watchabilityRank(null, 20, authority, 25),
        )
        // 自述更新至30集看第25集:可用,余量 5
        assertTrue(CandidateRankPolicy.watchabilityRank(null, 30, authority, 25) >
            CandidateRankPolicy.SCORE_UNKNOWN)
    }

    @Test
    fun `电影与集数不可数 - 全体中性,排序交给权重键`() {
        // 权威未知(confirmed 空):不判
        assertEquals(
            CandidateRankPolicy.SCORE_UNKNOWN,
            CandidateRankPolicy.watchabilityRank((1..20).toSet(), 20, emptySet(), 5),
        )
        // 当前集号不可数:不判
        assertEquals(
            CandidateRankPolicy.SCORE_UNKNOWN,
            CandidateRankPolicy.watchabilityRank((1..20).toSet(), 20, authority, 0),
        )
        // 该源毫无集数信息:中性,不与"缺当前集"同罪
        assertEquals(
            CandidateRankPolicy.SCORE_UNKNOWN,
            CandidateRankPolicy.watchabilityRank(null, 0, authority, 25),
        )
    }

    @Test
    fun `单源观测不足以断言缺集 - 权威没这一集就不许判死`() {
        // own 已知、权威里却没有第 25 集(说明该集号本身未被印证)⇒ 不能判这源缺集
        assertFalse(CandidateRankPolicy.unavailableNow((1..20).toSet(), 20, (1..24).toSet(), 25))
    }

    @Test
    fun `runFrom 只在有数据时起算`() {
        assertEquals(-1, CandidateRankPolicy.runFrom(emptySet(), 5))
        assertEquals(-1, CandidateRankPolicy.runFrom((1..10).toSet(), 0))
        assertEquals(6, CandidateRankPolicy.runFrom((1..10).toSet(), 5))
        assertEquals(0, CandidateRankPolicy.runFrom((1..10).toSet(), 11))
    }
}
