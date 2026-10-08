package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防滥用封禁判据的边界(需求 1/2/3 的口径全在这里锁住)。
 *
 * <p>三条最容易写反的地方:
 * ① 只数次数不看影片数 ⇒ "同一部片连点三次"就能封源(可能只是那部片被源站下架);
 * ② 封禁时忘了清证据 ⇒ 6 小时"自动解封"后旧证据仍在窗口里,下一次失败立刻又封,等于永不自动解封;
 * ③ 解封时把升级次数一起清掉 ⇒ 永远只封 6 小时,第二/三档形同虚设。
 *
 * <p>用例里的多次失败都打**同一个时间戳**,封禁时刻才可预期(封禁时长从触发那一次算起)。
 */
class SourceHealthPolicyTest {

    private val t0 = 1_700_000_000_000L
    private val hour = 60L * 60L * 1000L
    private val day = 24 * hour

    /** 连记 [fails] 次失败(片名轮流取,按需凑"不同影片数") */
    private fun state(
        base: SourceHealthState = SourceHealthState(),
        kind: SourceFailKind = SourceFailKind.SEARCH_TIMEOUT,
        fails: Int,
        titles: List<String> = listOf("电影A", "电影B"),
        at: Long = t0,
    ): SourceHealthState {
        var s = base
        for (i in 0 until fails) {
            s = SourceHealthPolicy.recordFail(s, kind, SourceHealthPolicy.contentKey(titles[i % titles.size]), at)
        }
        return s
    }

    // ---------- 触发门槛(需求 1/3) ----------

    @Test
    fun threeFailsTwoFilms_bansSixHours() {
        val s = state(fails = 3, titles = listOf("电影A", "电影B"))
        assertEquals(1, s.banCount)
        assertFalse(s.manualLocked)
        assertTrue(SourceHealthPolicy.isBlocked(s, t0))
        assertTrue(SourceHealthPolicy.isBlocked(s, t0 + 6 * hour - 1))
        assertFalse(SourceHealthPolicy.isBlocked(s, t0 + 6 * hour))
    }

    @Test
    fun threeFailsSameFilm_doesNotBan() {
        // 同一部片连点三次:可能只是那部片在源站被下架,不该封源
        val s = state(fails = 3, titles = listOf("电影A"))
        assertEquals(0, s.banCount)
        assertFalse(SourceHealthPolicy.isBlocked(s, t0))
    }

    @Test
    fun twoFailsTwoFilms_doesNotBan() {
        val s = state(fails = 2, titles = listOf("电影A", "电影B"))
        assertEquals(0, s.banCount)
    }

    @Test
    fun failsOutsideWindow_doNotCount() {
        var s = SourceHealthPolicy.recordFail(
            SourceHealthState(), SourceFailKind.SEARCH_TIMEOUT, SourceHealthPolicy.contentKey("电影A"), t0,
        )
        s = SourceHealthPolicy.recordFail(
            s, SourceFailKind.SEARCH_TIMEOUT, SourceHealthPolicy.contentKey("电影B"), t0 + 1,
        )
        // 25 小时后:前两条已出窗口,窗口内只剩这一条
        val later = t0 + 25 * hour
        s = SourceHealthPolicy.recordFail(
            s, SourceFailKind.SEARCH_TIMEOUT, SourceHealthPolicy.contentKey("电影C"), later,
        )
        assertEquals(0, s.banCount)
        assertFalse(SourceHealthPolicy.isBlocked(s, later))
    }

    @Test
    fun searchTimeoutAlwaysCounts() {
        // 需求规则 3:搜索超时一律计一次失败
        val s = state(kind = SourceFailKind.SEARCH_TIMEOUT, fails = 3)
        assertEquals(1, s.banCount)
    }

    @Test
    fun eachKindCountsOnce_noWeighting() {
        var s = SourceHealthState()
        s = SourceHealthPolicy.recordFail(s, SourceFailKind.SEARCH_TIMEOUT, SourceHealthPolicy.contentKey("电影A"), t0)
        s = SourceHealthPolicy.recordFail(s, SourceFailKind.SEARCH_FAILED, SourceHealthPolicy.contentKey("电影B"), t0)
        s = SourceHealthPolicy.recordFail(s, SourceFailKind.PLAY_FAILED, SourceHealthPolicy.contentKey("电影A"), t0)
        assertEquals(1, s.banCount)
    }

    @Test
    fun missingTitle_countsAsFailButNotAsFilm() {
        // 取不到片名时仍计次数、但不凑"不同影片数":否则三次无名失败就能封源
        val s = state(fails = 3, titles = listOf(""))
        assertEquals(0, s.banCount)
        assertEquals(3, s.fails.size)
    }

    // ---------- 时长升级与自动解封(需求 2) ----------

    @Test
    fun secondBanEscalatesToTwentyFourHours() {
        var s = state(fails = 3, titles = listOf("电影A", "电影B"))
        // 首次 6 小时已过 ⇒ 自动解封,证据清空
        val t1 = t0 + 7 * hour
        s = SourceHealthPolicy.normalize(s, t1)
        assertFalse(SourceHealthPolicy.isBlocked(s, t1))
        assertEquals(0, s.fails.size)
        assertEquals(1, s.banCount)
        // 再攒够三次 ⇒ 第二档 24 小时
        s = state(base = s, kind = SourceFailKind.PLAY_FAILED, fails = 3, titles = listOf("电影C", "电影D", "电影E"), at = t1)
        assertEquals(2, s.banCount)
        assertTrue(SourceHealthPolicy.isBlocked(s, t1 + 24 * hour - 1))
        assertFalse(SourceHealthPolicy.isBlocked(s, t1 + 24 * hour))
    }

    @Test
    fun thirdBanIsPermanentUntilManualUnblock() {
        val s = SourceHealthPolicy.ban(SourceHealthState(banCount = 2), t0)
        assertEquals(3, s.banCount)
        assertTrue(s.manualLocked)
        // 多久都不自动解封
        assertTrue(SourceHealthPolicy.isBlocked(s, t0 + 3650 * day))
        val freed = SourceHealthPolicy.unblock(s)
        assertFalse(SourceHealthPolicy.isBlocked(freed, t0))
        // 手动解除保留升级次数:再犯仍是永久档(不能被当成后门绕过升级)
        assertEquals(3, freed.banCount)
        val again = state(base = freed, kind = SourceFailKind.PLAY_FAILED, fails = 3, at = t0 + 1000)
        assertTrue(again.manualLocked)
    }

    @Test
    fun banClearsEvidence_soAutoUnblockIsReal() {
        val banned = state(fails = 3)
        assertEquals(0, banned.fails.size)
        val t1 = t0 + 6 * hour
        val normalized = SourceHealthPolicy.normalize(banned, t1)
        assertFalse(SourceHealthPolicy.isBlocked(normalized, t1))
        // 解封后只差一次失败 ⇒ 不该立刻又封
        val oneMore = SourceHealthPolicy.recordFail(
            normalized, SourceFailKind.SEARCH_TIMEOUT, SourceHealthPolicy.contentKey("电影C"), t1,
        )
        assertFalse(SourceHealthPolicy.isBlocked(oneMore, t1))
        assertEquals(0, oneMore.banCount)
    }

    @Test
    fun permanentLock_ignoresFurtherFails() {
        val locked = SourceHealthPolicy.ban(SourceHealthState(banCount = 2), t0)
        val after = SourceHealthPolicy.recordFail(
            locked, SourceFailKind.PLAY_FAILED, SourceHealthPolicy.contentKey("电影A"), t0 + 10,
        )
        assertEquals(locked, after)
    }

    @Test
    fun manualUnblockResetsEvidenceAndBanWindow() {
        val s = state(fails = 3, titles = listOf("电影A", "电影B"))
        val freed = SourceHealthPolicy.unblock(s)
        assertEquals(0, freed.fails.size)
        assertEquals(0L, freed.bannedUntil)
        assertFalse(freed.manualLocked)
        assertFalse(SourceHealthPolicy.isBlocked(freed, t0))
    }

    // ---------- 内容键归一化 ----------

    @Test
    fun contentKey_normalizesWhitespaceAndCase() {
        assertEquals("电影a", SourceHealthPolicy.contentKey(" 电影A "))
        assertEquals("thematrix", SourceHealthPolicy.contentKey("The Matrix"))
        assertEquals("", SourceHealthPolicy.contentKey(null))
        assertEquals("", SourceHealthPolicy.contentKey("   "))
        // 同一部片在不同源只差空白/大小写 ⇒ 必须算作同一部(否则"不同影片数"被稀释)
        assertEquals(
            SourceHealthPolicy.contentKey("The Matrix"),
            SourceHealthPolicy.contentKey("the  matrix"),
        )
    }
}
