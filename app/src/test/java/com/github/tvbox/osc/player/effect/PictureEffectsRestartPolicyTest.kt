package com.github.tvbox.osc.player.effect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「改参数后要不要重播本集」真值表:**只有一种情况要重播** —— 想要效果,而本集没挂效果链。
 *
 * 反向(全关掉、链还挂着)故意不重播:摘链省下的只是一次全屏 GL 拷贝,代价却是从片头重播;
 * 关掉时改走链内的纯拷贝分支(每帧现读),用户点完立刻看到画面回去。
 * 判反的后果 = 开了没效果 / 关一次增强就把整集重播一遍。
 */
class PictureEffectsRestartPolicyTest {

    @Test
    fun enableNeedsRestartOnlyWhenEpisodeHasNoPipe() {
        assertTrue(PictureEffects.restartNeeded(wantEffects = true, opened = false))
        assertFalse(PictureEffects.restartNeeded(wantEffects = true, opened = true))
    }

    @Test
    fun disableNeverRestarts() {
        // 链挂着但参数已回恒等:不摘链、不重播,下次起播自然不挂
        assertFalse(PictureEffects.restartNeeded(wantEffects = false, opened = true))
        assertFalse(PictureEffects.restartNeeded(wantEffects = false, opened = false))
    }
}
