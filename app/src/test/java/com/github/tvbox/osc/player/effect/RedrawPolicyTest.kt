package com.github.tvbox.osc.player.effect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RedrawPolicy 单测:锁住"只有暂停态 + 能重绘时才补一帧"的真值表 */
class RedrawPolicyTest {

    @Test
    fun geometry_redrawsOnlyWhenSizeChangedWhilePaused() {
        assertTrue(RedrawPolicy.shouldRedrawOnGeometry(true, false, true))
        // 播放中:下一帧自然按新几何合成;尺寸没变:不该因重发信令触发;不能重绘:发了会抛
        assertFalse(RedrawPolicy.shouldRedrawOnGeometry(true, true, true))
        assertFalse(RedrawPolicy.shouldRedrawOnGeometry(false, false, true))
        assertFalse(RedrawPolicy.shouldRedrawOnGeometry(true, false, false))
    }

    @Test
    fun params_redrawsOnlyWhenPaused() {
        assertTrue(RedrawPolicy.shouldRedrawOnParams(false, true))
        assertFalse(RedrawPolicy.shouldRedrawOnParams(true, true))
        assertFalse(RedrawPolicy.shouldRedrawOnParams(false, false))
    }
}
