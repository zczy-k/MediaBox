package com.github.tvbox.osc.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 起播分段的归因规则。判错的后果不是"慢一点",而是**把账记到错误的头上**:
 * 站点不答话、我们自己在拉播放列表,都被算成"这条线路失败一次",
 * 一条本来好好的线会因为"这个站今天很慢"被越排越后。
 */
class PlayStageTest {

    @Test
    fun `爬虫没答话时排在最前,后面的标记一律不看`() {
        // 超时重来一轮时残留的旧标记不能把状态"抬高"成已经交了地址
        assertEquals(
            PlayStage.SPIDER_WAIT,
            PlayStages.of(true, true, true),
        )
        assertEquals(PlayStage.SPIDER_WAIT, PlayStages.of(true, false, false))
    }

    @Test
    fun `地址定稿就是已交给内核,即使本轮走过净化`() {
        // 净化完成的回调正是 goPlayUrl ⇒ kernelGotUrl 为真说明净化已经回来了
        assertEquals(PlayStage.PLAYER_START, PlayStages.of(false, true, true))
        assertEquals(PlayStage.PLAYER_START, PlayStages.of(false, false, true))
    }

    @Test
    fun `净化在拉它自己的播放列表时,不算线路的问题`() {
        assertEquals(PlayStage.PURIFY_IN_FLIGHT, PlayStages.of(false, true, false))
        assertFalse(PlayStage.PURIFY_IN_FLIGHT.blamesTheLine())
    }

    @Test
    fun `爬虫答了但地址还没定稿属于本地链路`() {
        assertEquals(PlayStage.LOCAL_HANDOFF, PlayStages.of(false, false, false))
        assertFalse(PlayStage.LOCAL_HANDOFF.blamesTheLine())
    }

    /** 只有"地址确实交给内核了"才谈得上"这个流不行" —— 这是本次分段唯一想守住的边界 */
    @Test
    fun `只有 player-start 一段有资格把账记到线路头上`() {
        assertTrue(PlayStage.PLAYER_START.blamesTheLine())
        assertFalse(PlayStage.SPIDER_WAIT.blamesTheLine())
        assertFalse(PlayStage.LOCAL_HANDOFF.blamesTheLine())
        assertFalse(PlayStage.PURIFY_IN_FLIGHT.blamesTheLine())
    }

    @Test
    fun `日志短名稳定,便于在诊断包里直接搜`() {
        assertEquals("spider-wait", PlayStage.SPIDER_WAIT.tag())
        assertEquals("local-handoff", PlayStage.LOCAL_HANDOFF.tag())
        assertEquals("purify-in-flight", PlayStage.PURIFY_IN_FLIGHT.tag())
        assertEquals("player-start", PlayStage.PLAYER_START.tag())
    }
}
