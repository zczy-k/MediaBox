package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放失败短期记忆判据单测(问题 17)。
 * 锁三件事:① TTL 内失败、过期自动遗忘;② 时钟回拨保护;③ 全候选失败判定。
 */
class PlaybackFailurePolicyTest {

    private val t0 = 1_000_000_000_000L

    @Test
    fun `无失败记录不算失败`() {
        assertFalse(PlaybackFailurePolicy.isFailed(0L, t0))
        assertFalse(PlaybackFailurePolicy.isFailed(-1L, t0))
    }

    @Test
    fun `刚记录在TTL内算失败`() {
        assertTrue(PlaybackFailurePolicy.isFailed(t0, t0))
        assertTrue(PlaybackFailurePolicy.isFailed(t0, t0 + PlaybackFailurePolicy.TTL_MS - 1))
    }

    @Test
    fun `超过TTL自动遗忘`() {
        // 恰好到期即遗忘(边界:elapsed >= TTL ⇒ 不算失败)
        assertFalse(PlaybackFailurePolicy.isFailed(t0, t0 + PlaybackFailurePolicy.TTL_MS))
        assertFalse(PlaybackFailurePolicy.isFailed(t0, t0 + PlaybackFailurePolicy.TTL_MS + 60_000))
    }

    @Test
    fun `时钟回拨按刚失败处理`() {
        // now 早于 failedAt(手动改时间):按"刚失败"处理,不因负 elapsed 翻成未失败
        assertTrue(PlaybackFailurePolicy.isFailed(t0, t0 - 60_000))
    }

    @Test
    fun `全候选失败判定_有未失败的即返回假`() {
        val keys = listOf("a", "b", "c")
        val failedAt = mapOf("a" to t0, "b" to t0, "c" to 0L) // c 未失败
        assertFalse(
            PlaybackFailurePolicy.allCandidatesFailed(keys, { failedAt[it] ?: 0L }, t0 + 1000),
        )
    }

    @Test
    fun `全候选失败判定_全部失败返回真`() {
        val keys = listOf("a", "b")
        val failedAt = mapOf("a" to t0, "b" to t0)
        assertTrue(
            PlaybackFailurePolicy.allCandidatesFailed(keys, { failedAt[it] ?: 0L }, t0 + 1000),
        )
    }

    @Test
    fun `全候选失败判定_部分失败已过期则返回假`() {
        val keys = listOf("a", "b")
        // a 失败仍在 TTL 内、b 失败已过期 ⇒ b 可重试 ⇒ 不算全失败
        val failedAt = mapOf("a" to t0, "b" to (t0 - PlaybackFailurePolicy.TTL_MS - 1))
        assertFalse(
            PlaybackFailurePolicy.allCandidatesFailed(keys, { failedAt[it] ?: 0L }, t0),
        )
    }

    @Test
    fun `空候选不算全失败`() {
        // 空池是"还没结果",不是"全废了" —— 不能误判成失败终态
        assertFalse(
            PlaybackFailurePolicy.allCandidatesFailed(emptyList(), { 0L }, t0),
        )
    }

    @Test
    fun `TTL常量与设计一致`() {
        assertEquals(5L * 60 * 1000, PlaybackFailurePolicy.TTL_MS)
    }
}