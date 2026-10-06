package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情加载看门狗时限单测。
 *
 * <p>锁两条口径:①下界——缺省源(8s)必须落在 [MIN_TIMEOUT_MS],不能被掐成"比站点自己的限时还短";
 * ②上界——站点声明 60s 时也要被夹到 [MAX_TIMEOUT_MS],否则用户会被按在转圈页上一分多钟。
 */
class DetailLoadWatchdogTest {

    @Test
    fun defaultSourceUsesFloorTimeout() {
        // 8s * 1000 + 12s = 20s,正好等于下界
        assertEquals(DetailLoadWatchdog.MIN_TIMEOUT_MS, DetailLoadWatchdog.timeoutMs(8))
        assertEquals(20_000L, DetailLoadWatchdog.timeoutMs(8))
    }

    @Test
    fun fastSourceIsNotCutBelowFloor() {
        // 5s 源:5s + 12s = 17s,低于下界 ⇒ 抬到 20s,不能比站点自己的限时还短
        assertEquals(DetailLoadWatchdog.MIN_TIMEOUT_MS, DetailLoadWatchdog.timeoutMs(5))
        assertTrue(DetailLoadWatchdog.timeoutMs(5) > 5_000L)
    }

    @Test
    fun slowSourceIsCappedAtCeiling() {
        // 60s 源:60s + 12s = 72s,超上界 ⇒ 夹到 45s
        assertEquals(DetailLoadWatchdog.MAX_TIMEOUT_MS, DetailLoadWatchdog.timeoutMs(60))
    }

    @Test
    fun nonPositiveTimeoutFallsBackToDefaultSource() {
        assertEquals(DetailLoadWatchdog.timeoutMs(8), DetailLoadWatchdog.timeoutMs(0))
        assertEquals(DetailLoadWatchdog.timeoutMs(8), DetailLoadWatchdog.timeoutMs(-1))
    }

    @Test
    fun timeoutNeverDecreasesAsSourceTimeoutGrows() {
        var previous = 0L
        for (seconds in 0..70) {
            val current = DetailLoadWatchdog.timeoutMs(seconds)
            assertTrue("seconds=$seconds 应单调不减", current >= previous)
            assertTrue(current in DetailLoadWatchdog.MIN_TIMEOUT_MS..DetailLoadWatchdog.MAX_TIMEOUT_MS)
            previous = current
        }
    }
}
