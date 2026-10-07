package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 字幕缓存 LRU 裁剪口径(锁 [SubtitleCacheJanitor.pickEviction])。
 *
 * <p>IO 部分([SubtitleCacheJanitor.trim])在纯 JVM 上测不了 —— 它要真实 [java.io.File];
 * 但"删哪些"才是容易写错的地方,所以把纯逻辑单独抽出来测。
 *
 * <p>后半部分用 [TemporaryFolder] 补真实 IO 覆盖:`pickEviction` 正确 ≠ `trim` 正确
 * —— 后者还要处理目录缺失、文件名前缀过滤、`delete()` 失败等,这些同样会出问题。
 */
class SubtitleCacheJanitorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 在 /zimu/ 下造 n 个文件,mtime 从旧到新依次递增(间隔 1 分钟)。 */
    private fun seedZimu(n: Int) {
        val dir = tmp.newFolder("zimu")
        val base = 1_000_000L
        repeat(n) { i ->
            val f = File(dir, "sub_$i.srt")
            f.writeText("x")
            f.setLastModified(base + i * 60_000L)
        }
    }

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

    // ── 以下为真实 IO 覆盖:pickEviction 对 ≠ trim 对 ──────────────────────────

    @Test
    fun `目录不存在时返回0且不抛异常`() {
        assertEquals(0, SubtitleCacheJanitor.trim(tmp.newFolder("empty")))
        assertEquals(0, SubtitleCacheJanitor.trim(null))
    }

    @Test
    fun `文件数未过阈值时一个都不删`() {
        seedZimu(SubtitleCacheJanitor.MAX_FILES)
        assertEquals(0, SubtitleCacheJanitor.trim(tmp.root))
        assertEquals(SubtitleCacheJanitor.MAX_FILES, File(tmp.root, "zimu").listFiles()!!.size)
    }

    @Test
    fun `超过阈值时真的删掉最旧的那些文件`() {
        val over = SubtitleCacheJanitor.MAX_FILES + 50
        seedZimu(over)
        val removed = SubtitleCacheJanitor.trim(tmp.root)

        assertEquals(50, removed)
        val dir = File(tmp.root, "zimu")
        assertEquals(SubtitleCacheJanitor.MAX_FILES, dir.listFiles()!!.size)
        // 最旧的 50 个必须已被删掉
        assertFalse(File(dir, "sub_0.srt").exists())
        assertFalse(File(dir, "sub_49.srt").exists())
        // 最新的必须活下来
        assertTrue(File(dir, "sub_${over - 1}.srt").exists())
    }

    @Test
    fun `重复调用是幂等的`() {
        seedZimu(SubtitleCacheJanitor.MAX_FILES + 10)
        assertEquals(10, SubtitleCacheJanitor.trim(tmp.root))
        assertEquals(0, SubtitleCacheJanitor.trim(tmp.root))
        assertEquals(0, SubtitleCacheJanitor.trim(tmp.root))
        assertEquals(SubtitleCacheJanitor.MAX_FILES, File(tmp.root, "zimu").listFiles()!!.size)
    }

    @Test
    fun `本地导入的字幕副本也参与裁剪但不误删无关文件`() {
        val cacheDir = tmp.newFolder("c")
        repeat(SubtitleCacheJanitor.MAX_FILES + 20) { i ->
            val f = File(cacheDir, "subtitle_${i}_import.srt")
            f.writeText("x")
            f.setLastModified(1_000_000L + i * 60_000L)
        }
        // 混一个普通文件,确认它不被误删(裁剪只针对 subtitle_ 前缀)
        val other = File(cacheDir, "unrelated.dat")
        other.writeText("x")

        val removed = SubtitleCacheJanitor.trim(cacheDir)

        assertEquals(20, removed)
        assertTrue("非字幕文件不该被删", other.exists())
        assertEquals(
            SubtitleCacheJanitor.MAX_FILES,
            cacheDir.listFiles { f -> f.name.startsWith("subtitle_") }!!.size,
        )
    }
}