package com.github.tvbox.osc.player.effect

import com.github.tvbox.osc.player.effect.anime4k.Anime4kEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下发列表形态:media3 不消费 isNoOp(列表里的 effect 一定会被实例化) ⇒ 超分关闭时必须
 * 从列表里摘掉,否则会以默认档重建整条超分链(2026-10-01 真机 bug)。
 */
class PictureEffectsEffectListTest {

    @Test
    fun disabledAnime4k_isNotHandedToTheKernel() {
        val effects = PictureEffects.effectsFor(anime4kEnabled = false)
        assertEquals(2, effects.size)
        assertFalse(effects.any { it is Anime4kEffect })
    }

    @Test
    fun enabledAnime4k_isHandedToTheKernel() {
        val effects = PictureEffects.effectsFor(anime4kEnabled = true)
        assertEquals(3, effects.size)
        assertTrue(effects.any { it is Anime4kEffect })
    }

    @Test
    fun bothLists_shareTheSameEffectInstances() {
        val without = PictureEffects.effectsFor(anime4kEnabled = false)
        val with = PictureEffects.effectsFor(anime4kEnabled = true)
        assertTrue(with.containsAll(without))
    }
}
