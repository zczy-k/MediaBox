package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕缓存 LRU 裁剪口径(锁 [SubtitleCacheJanitor.pickEviction])。
 *
 * <p>IO 部分([SubtitleCacheJanitor.trim])在 JVM 上测不了 —— 它要真实 [java.io.File];
 * 但"删哪些"才是容易写错的地方,所以把纯逻辑单独抽出来测。
 */
class SubtitleCacheJanitorTest {

    @Test
    fun `总量不超上限时不删`() {
        assertEquals(emptyList<Int>(), SubtitleCacheJanitor.pickEviction(listOf(1L, 2L, 3L), 3))
        assertEquals(emptyList<Int>(), SubtitleCacheJanitor.pickEviction(listOf(1L), 3))
    }

    @Test
    fun `超出上限时删最旧的`() {
        assertEquals(listOf(0, 1), SubtitleCacheJanitor.pickEviction(listOf(1L, 2L, 3L, 4L), 2))
        assertEquals(listOf(0), SubtitleCacheJanitor.pickEviction(listOf(1L, 2L, 3L), 2))
    }

    @Test
    fun `空目录不删`() {
        assertEquals(emptyList<Int>(), SubtitleCacheJanitor.pickEviction(emptyList(), 5))
        assertEquals(emptyList<Int>(), SubtitleCacheJanitor.pickEviction(emptyList(), 0))
    }

    @Test
    fun `上限为零时全删`() {
        assertEquals(listOf(0, 1, 2), SubtitleCacheJanitor.pickEviction(listOf(1L, 2L, 3L), 0))
    }

    /** 真实场景:40 集剧 × 2 条字幕 = 80 个文件,应全部保留 */
    @Test
    fun `单部剧的字幕数不会触发裁剪`() {
        val times = (1L..80L).toList()
        assertTrue(SubtitleCacheJanitor.pickEviction(times, SubtitleCacheJanitor.MAX_FILES).isEmpty())
    }

    /** 连看 10 部剧(800 个文件)后,应裁到只剩上限那么多 */
    @Test
    fun `长期使用后裁剪到上限`() {
        val times = (1L..800L).toList()
        val victims = SubtitleCacheJanitor.pickEviction(times, SubtitleCacheJanitor.MAX_FILES)
        assertEquals(800 - SubtitleCacheJanitor.MAX_FILES, victims.size)
        // 保留的应是最新的 MAX_FILES 个
        assertEquals((800 - SubtitleCacheJanitor.MAX_FILES).toLong(), times[victims.last()])
    }
}