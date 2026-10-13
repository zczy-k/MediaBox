package com.github.tvbox.osc.util

import com.github.tvbox.osc.player.LineHealthPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四层恢复判据单测(P0-3)。锁三件事:
 * ① L1 只平反"纯网络类"封禁;② L2 探活只选"含源类证据 + 快到期"的;③ 永久封禁任何自动层都不动。
 */
class RecoveryPolicyTest {

    private val t0 = 1_000_000_000_000L
    private fun fail(kind: SourceFailKind, at: Long, title: String = "片") =
        SourceFailEvent(kind, at, SourceHealthPolicy.contentKey(title))

    // ==================== L1 isNetworkOnlyBan ====================

    @Test
    fun `纯超时封禁可平反`() {
        val st = SourceHealthState(
            fails = listOf(fail(SourceFailKind.SEARCH_TIMEOUT, t0)),
            bannedUntil = t0 + SourceHealthPolicy.TIMEOUT_BAN_MS,
        )
        assertTrue(RecoveryPolicy.isNetworkOnlyBan(st, t0 + 1000))
    }

    @Test
    fun `空证据的封禁(规则2封禁后)可平反`() {
        // ban() 会清空 fails;规则 2 封禁时 streak 也已清零 ⇒ fails 为空
        val st = SourceHealthState(fails = emptyList(), bannedUntil = t0 + 60_000)
        assertTrue(RecoveryPolicy.isNetworkOnlyBan(st, t0 + 1000))
    }

    @Test
    fun `含搜索失败证据的封禁不可平反`() {
        val st = SourceHealthState(
            fails = listOf(fail(SourceFailKind.SEARCH_FAILED, t0)),
            bannedUntil = t0 + SourceHealthPolicy.FIRST_BAN_MS,
        )
        assertFalse(RecoveryPolicy.isNetworkOnlyBan(st, t0 + 1000))
    }

    @Test
    fun `混有源类证据的封禁不可平反`() {
        val st = SourceHealthState(
            fails = listOf(fail(SourceFailKind.SEARCH_TIMEOUT, t0), fail(SourceFailKind.SEARCH_FAILED, t0)),
            bannedUntil = t0 + SourceHealthPolicy.FIRST_BAN_MS,
        )
        assertFalse(RecoveryPolicy.isNetworkOnlyBan(st, t0 + 1000))
    }

    @Test
    fun `永久封禁不可平反`() {
        val st = SourceHealthState(manualLocked = true, bannedUntil = 0L)
        assertFalse(RecoveryPolicy.isNetworkOnlyBan(st, t0))
    }

    @Test
    fun `未封禁状态无需平反`() {
        val st = SourceHealthState(fails = emptyList(), bannedUntil = 0L)
        assertFalse(RecoveryPolicy.isNetworkOnlyBan(st, t0))
    }

    @Test
    fun `已过期的封禁无需平反`() {
        val st = SourceHealthState(bannedUntil = t0 - 1)
        assertFalse(RecoveryPolicy.isNetworkOnlyBan(st, t0))
    }

    // ==================== L1 liftNetworkBan ====================

    @Test
    fun `平反清封禁与证据_保留banCount`() {
        val st = SourceHealthState(
            fails = listOf(fail(SourceFailKind.SEARCH_TIMEOUT, t0)),
            banCount = 2,
            bannedUntil = t0 + 60_000,
            timeoutStreak = 1,
        )
        val lifted = RecoveryPolicy.liftNetworkBan(st)
        assertTrue(lifted.fails.isEmpty())
        assertEquals(0L, lifted.bannedUntil)
        assertEquals(0, lifted.timeoutStreak)
        // banCount 保留:平反的是这次误封,不是抹掉历史(与 SourceHealthPolicy.unblock 同口径)
        assertEquals(2, lifted.banCount)
    }

    // ==================== L2 probeCandidates ====================

    @Test
    fun `探活只选含源类证据且快到期的封禁源`() {
        val states = mapOf(
            // 纯网络类:由 L1 平反,不该进探活
            "netOnly" to SourceHealthState(
                fails = listOf(fail(SourceFailKind.SEARCH_TIMEOUT, t0)),
                bannedUntil = t0 + 60_000,
            ),
            // 含源类 + 快到期(剩余 10min ≤ 20min):应进
            "srcSoon" to SourceHealthState(
                fails = listOf(fail(SourceFailKind.SEARCH_FAILED, t0)),
                banCount = 1,
                bannedUntil = t0 + 10 * 60 * 1000,
            ),
            // 含源类 + 离到期还远(剩余 6h > 20min):不进
            "srcFar" to SourceHealthState(
                fails = listOf(fail(SourceFailKind.SEARCH_FAILED, t0)),
                banCount = 1,
                bannedUntil = t0 + SourceHealthPolicy.FIRST_BAN_MS,
            ),
            // 永久封禁:不动
            "perm" to SourceHealthState(manualLocked = true),
        )
        val picked = RecoveryPolicy.probeCandidates(states, t0 + 1000)
        assertEquals(listOf("srcSoon"), picked)
    }

    @Test
    fun `探活按轻封优先_再按快到期`() {
        val states = mapOf(
            "heavy" to SourceHealthState(
                fails = listOf(fail(SourceFailKind.SEARCH_FAILED, t0)),
                banCount = 2, bannedUntil = t0 + 5 * 60 * 1000,
            ),
            "lightLate" to SourceHealthState(
                fails = listOf(fail(SourceFailKind.SEARCH_FAILED, t0)),
                banCount = 1, bannedUntil = t0 + 15 * 60 * 1000,
            ),
            "lightSoon" to SourceHealthState(
                fails = listOf(fail(SourceFailKind.SEARCH_FAILED, t0)),
                banCount = 1, bannedUntil = t0 + 3 * 60 * 1000,
            ),
        )
        val picked = RecoveryPolicy.probeCandidates(states, t0 + 1000, limit = 2)
        // banCount 升序 ⇒ 轻封(1)优先;同为 1 则快到期优先
        assertEquals(listOf("lightSoon", "lightLate"), picked)
    }

    @Test
    fun `探活上限生效`() {
        val states = (1..10).associate { i ->
            "s$i" to SourceHealthState(
                fails = listOf(fail(SourceFailKind.SEARCH_FAILED, t0)),
                banCount = 1, bannedUntil = t0 + i * 60_000,
            )
        }
        assertEquals(3, RecoveryPolicy.probeCandidates(states, t0 + 1000).size)
        assertEquals(1, RecoveryPolicy.probeCandidates(states, t0 + 1000, limit = 1).size)
        assertTrue(RecoveryPolicy.probeCandidates(states, t0 + 1000, limit = 0).isEmpty())
    }

    @Test
    fun `无封禁源时探活为空`() {
        val states = mapOf("ok" to SourceHealthState(bannedUntil = 0L))
        assertTrue(RecoveryPolicy.probeCandidates(states, t0).isEmpty())
    }

    // ==================== 线路级 ====================

    @Test
    fun `弱网期的线路可疑在网络恢复时解除`() {
        val suspect = LineHealthPolicy.State(failStreak = 2, failAt = t0)
        assertTrue(RecoveryPolicy.shouldLiftLineSuspect(suspect, t0 + 1000, weakNetworkDuringFail = true))
        // 非弱网期造成的可疑:不动(可能是真坏线)
        assertFalse(RecoveryPolicy.shouldLiftLineSuspect(suspect, t0 + 1000, weakNetworkDuringFail = false))
    }

    @Test
    fun `非可疑线路无需解除`() {
        val healthy = LineHealthPolicy.State(failStreak = 0)
        assertFalse(RecoveryPolicy.shouldLiftLineSuspect(healthy, t0, weakNetworkDuringFail = true))
    }
}