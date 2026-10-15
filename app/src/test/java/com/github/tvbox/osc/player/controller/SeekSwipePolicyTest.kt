package com.github.tvbox.osc.player.controller

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 横滑快进跨度的口径:满屏 = 片长 1/8,并夹在 [60 秒, 15 分钟] 之间。
 *
 * 锁的是"长短片手感一致"这件事 —— 旧口径写死 240 秒/满屏,2 小时电影要滑 30 次满屏。
 */
class SeekSwipePolicyTest {

    private fun minutes(m: Int) = m * 60_000L

    @Test
    fun `满屏按时长的八分之一`() {
        assertEquals(75_000L, SeekSwipePolicy.fullSwipeSpanMs(minutes(10)))
        assertEquals(minutes(5), SeekSwipePolicy.fullSwipeSpanMs(minutes(40)))
        assertEquals(787_500L, SeekSwipePolicy.fullSwipeSpanMs(minutes(105)))
    }

    @Test
    fun `长片封顶十五分钟`() {
        assertEquals(SeekSwipePolicy.MAX_FULL_SWIPE_MS, SeekSwipePolicy.fullSwipeSpanMs(minutes(120)))
        assertEquals(SeekSwipePolicy.MAX_FULL_SWIPE_MS, SeekSwipePolicy.fullSwipeSpanMs(minutes(240)))
    }

    @Test
    fun `短片兜底六十秒`() {
        assertEquals(SeekSwipePolicy.MIN_FULL_SWIPE_MS, SeekSwipePolicy.fullSwipeSpanMs(minutes(5)))
        assertEquals(SeekSwipePolicy.MIN_FULL_SWIPE_MS, SeekSwipePolicy.fullSwipeSpanMs(minutes(1)))
    }

    @Test
    fun `时长未知按最短档_不许算出零跨度`() {
        // 直播 / duration 还没出来时 safeTimeMs 给 0:若按 1/8 算就是"滑了不动"
        assertEquals(SeekSwipePolicy.MIN_FULL_SWIPE_MS, SeekSwipePolicy.fullSwipeSpanMs(0L))
        assertEquals(SeekSwipePolicy.MIN_FULL_SWIPE_MS, SeekSwipePolicy.fullSwipeSpanMs(-1L))
    }
}
