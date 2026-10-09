package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防滥用封禁判据的边界(需求 1/2/3 的口径全在这里锁住)。
 *
 * <p>四条最容易写反的地方:
 * ① **升级档位**只看次数不看影片数 ⇒ "同一部片连点三次"就能一路升到永久封禁
 *   (那可能只是那部片被源站下架);注意规则 2(连续超时)是**故意**不看影片数的,两者口径不同;
 * ② 封禁时忘了清证据 ⇒ 6 小时"自动解封"后旧证据仍在窗口里,下一次失败立刻又封,等于永不自动解封;
 * ③ 解封时把升级次数一起清掉 ⇒ 永远只封 6 小时,第二/三档形同虚设;
 * ④ 让超时也去累计升级档位 ⇒ 一次网络抖动就可能把好源推到**永久封禁**,而超时是最弱的证据。
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
    fun threeFailsSameFilm_doesNotEscalate() {
        // 同一部片连点三次:可能只是那部片在源站被下架,不该走**升级档位**(那会一路到永久封禁)。
        //
        // ⚠️ 2026-10-09:它仍会命中**规则 2**(连续超时 2 次 → 1 小时临时封)——
        // 因为超时是**内容无关**的证据:"源连响应都没有"与你搜的是哪部片无关。
        // 所以这里只断言"不升级、不永久",不再断言"完全没被封"(那正是旧口径)。
        val s = state(fails = 3, titles = listOf("电影A"))
        assertEquals(0, s.banCount)
        assertFalse(s.manualLocked)
        // 封的是 1 小时的临时档,不是 6 小时
        assertTrue(SourceHealthPolicy.isBlocked(s, t0 + hour - 1))
        assertFalse(SourceHealthPolicy.isBlocked(s, t0 + hour))
    }

    @Test
    fun threePlayFailsSameFilm_neverBans() {
        // 同一部片三次**起播失败**:既不够升级档位(只涉及 1 部影片),又不是超时
        // ⇒ 两条规则都不该触发。这条守住"不是所有失败都算数"
        val s = state(kind = SourceFailKind.PLAY_FAILED, fails = 3, titles = listOf("电影A"))
        assertEquals(0, s.banCount)
        assertFalse(SourceHealthPolicy.isBlocked(s, t0))
    }

    @Test
    fun twoFailsTwoFilms_doNotEscalate() {
        // 两次失败不够**升级档位**(要 3 次)。⚠️ 2026-10-09:如果这两次都是**超时**,
        // 它会命中规则 2 的 1 小时临时封 —— 这是设计使然(超时与搜什么片无关)。
        // 所以这里只断言"没有升级",并显式确认封的是 1 小时那一档。
        val s = state(fails = 2, titles = listOf("电影A", "电影B"))
        assertEquals(0, s.banCount)
        assertTrue(SourceHealthPolicy.isBlocked(s, t0 + hour - 1))
        assertFalse(SourceHealthPolicy.isBlocked(s, t0 + hour))
    }

    @Test
    fun twoFailsTwoFilms_nonTimeoutKinds_neverBan() {
        // 同样的"两次失败",只要不是超时,两条规则都不该触发
        val s = state(kind = SourceFailKind.PLAY_FAILED, fails = 2, titles = listOf("电影A", "电影B"))
        assertEquals(0, s.banCount)
        assertFalse(SourceHealthPolicy.isBlocked(s, t0))
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
        // 解封后只差一次失败 ⇒ 不该立刻又封(升级次数仍停在 1,证据从 0 重新计)
        val oneMore = SourceHealthPolicy.recordFail(
            normalized, SourceFailKind.SEARCH_TIMEOUT, SourceHealthPolicy.contentKey("电影C"), t1,
        )
        assertEquals(1, oneMore.banCount)
        assertEquals(1, oneMore.fails.size)
        assertFalse(SourceHealthPolicy.isBlocked(oneMore, t1))
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

    // ---------- 规则 2:连续超时(2026-10-09) ----------
    // 存在的理由:超时是**内容无关**的证据,而规则 1 要求"≥2 部不同影片" ——
    // 两者混在一起时,用户要连搜三部不同的片子才会开始变快(真机实测 328 源里 289 个超时)。

    @Test
    fun twoConsecutiveTimeouts_banOneHourWithoutEscalating() {
        // 连 2 次超时即封,**同一部片也算**(超时与搜什么无关)
        val s = state(fails = 2, titles = listOf("电影A"))
        assertTrue(SourceHealthPolicy.isBlocked(s, t0))
        assertTrue(SourceHealthPolicy.isBlocked(s, t0 + hour - 1))
        assertFalse(SourceHealthPolicy.isBlocked(s, t0 + hour))
        // ⚠️ 不消耗升级档位:超时是最弱的证据,一次网络抖动就能造出一串;
        // 让它累计到第 3 次就会变成**永久封禁**(要用户手动解除)—— 不可接受
        assertEquals(0, s.banCount)
        assertFalse(s.manualLocked)
    }

    @Test
    fun answeredResetsTimeoutStreak() {
        // 源答话了(哪怕答"我没有")⇒ 它是活的,连续超时必须重新数
        var s = state(fails = 1, titles = listOf("电影A"))
        assertEquals(1, s.timeoutStreak)
        s = SourceHealthPolicy.recordAnswered(s)
        assertEquals(0, s.timeoutStreak)
        s = SourceHealthPolicy.recordFail(
            s, SourceFailKind.SEARCH_TIMEOUT, SourceHealthPolicy.contentKey("电影B"), t0 + 1,
        )
        assertEquals(1, s.timeoutStreak)
        assertFalse(SourceHealthPolicy.isBlocked(s, t0 + 1))
    }

    @Test
    fun answeredIsNoOpWhenNothingToReset() {
        // 无变化时必须返回**原对象**:调用方据此跳过落盘(一次搜索会成功几百个源)
        val s = SourceHealthState()
        assertSame(s, SourceHealthPolicy.recordAnswered(s))
    }

    @Test
    fun httpFailureNeitherIncrementsNorResetsStreak() {
        // SEARCH_FAILED 不是"源答话了"(没资格把连续超时洗白),也不是超时(不凑这个数)。
        // 全文用同一个片名 ⇒ distinct=1,确保不会误触规则 1,只验证规则 2 的加减口径
        val a = SourceHealthPolicy.contentKey("电影A")
        var s = state(fails = 1, titles = listOf("电影A"))
        s = SourceHealthPolicy.recordFail(s, SourceFailKind.SEARCH_FAILED, a, t0 + 1)
        assertEquals(1, s.timeoutStreak)
        s = SourceHealthPolicy.recordFail(s, SourceFailKind.SEARCH_TIMEOUT, a, t0 + 2)
        assertEquals(2, s.timeoutStreak)
        assertTrue(SourceHealthPolicy.isBlocked(s, t0 + 2))
        assertEquals(0, s.banCount)
    }

    @Test
    fun timeoutBanExpiryRequiresFreshStreak() {
        var s = state(fails = 2, titles = listOf("电影A"))
        assertTrue(SourceHealthPolicy.isBlocked(s, t0))
        val t1 = t0 + hour
        s = SourceHealthPolicy.normalize(s, t1)
        assertFalse(SourceHealthPolicy.isBlocked(s, t1))
        // 封禁时计数已归零 ⇒ 解封后只超时一次不该立刻又封
        assertEquals(0, s.timeoutStreak)
        s = SourceHealthPolicy.recordFail(
            s, SourceFailKind.SEARCH_TIMEOUT, SourceHealthPolicy.contentKey("电影B"), t1,
        )
        assertFalse(SourceHealthPolicy.isBlocked(s, t1))
        assertEquals(1, s.timeoutStreak)
    }

    @Test
    fun normalizeDropsStreakOnceEvidenceAgesOut() {
        var s = state(fails = 1, titles = listOf("电影A"))
        assertEquals(1, s.timeoutStreak)
        // 25 小时后那条超时已出窗口 ⇒ 计数必须一起归零,否则之后**单次**超时就能凑够 2 次
        // 触发封禁(那等于把一个"上个纪元"的计数当成了连续)
        val later = t0 + 25 * hour
        s = SourceHealthPolicy.normalize(s, later)
        assertEquals(0, s.fails.size)
        assertEquals(0, s.timeoutStreak)
        val one = SourceHealthPolicy.recordFail(
            s, SourceFailKind.SEARCH_TIMEOUT, SourceHealthPolicy.contentKey("电影B"), later,
        )
        assertFalse(SourceHealthPolicy.isBlocked(one, later))
    }

    @Test
    fun upgradingBanAlsoResetsStreak() {
        // 走升级档位封禁时,连续超时计数一并归零(两条规则共用同一条"封禁即清证据"的原则)
        val s = state(fails = 3, titles = listOf("电影A", "电影B"))
        assertEquals(1, s.banCount)
        assertEquals(0, s.timeoutStreak)
    }

    @Test
    fun manualUnblockClearsStreak() {
        val s = state(fails = 2, titles = listOf("电影A"))
        assertEquals(0, SourceHealthPolicy.unblock(s).timeoutStreak)
    }

    // ---------- 降权判据(只排序、不屏蔽,2026-10-09) ----------

    @Test
    fun shouldDefer_onlyWithinWindowAfterTimeout() {
        val s = state(fails = 1, titles = listOf("电影A"))
        assertTrue(SourceHealthPolicy.shouldDefer(s, t0))
        assertTrue(SourceHealthPolicy.shouldDefer(s, t0 + SourceHealthPolicy.DEFER_MS - 1))
        // 过了窗口就恢复正常排序:一次网络抖动不该让源"一整天排最后"
        assertFalse(SourceHealthPolicy.shouldDefer(s, t0 + SourceHealthPolicy.DEFER_MS))
    }

    @Test
    fun shouldDefer_isMutuallyExclusiveWithBlocked() {
        // 已屏蔽的源走**过滤**那条路(根本不搜),不该同时被标成"降权" —— 两处口径必须互斥,
        // 否则日志里"降权 N 个"会把已经不在池里的源也数进去
        val blocked = state(fails = 2, titles = listOf("电影A"))
        assertTrue(SourceHealthPolicy.isBlocked(blocked, t0))
        assertFalse(SourceHealthPolicy.shouldDefer(blocked, t0))
    }

    @Test
    fun shouldDefer_ignoresNonTimeoutEvidence() {
        // 起播失败是**另一个阶段**的事:不该让它在搜索里降权。
        // (首页源同理不受降权影响,那条在 SearchBatchPolicy 侧)
        var s = SourceHealthState()
        s = SourceHealthPolicy.recordFail(s, SourceFailKind.PLAY_FAILED, SourceHealthPolicy.contentKey("电影A"), t0)
        assertFalse(SourceHealthPolicy.shouldDefer(s, t0))
        var f = SourceHealthState()
        f = SourceHealthPolicy.recordFail(f, SourceFailKind.SEARCH_FAILED, SourceHealthPolicy.contentKey("电影A"), t0)
        assertFalse(SourceHealthPolicy.shouldDefer(f, t0))
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
