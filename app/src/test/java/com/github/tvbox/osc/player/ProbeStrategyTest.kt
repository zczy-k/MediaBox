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

    /**
     * 阶梯顺序:第一版先补 UA,v1.1.36 日志证明补了 UA 仍然 403,于是改成先试 Referer
     * (防盗链更常校验这一项)。⚠️ PC 上"不带任何头反倒 200"那次**不算证据** ——
     * 它同时换了 UA、TLS 指纹与出口 IP,三个变量分不开,谁起作用要等 `tactic=`/`deny=` 的日志定性。
     */
    @Test
    fun `403 先补 Referer,再连 UA 一起补`() {
        assertEquals(
            ProbeTactic.REFERER,
            ProbeStrategy.nextTactic(ProbeTactic.SOURCE_HEADERS, ProbeOutcome.FORBIDDEN, false, false),
        )
        assertEquals(
            ProbeTactic.REFERER_PLUS_UA,
            ProbeStrategy.nextTactic(ProbeTactic.REFERER, ProbeOutcome.FORBIDDEN, false, false),
        )
    }

    /** 源站已经配了那一档 ⇒ 不许拿同一个请求再打一遍 */
    @Test
    fun `换了等于没换的那一档不进阶梯`() {
        // Referer 已配 ⇒ 补 Referer 无意义;而下一档还要补 UA,这里 alreadyHasUa=false 仍可救
        assertEquals(
            ProbeTactic.REFERER_PLUS_UA,
            ProbeStrategy.nextTactic(ProbeTactic.SOURCE_HEADERS, ProbeOutcome.FORBIDDEN, false, true),
        )
        // 两个头源站都配了还被拒 ⇒ 不是缺头,别磨
        assertNull(
            ProbeStrategy.nextTactic(ProbeTactic.REFERER_PLUS_UA, ProbeOutcome.FORBIDDEN, true, true),
        )
    }

    @Test
    fun `阶梯两档用尽就停`() {
        var tactic: ProbeTactic? = ProbeTactic.SOURCE_HEADERS
        var steps = 0
        while (tactic != null && tactic != ProbeTactic.REFERER_PLUS_UA &&
            steps < ProbeTactic.entries.size
        ) {
            tactic = ProbeStrategy.nextTactic(tactic, ProbeOutcome.FORBIDDEN, false, false)
            steps++
        }
        assertEquals(ProbeTactic.REFERER_PLUS_UA, tactic)
        assertEquals(2, steps)
        assertNull(
            ProbeStrategy.nextTactic(ProbeTactic.REFERER_PLUS_UA, ProbeOutcome.FORBIDDEN, false, false),
        )
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

    /**
     * 鉴权类要**把身份档位真的用尽**才冷却。
     * 真机踩过:只试过 UA 那一档就把站点冷却 30 分钟,结果同一条能测的线被排除在探测之外。
     */
    @Test
    fun `冷却按失败性质分档,且鉴权类要档位用尽`() {
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

    /** 404 / 内容不可用 / 没有分辨率都不该给站点贴标签:那是这一条地址的事 */
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

    /** 冷却时长本身:鉴权类压到 10 分钟(真机误伤的教训),不能长到"换一部片还不给机会" */
    @Test
    fun `冷却时长在合理量级`() {
        assertEquals(10L * 60 * 1000, ProbeStrategy.COOL_DOWN_FORBIDDEN_MS)
        assertEquals(3L * 60 * 1000, ProbeStrategy.COOL_DOWN_TRANSIENT_MS)
    }

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
        assertEquals(ProbeTactic.SOURCE_HEADERS, ProbeStrategy.firstTactic(null))
        assertEquals(ProbeTactic.REFERER, ProbeStrategy.firstTactic(ProbeTactic.REFERER))
    }

    @Test
    fun `重试上限不超过阶梯长度`() {
        assertTrue(ProbeStrategy.MAX_ATTEMPTS <= ProbeTactic.entries.size)
    }

    /**
     * `deny=` 的第一版真机打回来是 `<!DOCTYPE html>` —— 这些站回的是 HTML 拒绝页,
     * 第一行永远是 doctype,等于什么也没说。判据见 [ProbeStrategy.denySummary]。
     */
    @Test
    fun `HTML 拒绝页要抠出真正的那句话`() {
        val page = "<!DOCTYPE html><html><head><title>403</title></head><body>" +
            "<script>var x=1;</script><h1>Access Denied</h1>" +
            "<p>Referer check failed, request from 1.2.3.4 is blocked</p></body></html>"
        val hint = ProbeStrategy.denySummary(page, null)
        assertFalse("不能再是 doctype", hint.startsWith("<"))
        assertTrue("要带上拒绝原话: $hint", hint.contains("Access Denied"))
        assertTrue("定位到原因句: $hint", hint.contains("Referer"))
    }

    @Test
    fun `纯文本体直接用而空体才退回头里的提示`() {
        assertEquals("token expired", ProbeStrategy.denySummary("token expired", "X-Tengine-Error=bx"))
        assertEquals(
            "X-Tengine-Error=bx",
            ProbeStrategy.denySummary("<!doctype html><html><body></body></html>", "X-Tengine-Error=bx"),
        )
        assertEquals("什么都没给时不留空行", "-", ProbeStrategy.denySummary("", ""))
    }

    @Test
    fun `中文拒绝页也能定位`() {
        val page = "<html><body><div>导航 首页 频道</div><span>您访问的地址已被禁止,请勿频繁请求</span></body></html>"
        val hint = ProbeStrategy.denySummary(page, null)
        assertTrue("该抓到" + "禁止: $hint", hint.contains("禁止"))
    }
}

/** 站点记忆是纯进程内提示缓存:要能记起、能过期、并发访问不崩,还要能挡住同一条地址的重复探测 */
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
        ProbeHostMemory.rememberSuccess("a.com", ProbeTactic.REFERER)
        assertEquals(ProbeTactic.REFERER, ProbeHostMemory.lastSuccessful("a.com"))
        ProbeHostMemory.coolDown("a.com", 60_000L, 1_000L)
        assertTrue(ProbeHostMemory.isCooling("a.com", 2_000L))
        assertFalse(ProbeHostMemory.isCooling("a.com", 61_000L))
        assertEquals(ProbeTactic.REFERER, ProbeHostMemory.lastSuccessful("a.com"))
    }

    /** 冷却只往长了改:并发的两条线路同时失败时,短的不能把长的抹掉 */
    @Test
    fun `冷却时间只延长不缩短`() {
        ProbeHostMemory.clearForTest()
        ProbeHostMemory.coolDown("b.com", ProbeStrategy.COOL_DOWN_FORBIDDEN_MS, 0L)
        ProbeHostMemory.coolDown("b.com", ProbeStrategy.COOL_DOWN_TRANSIENT_MS, 1_000L)
        assertTrue(ProbeHostMemory.isCooling("b.com", ProbeStrategy.COOL_DOWN_TRANSIENT_MS + 1_000L))
        assertFalse(ProbeHostMemory.isCooling("b.com", ProbeStrategy.COOL_DOWN_FORBIDDEN_MS))
    }

    /** 同一条地址被两条路径同时探 ⇒ 第二条必须让路(探测和播放抢带宽) */
    @Test
    fun `同一地址的并发探测只放行一个`() {
        ProbeHostMemory.clearForTest()
        val url = "https://cdn.example.com/a/index.m3u8"
        assertTrue(ProbeHostMemory.beginProbe(url))
        assertFalse(ProbeHostMemory.beginProbe(url))
        ProbeHostMemory.endProbe(url)
        assertTrue(ProbeHostMemory.beginProbe(url))
    }

    /** 去重表满了要放行:宁可多探一次,也不能挡正常探测 */
    @Test
    fun `去重表满时放行`() {
        ProbeHostMemory.clearForTest()
        for (i in 0..40) {
            assertTrue("第 $i 条应被放行", ProbeHostMemory.beginProbe("https://h$i.example.com/x.m3u8"))
        }
        ProbeHostMemory.clearForTest()
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
