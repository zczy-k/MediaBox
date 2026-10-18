package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **按源总数分档**判据单测(2026-10-17 用户定案)。锁四件事:
 * ① 池子越大档数越多(≤99 一档 / 100~300 两档 / >300 三档);
 * ② 每档**累计**单调不减、末档必等于全量;③ 边界值不跳档、越界档号钳得住;
 * ④ "还有下一档吗 / 还剩几个没问"与档号自洽 —— 自动链只跑第 1 档,
 *    而小池的第 1 档就是全量,所以小订阅不该出现「继续找源」。
 */
class ExpandPoolPolicyTest {

    // ==================== waveCount ====================

    @Test
    fun `池子大小决定档数`() {
        assertEquals(1, ExpandPoolPolicy.waveCount(1))
        assertEquals(1, ExpandPoolPolicy.waveCount(99))
        assertEquals(2, ExpandPoolPolicy.waveCount(100))   // 跨过 99 就分两档
        assertEquals(2, ExpandPoolPolicy.waveCount(300))
        assertEquals(3, ExpandPoolPolicy.waveCount(301))   // 跨过 300 就分三档
        assertEquals(3, ExpandPoolPolicy.waveCount(315))
        assertEquals(3, ExpandPoolPolicy.waveCount(10_000))
    }

    @Test
    fun `没有源就没有档`() {
        assertEquals(0, ExpandPoolPolicy.waveCount(0))
        assertEquals(0, ExpandPoolPolicy.waveCount(-7))
    }

    // ==================== poolSizeForWave ====================

    @Test
    fun `315个源三档跑完`() {
        assertEquals(105, ExpandPoolPolicy.poolSizeForWave(315, 1)) // 1/3
        assertEquals(210, ExpandPoolPolicy.poolSizeForWave(315, 2)) // 2/3
        assertEquals(315, ExpandPoolPolicy.poolSizeForWave(315, 3)) // 全量
    }

    @Test
    fun `250个源两档跑完`() {
        assertEquals(125, ExpandPoolPolicy.poolSizeForWave(250, 1)) // 1/2
        assertEquals(250, ExpandPoolPolicy.poolSizeForWave(250, 2)) // 全量
    }

    @Test
    fun `小池一档就是全量`() {
        // 自动链只跑第 1 档 ⇒ 小订阅不需要再点一次就搜完了
        assertEquals(90, ExpandPoolPolicy.poolSizeForWave(90, 1))
        assertEquals(5, ExpandPoolPolicy.poolSizeForWave(5, 1))
        assertEquals(1, ExpandPoolPolicy.poolSizeForWave(1, 1))
    }

    @Test
    fun `各档累计单调不减且末档等于全量`() {
        for (total in listOf(1, 2, 3, 50, 99, 100, 101, 250, 300, 301, 315, 999)) {
            val n = ExpandPoolPolicy.waveCount(total)
            var prev = 0
            for (wave in 1..n) {
                val size = ExpandPoolPolicy.poolSizeForWave(total, wave)
                assertTrue("total=$total wave=$wave size=$size 越界", size in 1..total)
                assertTrue("total=$total 第$wave 档($size)应 >= 上一档($prev)", size >= prev)
                prev = size
            }
            assertEquals("total=$total 末档必须是全量", total, prev)
        }
    }

    @Test
    fun `越界档号钳到范围内`() {
        assertEquals(
            ExpandPoolPolicy.poolSizeForWave(315, 1),
            ExpandPoolPolicy.poolSizeForWave(315, 0),
        )
        assertEquals(
            ExpandPoolPolicy.poolSizeForWave(315, 1),
            ExpandPoolPolicy.poolSizeForWave(315, -3),
        )
        // 超出最后一档 = 全量(不会溢出、也不会退回小档)
        assertEquals(315, ExpandPoolPolicy.poolSizeForWave(315, 4))
        assertEquals(315, ExpandPoolPolicy.poolSizeForWave(315, 99))
    }

    @Test
    fun `零或负总数返回0`() {
        assertEquals(0, ExpandPoolPolicy.poolSizeForWave(0, 1))
        assertEquals(0, ExpandPoolPolicy.poolSizeForWave(-5, 2))
    }

    // ==================== hasNextWave / remainingFor ====================

    @Test
    fun `还有没有下一档`() {
        assertFalse(ExpandPoolPolicy.hasNextWave(90, 1))    // 小池:自动那一档就搜完了
        assertTrue(ExpandPoolPolicy.hasNextWave(250, 1))
        assertFalse(ExpandPoolPolicy.hasNextWave(250, 2))
        assertTrue(ExpandPoolPolicy.hasNextWave(315, 2))
        assertFalse(ExpandPoolPolicy.hasNextWave(315, 3))
    }

    @Test
    fun `还剩多少个没问与档号自洽`() {
        assertEquals(0, ExpandPoolPolicy.remainingFor(90, 1))
        assertEquals(125, ExpandPoolPolicy.remainingFor(250, 1))
        assertEquals(0, ExpandPoolPolicy.remainingFor(250, 2))
        assertEquals(210, ExpandPoolPolicy.remainingFor(315, 1))
        assertEquals(105, ExpandPoolPolicy.remainingFor(315, 2))
        assertEquals(0, ExpandPoolPolicy.remainingFor(315, 3))
    }

    // ==================== shouldAutoAdvanceOnExhaustion ====================

    /**
     * 2026-10-18 新增判据的**核心不变量**:兜底链在"候选全失败"时才会自动推进。
     *
     * 真机《怦然心动》现场:源池 317,自动链只跑第 1 档(106 个),9 个候选全失败
     * 就报「视频播放出错」—— 而剩余 211 个源从未被问过,搜索 9 秒后才跑完第一档。
     * 这个判据就是为了让兜底链**把没问过的源问完**。
     */
    @Test
    fun `候选全失败且还有档时才自动推进`() {
        // 该推进:三条闸全开
        assertTrue(
            ExpandPoolPolicy.shouldAutoAdvanceOnExhaustion(
                allCandidatesFailed = true, hasMoreWave = true,
                autoAdvancedWaves = 0, maxAutoWaves = 3,
            )
        )
        assertTrue(
            ExpandPoolPolicy.shouldAutoAdvanceOnExhaustion(
                allCandidatesFailed = true, hasMoreWave = true,
                autoAdvancedWaves = 2, maxAutoWaves = 3,
            )
        )
    }

    @Test
    fun `还有候选没试完就不推进`() {
        // 候选表里还有没试过的源 ⇒ 先继续试候选,不必再搜新源
        assertFalse(
            ExpandPoolPolicy.shouldAutoAdvanceOnExhaustion(
                allCandidatesFailed = false, hasMoreWave = true,
                autoAdvancedWaves = 0, maxAutoWaves = 3,
            )
        )
    }

    @Test
    fun `池子已经问尽就不推进`() {
        // 没有下一档可跑 ⇒ 该给结论了(继续"推进"只会在原地打转)
        assertFalse(
            ExpandPoolPolicy.shouldAutoAdvanceOnExhaustion(
                allCandidatesFailed = true, hasMoreWave = false,
                autoAdvancedWaves = 0, maxAutoWaves = 3,
            )
        )
    }

    @Test
    fun `推进次数达到上限就停`() {
        // ⚠️ 这条是防"无限重搜"的关键(2026-10-13 真机:token=13→14 反复,用户看到"卡死无举动")
        assertFalse(
            ExpandPoolPolicy.shouldAutoAdvanceOnExhaustion(
                allCandidatesFailed = true, hasMoreWave = true,
                autoAdvancedWaves = 3, maxAutoWaves = 3,
            )
        )
        // 超过上限同样不能推进(防御性:计数只增,不该出现 >max 的合法值)
        assertFalse(
            ExpandPoolPolicy.shouldAutoAdvanceOnExhaustion(
                allCandidatesFailed = true, hasMoreWave = true,
                autoAdvancedWaves = 9, maxAutoWaves = 3,
            )
        )
    }

    @Test
    fun `上限取3正好能把三档池问完`() {
        assertEquals(3, ExpandPoolPolicy.MAX_AUTO_ADVANCE_WAVES)
        // >300 的池子分三档 ⇒ 上限 3 恰好覆盖"自动把全池问一遍"
        assertEquals(3, ExpandPoolPolicy.waveCount(317))
    }
}
