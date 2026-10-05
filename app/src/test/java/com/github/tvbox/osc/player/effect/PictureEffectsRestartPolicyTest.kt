package com.github.tvbox.osc.player.effect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「改参数后要不要重播本集」真值表:开=有效果但本集没挂链;关=参数回恒等、本集挂着链且预置是「原始」。
 * 判反的后果 = 开了没效果 / 该直通时不直通(面板会立刻 replay,这里是唯一守卫)。
 */
class PictureEffectsRestartPolicyTest {

    @Test
    fun enableNeedsRestartOnlyWhenEpisodeHasNoPipe() {
        assertTrue(PictureEffects.restartNeeded(wantEffects = true, opened = false, presetOriginal = false))
        assertTrue(PictureEffects.restartNeeded(wantEffects = true, opened = false, presetOriginal = true))
        assertFalse(PictureEffects.restartNeeded(wantEffects = true, opened = true, presetOriginal = false))
    }

    @Test
    fun disableNeedsRestartOnlyFromOriginalPresetWithOpenPipe() {
        assertTrue(PictureEffects.restartNeeded(wantEffects = false, opened = true, presetOriginal = true))
        // 滑条碰巧拖回中性(预置仍是「自定义」)不算关闭
        assertFalse(PictureEffects.restartNeeded(wantEffects = false, opened = true, presetOriginal = false))
        assertFalse(PictureEffects.restartNeeded(wantEffects = false, opened = false, presetOriginal = true))
    }

    @Test
    fun idleStateNeverRestarts() {
        assertFalse(PictureEffects.restartNeeded(wantEffects = false, opened = false, presetOriginal = false))
    }
}
