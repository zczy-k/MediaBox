package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按比例动态扩池判据单测。锁三件事:
 * ① 轮次比例 1/3→1/2→2/3→全量且**单调不减**;② 小源数向上取整不退化;③ 扩池触发条件三缺一不可。
 */
class ExpandPoolPolicyTest {

    // ==================== poolSizeForRound ====================

    @Test
    fun `360源的比例分档`() {
        val total = 360
        assertEquals(120, ExpandPoolPolicy.poolSizeForRound(total, 1)) // 1/3
        assertEquals(180, ExpandPoolPolicy.poolSizeForRound(total, 2)) // 1/2
        assertEquals(240, ExpandPoolPolicy.poolSizeForRound(total, 3)) // 2/3
        assertEquals(360, ExpandPoolPolicy.poolSizeForRound(total, 4)) // 全量
    }

    @Test
    fun `轮次单调不减`() {
        val total = 359
        val sizes = (1..ExpandPoolPolicy.FINAL_ROUND).map { ExpandPoolPolicy.poolSizeForRound(total, it) }
        for (i in 1 until sizes.size) {
            assertTrue("第${i + 1}轮(${sizes[i]})应 >= 第${i}轮(${sizes[i - 1]})", sizes[i] >= sizes[i - 1])
        }
        assertEquals(total, sizes.last())
    }

    @Test
    fun `超终结轮仍为全量`() {
        assertEquals(100, ExpandPoolPolicy.poolSizeForRound(100, ExpandPoolPolicy.FINAL_ROUND))
        assertEquals(100, ExpandPoolPolicy.poolSizeForRound(100, ExpandPoolPolicy.FINAL_ROUND + 5))
    }

    @Test
    fun `小源数向上取整不退化`() {
        // 5 个源的 1/3 = 1.67 → 上取整 2(不能是 1,否则扩池在小订阅上无意义)
        assertEquals(2, ExpandPoolPolicy.poolSizeForRound(5, 1))
        // 3 个源的 1/3 = 1
        assertEquals(1, ExpandPoolPolicy.poolSizeForRound(3, 1))
        // 2 个源的 1/3 = 0.67 → 1
        assertEquals(1, ExpandPoolPolicy.poolSizeForRound(2, 1))
    }

    @Test
    fun `永不超过总数`() {
        for (total in 1..500 step 37) {
            for (round in 1..6) {
                val size = ExpandPoolPolicy.poolSizeForRound(total, round)
                assertTrue("total=$total round=$round size=$size", size in 1..total)
            }
        }
    }

    @Test
    fun `非法轮次按第一轮处理`() {
        assertEquals(
            ExpandPoolPolicy.poolSizeForRound(90, 1),
            ExpandPoolPolicy.poolSizeForRound(90, 0),
        )
        assertEquals(
            ExpandPoolPolicy.poolSizeForRound(90, 1),
            ExpandPoolPolicy.poolSizeForRound(90, -3),
        )
    }

    @Test
    fun `零或负总数返回0`() {
        assertEquals(0, ExpandPoolPolicy.poolSizeForRound(0, 1))
        assertEquals(0, ExpandPoolPolicy.poolSizeForRound(-5, 2))
    }

    // ==================== isFinalRound ====================

    @Test
    fun `终结轮判定`() {
        assertFalse(ExpandPoolPolicy.isFinalRound(1))
        assertFalse(ExpandPoolPolicy.isFinalRound(3))
        assertTrue(ExpandPoolPolicy.isFinalRound(4))
        assertTrue(ExpandPoolPolicy.isFinalRound(5))
    }

    // ==================== shouldExpand ====================

    @Test
    fun `收口且0命中且有更多源才扩池`() {
        assertTrue(ExpandPoolPolicy.shouldExpand(hitCount = 0, roundDone = true, curSize = 120, total = 360))
    }

    @Test
    fun `未收口不扩`() {
        assertFalse(ExpandPoolPolicy.shouldExpand(hitCount = 0, roundDone = false, curSize = 120, total = 360))
    }

    @Test
    fun `已有命中不扩`() {
        assertFalse(ExpandPoolPolicy.shouldExpand(hitCount = 3, roundDone = true, curSize = 120, total = 360))
    }

    @Test
    fun `已到全量不扩`() {
        assertFalse(ExpandPoolPolicy.shouldExpand(hitCount = 0, roundDone = true, curSize = 360, total = 360))
    }
}