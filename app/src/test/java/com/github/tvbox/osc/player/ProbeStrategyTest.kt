package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能探测的阶梯与冷却。判错的两种后果都坏:
 * 该重试时不重试 = 整个源的画质优选长期失效(真机 18 次 403 就是这么来的);
 * 不该重试时乱重试 = 探测和播放抢带宽,用户感到"看片变卡"。
 */
class ProbeStrategyTest {

    // ---------- 只有 403 值得换身份 ----------

    @Test
    fun `403 且源站没配 UA ⇒ 下一档补浏览器 UA`() {
        assertEquals(
            ProbeTactic.BROWSER_UA,
            ProbeStrategy.nextTactic(ProbeTactic.SOURCE_HEADERS, ProbeOutcome.FORBIDDEN, false, false),
        )
    }

    /** 源站本来就有 UA 时再"补 UA"是同一个请求打两遍:白烧一次带宽,还让人以为换过身份 */
    @Test
    fun `源站已配的头直接跳过,不换等于没换的那一档不进阶梯`() {
        assertEquals(
            ProbeTactic.REFERER,
            ProbeStrategy.nextTactic(ProbeTactic.SOURCE_HEADERS, ProbeOutcome.FORBIDDEN, true, false),
        )
        // UA 与 Referer 都有 ⇒ 没有可换的身份了
        assertNull(
            ProbeStrategy.nextTactic(ProbeTactic.SOURCE_HEADERS, ProbeOutcome.FORBIDDEN, true, true),
        )
    }

    @Test
    fun `阶梯最长三档,补完 UA 再补 Referer`() {
        var tactic: ProbeTactic? = ProbeTactic.SOURCE_HEADERS
        var steps = 0
        while (tactic != null && tactic != ProbeTactic.UA_PLUS_REFERER && steps < ProbeTactic.entries.size) {
            tactic = ProbeStrategy.nextTactic(tactic, ProbeOutcome.FORBIDDEN, false, false)
            steps++
        }
        assertEquals(ProbeTactic.UA_PLUS_REFERER, tactic)
        // 身份换尽了还是 403 ⇒ 停,交给冷却而不是继续磨
        assertNull(ProbeStrategy.nextTactic(ProbeTactic.UA_PLUS_REFERER, ProbeOutcome.FORBIDDEN, false, false))
    }

    @Test
    fun `非 403 一律不重试`() {
        listOf(
            ProbeOutcome.NOT_FOUND,
            ProbeOutcome.SERVER,
            ProbeOutcome.NETWORK,
            ProbeOutcome.CONTENT_UNUSABLE,
            ProbeOutcome.NO_SIZE,
            ProbeOutcome.OK,
        ).forEach { outcome ->
            assertEquals(
                "outcome=$outcome 不该重试",
                null,
                ProbeStrategy.nextTactic(ProbeTactic.SOURCE_HEADERS, outcome, false, false),
            )
        }
    }

    // ---------- 冷却分性质 ----------

    /** 鉴权类(换完身份仍被拒)才长期冷却;网关/网络只短冷却 */
    @Test
    fun `冷却按失败性质分档`() {
        assertEquals(
            ProbeStrategy.COOL_DOWN_FORBIDDEN_MS,
            ProbeStrategy.coolDownMs(ProbeOutcome.FORBIDDEN, outOfTactics = true),
        )
        assertEquals(0L, ProbeStrategy.coolDownMs(ProbeOutcome.FORBIDDEN, outOfTactics = false))
        assertEquals(
            ProbeStrategy.COOL_DOWN_TRANSIENT_MS,
            ProbeStrategy.coolDownMs(ProbeOutcome.SERVER, outOfTactics = false),
        )
        assertEquals(
            ProbeStrategy.COOL_DOWN_TRANSIENT_MS,
            ProbeStrategy.coolDownMs(ProbeOutcome.NETWORK, outOfTactics = false),
        )
    }

    /**
     * 404 / 内容不可用 / 没有分辨率 ⇒ **不给站点上冷却**。
     * 它们说的是"这一条地址",不是"这个站不能测"——贴错标签的整个站的画质优选就废了。
     */
    @Test
    fun `地址级失败不给站点贴标签`() {
        listOf(
            ProbeOutcome.NOT_FOUND,
            ProbeOutcome.CONTENT_UNUSABLE,
            ProbeOutcome.NO_SIZE,
            ProbeOutcome.OK,
        ).forEach {
            assertEquals("outcome=$it 不该冷却站点", 0L, ProbeStrategy.coolDownMs(it, outOfTactics = true))
        }
    }

    // ---------- HTTP 状态码分类 ----------

    @Test
    fun `状态码分类覆盖常见几种拒绝方式`() {
        assertEquals(ProbeOutcome.FORBIDDEN, ProbeStrategy.classifyHttp(403))
        assertEquals(ProbeOutcome.FORBIDDEN, ProbeStrategy.classifyHttp(401))
        assertEquals(ProbeOutcome.FORBIDDEN, ProbeStrategy.classifyHttp(407))
        assertEquals(ProbeOutcome.NOT_FOUND, ProbeStrategy.classifyHttp(404))
        assertEquals(ProbeOutcome.NOT_FOUND, ProbeStrategy.classifyHttp(410))
        assertEquals(ProbeOutcome.SERVER, ProbeStrategy.classifyHttp(502))
        assertEquals(ProbeOutcome.SERVER, ProbeStrategy.classifyHttp(504))
        assertEquals(ProbeOutcome.CONTENT_UNUSABLE, ProbeStrategy.classifyHttp(418))
    }

    /** 上次靠哪档成功,下次就从那档开始(省掉注定失败的往返) */
    @Test
    fun `记住成功的档位`() {
        assertEquals(
            ProbeTactic.SOURCE_HEADERS,
            ProbeStrategy.firstTactic(null),
        )
        assertEquals(
            ProbeTactic.REFERER,
            ProbeStrategy.firstTactic(ProbeTactic.REFERER),
        )
    }

    @Test
    fun `重试上限不超过阶梯长度`() {
        assertTrue(ProbeStrategy.MAX_ATTEMPTS <= ProbeTactic.entries.size)
    }
}

/** 站点记忆是纯进程内提示缓存:必须能记起、能过期、满了能重来,且不崩并发访问 */
class ProbeHostMemoryTest {

    @Test
    fun `记不住没记过的站`() {
        ProbeHostMemory.clearForTest()
        assertNull(ProbeHostMemory.lastSuccessful("v5.nope.com"))
        assertFalse(ProbeHostMemory.isCooling("v5.nope.com", 1_000L))
    }

    @Test
    fun `成功档位与冷却各自独立`() {
        ProbeHostMemory.clearForTest()
        ProbeHostMemory.rememberSuccess("a.com", ProbeTactic.BROWSER_UA)
        assertEquals(ProbeTactic.BROWSER_UA, ProbeHostMemory.lastSuccessful("a.com"))
        // 测通过 ≠ 之后不能冷却(站点会改策略),也 ≠ 冷却过就忘了档位
        ProbeHostMemory.coolDown("a.com", 60_000L, 1_000L)
        assertTrue(ProbeHostMemory.isCooling("a.com", 2_000L))
        assertFalse(ProbeHostMemory.isCooling("a.com", 61_000L))
        assertEquals(ProbeTactic.BROWSER_UA, ProbeHostMemory.lastSuccessful("a.com"))
    }

    /** 冷却只往长了改:并发的两条线路同时失败时,短的不能把长的抹掉 */
    @Test
    fun `冷却时间只延长不缩短`() {
        ProbeHostMemory.clearForTest()
        ProbeHostMemory.coolDown("b.com", ProbeStrategy.COOL_DOWN_FORBIDDEN_MS, 0L)
        ProbeHostMemory.coolDown("b.com", ProbeStrategy.COOL_DOWN_TRANSIENT_MS, 1_000L)
        // 已经过了短冷却的时刻,仍在长冷却里 ⇒ 说明短的那次没把长的抹掉
        assertTrue(ProbeHostMemory.isCooling("b.com", ProbeStrategy.COOL_DOWN_TRANSIENT_MS + 1_000L))
        assertFalse(ProbeHostMemory.isCooling("b.com", ProbeStrategy.COOL_DOWN_FORBIDDEN_MS))
    }

    @Test
    fun `空主机名一律忽略`() {
        ProbeHostMemory.clearForTest()
        ProbeHostMemory.rememberSuccess(null, ProbeTactic.REFERER)
        ProbeHostMemory.coolDown(null, 10L, 0L)
        ProbeHostMemory.coolDown("c.com", 0L, 0L)
        assertEquals(0, ProbeHostMemory.trackedHosts())
    }
}
