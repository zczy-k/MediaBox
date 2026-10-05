package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DetailPlaybackCommands.fullScreenState` 的八格真值表:两处"看起来该简化"的地方是既有语义
 * (见该函数 KDoc),改动判据前先看这里。
 */
class DetailPlaybackCommandsTest {

    private fun decide(requested: Boolean, landscape: Boolean, portraitVideo: Boolean = false) =
        DetailPlaybackCommands.fullScreenState(
            requested,
            DetailPlaybackFacts(landscape = landscape, portraitVideo = portraitVideo),
        )

    @Test
    fun enterFromPortraitWithLandscapeVideoRotates() {
        val (full, rotating) = decide(requested = true, landscape = false)
        assertTrue(full)
        assertTrue(rotating)
    }

    @Test
    fun enterFromPortraitWithPortraitVideoDoesNotRotate() {
        // 竖屏→竖屏不触发 onConfigurationChanged,置位会永不复位
        val (full, rotating) = decide(requested = true, landscape = false, portraitVideo = true)
        assertTrue(full)
        assertFalse(rotating)
    }

    @Test
    fun enterFromLandscapeWithLandscapeVideoDoesNotRotate() {
        // 大屏点全屏时系统可能不旋转
        val (full, rotating) = decide(requested = true, landscape = true)
        assertTrue(full)
        assertFalse(rotating)
    }

    @Test
    fun enterFromLandscapeWithPortraitVideoRotatesBack() {
        val (full, rotating) = decide(requested = true, landscape = true, portraitVideo = true)
        assertTrue(full)
        assertTrue(rotating)
    }

    @Test
    fun exitFromLandscapeKeepsFullBoxUntilRotationLands() {
        // 不要改成"退全屏恒不旋转":横屏按返回要保持全屏样直到旋转落地
        val (full, rotating) = decide(requested = false, landscape = true, portraitVideo = true)
        assertFalse(full)
        assertTrue(rotating)
    }

    @Test
    fun exitFromLandscapeWithLandscapeVideoAlsoKeepsFullBox() {
        val (full, rotating) = decide(requested = false, landscape = true)
        assertFalse(full)
        assertTrue(rotating)
    }

    @Test
    fun exitFromPortraitWithLandscapeVideoDoesNotRotate() {
        val (full, rotating) = decide(requested = false, landscape = false)
        assertFalse(full)
        assertFalse(rotating)
    }

    @Test
    fun exitFromPortraitWithPortraitVideoDoesNotRotate() {
        val (full, rotating) = decide(requested = false, landscape = false, portraitVideo = true)
        assertFalse(full)
        assertFalse(rotating)
    }
}
