package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表页"点空回写后摘卡片"的**纯逻辑**口径(对应 `SearchViewModel.refreshAvailability`
 * 与 `HomeViewModel.refreshAvailability` 里那段过滤)。
 *
 * <p>这两个 VM 的过滤本体只有一行 `filterNot { mightBeUnavailable(...) }`,不值得为它建 VM 测试;
 * 但**"哪些条目该被摘掉"是产品承诺**(没资源的片子不该出现在首页/搜索),必须锁住。
 * 尤其下面两组:
 * <ul>
 *   <li>正常影片**绝不能**被摘(误杀比多显示一张严重得多);</li>
 *   <li>换源链里已确认无资源的要**一定**被摘 —— 摘不掉就是这条功能没生效。</li>
 * </ul>
 */
class AvailabilityRefreshPolicyTest {

    private fun video(
        sourceKey: String? = "s1",
        id: String? = "v1",
        name: String? = "影片",
        state: String? = null,
        note: String? = null,
    ) = Movie.Video().apply {
        this.sourceKey = sourceKey
        this.id = id
        this.name = name
        this.state = state
        this.note = note
    }

    @Test
    fun `换源链确认无资源的条目会被摘掉`() {
        val marks = setOf("s1|v1")
        val list = listOf(video(id = "v1"), video(id = "v2"), video(id = "v3"))
        val left = list.filterNot { AvailabilityHeuristic.mightBeUnavailable(it, marks) }
        assertEquals(listOf("v2", "v3"), left.map { it.id })
    }

    @Test
    fun `没有标记的条目一律保留`() {
        val marks = setOf("other|v9")
        val list = listOf(video(id = "v1"), video(id = "v2"))
        assertEquals(2, list.filterNot { AvailabilityHeuristic.mightBeUnavailable(it, marks) }.size)
    }

    @Test
    fun `正常影片不会被摘`() {
        val list = listOf(
            video(name = "挽救计划", note = "更新至20集"),
            video(name = "流浪地球2", state = "连载中"),
            video(name = "三体", note = "HD国语中字"),
            video(name = "无间道", note = "经典重映"),
        )
        assertEquals("正常影片不该被摘掉任何一张", 4,
            list.filterNot { AvailabilityHeuristic.mightBeUnavailable(it, emptySet()) }.size)
    }

    @Test
    fun `幂等 - 重复过滤结果不变`() {
        val marks = setOf("s1|v1")
        val list = listOf(video(id = "v1"), video(id = "v2"))
        val once = list.filterNot { AvailabilityHeuristic.mightBeUnavailable(it, marks) }
        val twice = once.filterNot { AvailabilityHeuristic.mightBeUnavailable(it, marks) }
        assertEquals(once.map { it.id }, twice.map { it.id })
    }

    @Test
    fun `标记只精确匹配站点与影片 - 不会误伤同名影片`() {
        // 另一个站点上的同 id 影片不是同一部,不该被牵连
        val marks = setOf("s1|v1")
        val sameIdOtherSite = video(sourceKey = "s2", id = "v1")
        assertFalse(AvailabilityHeuristic.mightBeUnavailable(sameIdOtherSite, marks))
        assertTrue(AvailabilityHeuristic.mightBeUnavailable(video(sourceKey = "s1", id = "v1"), marks))
    }

    @Test
    fun `标记命中后立即被摘 - 不需要等列表重载`() {
        // 这是"点空 → 返回搜索页海报已消失"的关键:过滤是纯内存的,不依赖重新请求
        val marks = setOf("s1|v1")
        val list = listOf(video(id = "v1"), video(id = "v2"))
        val purged = list.count { AvailabilityHeuristic.mightBeUnavailable(it, marks) }
        assertEquals(1, purged)
    }
}