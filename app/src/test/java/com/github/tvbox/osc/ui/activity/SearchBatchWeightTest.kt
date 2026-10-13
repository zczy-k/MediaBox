package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.SourceWeightPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权重驱动分批(2026-10-13,《源与线路的权重模型》P1-1)单测。
 *
 * <p>锁三件事:① 保底配额真的把最低权重源捞进首轮(这是"不会死锁"的唯一安全网);
 * ② 一个源都不少(降权 ≠ 屏蔽);③ 首页源永不被降权。
 */
class SearchBatchWeightTest {

    private fun source(key: String, quick: Boolean): SourceBean {
        val bean = SourceBean()
        bean.key = key
        bean.setQuickSearch(if (quick) 1 else 0)
        return bean
    }

    private fun split(
        sources: List<SourceBean>,
        home: String,
        weights: Map<String, Int>,
        lastSearched: Map<String, Long> = emptyMap(),
        floor: Int = SourceWeightPolicy.FLOOR_QUOTA_N,
    ): Pair<List<String>, List<String>> = SearchBatchPolicy.splitFastRoundSourcesByWeight(
        sources,
        homeKey = home,
        weightOf = { weights[it] ?: SourceWeightPolicy.NEUTRAL },
        lastSearchedAtOf = { lastSearched[it] ?: 0L },
        floorQuotaN = floor,
    )

    /**
     * 保底配额把权重最低的源**捞进首轮** —— 否则它排到 300 源队尾永不被搜,
     * 就是"没有安全网的事实封禁"。
     */
    @Test
    fun `保底配额把最低权重源捞进首轮`() {
        val sources = listOf(
            source("home", quick = true),
            source("good", quick = true),
            source("bad1", quick = false),
            source("bad2", quick = false),
            source("bad3", quick = false),
        )
        val weights = mapOf("home" to 60, "good" to 70, "bad1" to 5, "bad2" to 10, "bad3" to 15)
        val (fast, _) = split(sources, "home", weights, floor = 2)
        // 最低两个 bad1/bad2 必须进首轮
        assertTrue(fast.contains("bad1"))
        assertTrue(fast.contains("bad2"))
    }

    @Test
    fun `一个源都不少_降权不是屏蔽`() {
        val sources = listOf(
            source("home", quick = true),
            source("a", quick = true),
            source("b", quick = false),
            source("c", quick = false),
        )
        val weights = mapOf("home" to 60, "a" to 80, "b" to 5, "c" to 10)
        val (fast, deferred) = split(sources, "home", weights, floor = 1)
        assertEquals(sources.size, (fast + deferred).toSet().size)
        assertEquals(sources.map { it.key }.toSet(), (fast + deferred).toSet())
    }

    @Test
    fun `首页源永不被降权`() {
        val sources = listOf(source("home", quick = false), source("x", quick = false))
        val weights = mapOf("home" to 0, "x" to 50)
        val (fast, _) = split(sources, "home", weights, floor = 0)
        assertTrue(fast.contains("home"))
    }

    @Test
    fun `健康快源进首轮_低权重快源被降级`() {
        val sources = listOf(
            source("home", quick = true),
            source("healthyQuick", quick = true),
            source("deadQuick", quick = true),
        )
        val weights = mapOf("home" to 60, "healthyQuick" to 70, "deadQuick" to 10)
        val (fast, _) = split(sources, "home", weights, floor = 0)
        assertTrue(fast.contains("healthyQuick"))
        assertTrue(!fast.contains("deadQuick"))
    }

    @Test
    fun `权重并列时保底配额优先最久未搜的`() {
        val sources = listOf(
            source("home", quick = true),
            source("old", quick = false),
            source("mid", quick = false),
            source("new", quick = false),
        )
        val weights = mapOf("home" to 60, "old" to 5, "mid" to 5, "new" to 5)
        val lastSearched = mapOf("old" to 0L, "mid" to 100L, "new" to 300L)
        val (fast, _) = split(sources, "home", weights, lastSearched = lastSearched, floor = 1)
        // 最久未搜的 old 必须被捞进首轮
        assertTrue(fast.contains("old"))
    }

    @Test
    fun `空输入返回空对`() {
        val (fast, deferred) = split(emptyList(), "home", emptyMap())
        assertTrue(fast.isEmpty())
        assertTrue(deferred.isEmpty())
    }

    @Test
    fun `保底配额为0时退化为纯权重分层`() {
        val sources = listOf(source("home", quick = true), source("low", quick = false))
        val weights = mapOf("home" to 60, "low" to 5)
        val (fast, deferred) = split(sources, "home", weights, floor = 0)
        assertEquals(listOf("home"), fast)
        assertEquals(listOf("low"), deferred)
    }
}