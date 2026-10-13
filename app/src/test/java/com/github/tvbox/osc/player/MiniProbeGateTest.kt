package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 准入过滤单测:锁住"明知探不到就一次请求都不发"的口径。
 *
 * <p>这是 P1 的**成本闸门** —— 真机实测源超时率 75~83%,放行一个不可探的类型
 * 就是白白增加一次大概率失败的请求。所以每条 Skip 分支都必须有测试钉住。
 */
class MiniProbeGateTest {

    private fun decide(
        type: Int,
        index: Boolean = false,
        used: Boolean = false,
        blocked: Boolean = false,
        warm: Boolean = false,
        recovery: Boolean = false,
    ) = MiniProbeGate.decide(type, index, used, blocked, warm, recovery)

    @Test
    fun type1_json_allowed() {
        assertEquals(MiniProbeGate.Decision.Allow, decide(type = 1))
    }

    @Test
    fun type0_xml_allowed() {
        assertEquals(MiniProbeGate.Decision.Allow, decide(type = 0))
    }

    @Test
    fun type4_warmExtend_allowed() {
        assertEquals(MiniProbeGate.Decision.Allow, decide(type = 4, warm = true))
    }

    @Test
    fun type4_coldExtend_skipped() {
        // extend 冷 ⇒ 解析本身要一次网络往返,不值得
        assertTrue(decide(type = 4, warm = false) is MiniProbeGate.Decision.Skip)
        assertEquals("xhint-skip extend-cold", decide(type = 4, warm = false).toString())
    }

    @Test
    fun type3_spider_skipped() {
        // 爬虫要抢 SPIDER_POOL(3 线程),会拖慢用户正在看的取数
        assertEquals("xhint-skip spider", decide(type = 3).toString())
    }

    @Test
    fun indexOnly_skipped() {
        assertEquals("xhint-skip index-only", decide(type = 1, index = true).toString())
    }

    @Test
    fun alreadyUsed_skipped() {
        assertEquals("xhint-skip used", decide(type = 1, used = true).toString())
    }

    @Test
    fun healthBlocked_skipped() {
        assertEquals("xhint-skip blocked", decide(type = 1, blocked = true).toString())
    }

    @Test
    fun recoveryProbe_liftsHealthBlocked() {
        // L2 探活候选:被封禁也放行,给"被判死的源"一次自证机会(P0-3)
        assertEquals(MiniProbeGate.Decision.Allow, decide(type = 1, blocked = true, recovery = true))
    }

    @Test
    fun recoveryProbe_doesNotBypassOtherGates() {
        // 豁免只针对 healthBlocked:type3 spider 仍跳过
        assertEquals("xhint-skip spider", decide(type = 3, blocked = true, recovery = true).toString())
        // 已用过仍跳过
        assertEquals("xhint-skip used", decide(type = 1, used = true, blocked = true, recovery = true).toString())
    }

    @Test
    fun unknownType_skipped() {
        assertEquals("xhint-skip type9", decide(type = 9).toString())
    }
}
