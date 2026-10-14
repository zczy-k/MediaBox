package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CandidateMatchPolicy] 阈值锁(2026-10-14)。
 *
 * 钉住的是"换源候选 vs 相关推荐"的分界线:换源是拿别人的源播这一部片,判错=播放事故,
 * 所以候选必须比搜索页更严;但也不能退回旧的二值判定(那条把"名字略不同"的直接丢掉 = 漏搜)。
 */
class CandidateMatchPolicyTest {

    @Test
    fun `标题包含关键词及以上才算同一部片`() {
        // 5 全等 / 4 前缀 / 3 包含 ⇒ 候选
        (3..5).forEach {
            assertEquals("score=$it", CandidateMatchPolicy.Verdict.CANDIDATE, CandidateMatchPolicy.verdictOf(it))
        }
    }

    @Test
    fun `仅别名或每词都出现只进相关推荐`() {
        assertEquals(CandidateMatchPolicy.Verdict.RELATED, CandidateMatchPolicy.verdictOf(2))
        assertEquals(CandidateMatchPolicy.Verdict.RELATED, CandidateMatchPolicy.verdictOf(1))
    }

    @Test
    fun `不匹配与意图冲突惩罚都丢弃`() {
        assertEquals(CandidateMatchPolicy.Verdict.DROP, CandidateMatchPolicy.verdictOf(0))
        // 年份/季数冲突的负分(用户明说"就要 2019 版")不得混进候选
        assertEquals(CandidateMatchPolicy.Verdict.DROP, CandidateMatchPolicy.verdictOf(-2))
    }

    @Test
    fun `isSameTitleScore 与候选档一致`() {
        (3..9).forEach { assertEquals(true, CandidateMatchPolicy.isSameTitleScore(it)) }
        (-9..2).forEach { assertEquals(false, CandidateMatchPolicy.isSameTitleScore(it)) }
    }
}
