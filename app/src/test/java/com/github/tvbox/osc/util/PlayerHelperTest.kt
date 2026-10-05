package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网速文本口径:入参是**字节/秒**、按 **1024** 进制,单位 B/s | KB/s | MB/s。
 * 标成比特率(Mb/s)或换成 1000 进制都会与播放器里其它显示差 8 倍/4.8%,故锁在用例里。
 */
class PlayerHelperTest {

    @Test
    fun displaySpeed_usesByteUnitsWithBinaryBase() {
        assertEquals("512B/s", PlayerHelper.getDisplaySpeed(512L, true))
        assertEquals("900KB/s", PlayerHelper.getDisplaySpeed(900L * 1024, true))
        assertTrue(PlayerHelper.getDisplaySpeed(3L * 1024 * 1024, true).endsWith("MB/s"))
    }

    @Test
    fun displaySpeed_zeroTextFollowsShowFlag() {
        assertEquals("0B/s", PlayerHelper.getDisplaySpeed(0L, true))
        assertEquals("", PlayerHelper.getDisplaySpeed(0L, false))
    }

    /**
     * 解码"已生效"判据:cfg 值只认 "软解码"(缺键/空串按硬解),与已下发静态位一致才算生效。
     * 判反 = 改了解码却不重建内核(旧内核继续用旧解码器),或每次复进都白重建一次。
     */
    @Test
    fun exoDecodeApplied_onlySoftValueMatchesAppliedFlag() {
        assertTrue(PlayerHelper.isExoDecodeApplied("软解码", true))
        assertTrue(PlayerHelper.isExoDecodeApplied("硬解码", false))
        assertTrue(PlayerHelper.isExoDecodeApplied("", false))
        assertTrue(PlayerHelper.isExoDecodeApplied(null, false))
        assertFalse(PlayerHelper.isExoDecodeApplied("软解码", false))
        assertFalse(PlayerHelper.isExoDecodeApplied("硬解码", true))
        assertFalse(PlayerHelper.isExoDecodeApplied(null, true))
    }
}
