package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AvailabilityHeuristic] 的边界测试。
 *
 * <p>核心是**零误杀**这条底线:粗筛一旦把有资源的卡片藏了,用户就永远看不到这部片子,
 * 比"多显示一张没资源的海报"严重得多。所以每个用例都标了"必须放过"还是"必须拦下"。
 */
class AvailabilityHeuristicTest {

    private fun video(
        id: String = "1",
        state: String? = null,
        note: String? = null,
        sourceKey: String? = "js_douban",
    ) = Movie.Video().apply {
        this.id = id
        this.state = state
        this.note = note
        this.sourceKey = sourceKey
    }

    // ── 必须拦下：站点自己说了没资源 ─────────────────────────────────────────

    @Test
    fun `state里的暂无资源要拦下`() {
        assertTrue(AvailabilityHeuristic.mightBeUnavailable(video(state = "暂无资源")))
    }

    @Test
    fun `note里的未收录要拦下`() {
        assertTrue(AvailabilityHeuristic.mightBeUnavailable(video(note = "未收录")))
    }

    @Test
    fun `常见措辞变体都要拦下`() {
        listOf(
            "暂无片源", "暂无更新", "已下架", "已失效", "维护中",
            "该资源已失效", "停止更新", "资源已删除", "永久维护", "暂停服务",
        ).forEach { w ->
            assertTrue("「$w」应被拦下", AvailabilityHeuristic.mightBeUnavailable(video(state = w)))
        }
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(AvailabilityHeuristic.mightBeUnavailable(video(state = "CENSORED")))
        assertTrue(AvailabilityHeuristic.mightBeUnavailable(video(state = "404")))
    }

    @Test
    fun `已标记的影片直接拦下`() {
        val v = video(id = "abc")
        val marks = setOf("js_douban|abc")
        assertTrue(AvailabilityHeuristic.mightBeUnavailable(v, marks))
    }

    // ── 必须放过：任何拿不准的情况一律显示 ─────────────────────────────────

    @Test
    fun `正常条目必须放过`() {
        assertFalse(AvailabilityHeuristic.mightBeUnavailable(video(state = "正片", note = "2025")))
        assertFalse(AvailabilityHeuristic.mightBeUnavailable(video()))
    }

    /**
     * **回归重点**:含"更新"的正常备注绝不能被"暂无更新"误伤。
     * 这是关键词匹配的经典陷阱 —— 只收完整词而不收单字,就是为了防这个。
     */
    @Test
    fun `含更新的正常备注不能被误杀`() {
        listOf(
            "更新至20集", "更新至第30集", "全24集", "连载中", "更新中",
            "每周五更新", "更新至大结局",
        ).forEach { w ->
            assertFalse("「$w」是正常描述,不该被拦", AvailabilityHeuristic.mightBeUnavailable(video(note = w)))
        }
    }

    @Test
    fun `空白与null都放过`() {
        assertFalse(AvailabilityHeuristic.mightBeUnavailable(video(state = "", note = "")))
        assertFalse(AvailabilityHeuristic.mightBeUnavailable(video(state = "   ", note = null)))
    }

    @Test
    fun `标记未命中该片时放过`() {
        val v = video(id = "xyz")
        assertFalse(AvailabilityHeuristic.mightBeUnavailable(v, setOf("js_douban|abc")))
    }

    @Test
    fun `null条目按不可用处理`() {
        assertTrue(AvailabilityHeuristic.mightBeUnavailable(null))
    }

    @Test
    fun `markOf在缺键时返回空串`() {
        assertEquals("", AvailabilityHeuristic.markOf("", "1"))
        assertEquals("", AvailabilityHeuristic.markOf("a", ""))
        assertEquals("a|1", AvailabilityHeuristic.markOf("a", "1"))
    }
}