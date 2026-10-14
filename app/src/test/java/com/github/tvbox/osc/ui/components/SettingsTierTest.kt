package com.github.tvbox.osc.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设置页"档位行"的读数口径:滑块换成固定档位后,KV 里可能存着老版本留下的中间值
 * (长按倍速默认 3、预载时长曾经是 20..120 任意 10 的倍数)。
 * 显示必须就近落档 —— 否则要么显示不出来,要么显示成比实际更高的一档,用户以为已经改了。
 */
class SettingsTierTest {

    @Test
    fun `恰好等于档位时直接命中`() {
        val speeds = listOf(2, 4, 8)
        assertEquals(0, nearestTier(speeds, 2))
        assertEquals(1, nearestTier(speeds, 4))
        assertEquals(2, nearestTier(speeds, 8))
    }

    @Test
    fun `落在两档之间取最近的一档`() {
        val speeds = listOf(2, 4, 8)
        // 老默认值 3 离 2 和 4 一样远 ⇒ 平手取低档,不许把读数往高里报
        assertEquals(0, nearestTier(speeds, 3))
        assertEquals(1, nearestTier(speeds, 5))
        // 6 离 4 和 8 一样远 ⇒ 同样贴低档
        assertEquals(1, nearestTier(speeds, 6))
    }

    @Test
    fun `超出档位范围贴两端`() {
        val speeds = listOf(2, 4, 8)
        assertEquals(0, nearestTier(speeds, 1))
        assertEquals(2, nearestTier(speeds, 100))
    }

    @Test
    fun `预载时长按同样口径`() {
        val seconds = listOf(20, 60)
        assertEquals(0, nearestTier(seconds, 20))
        // 老滑块停在 40(两档正中间)⇒ 贴 20 秒,不夸大成"提前 60 秒"
        assertEquals(0, nearestTier(seconds, 40))
        assertEquals(1, nearestTier(seconds, 120))
    }
}
