package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V4 的核心断言:**迟到回包绝不串进新内容**(硬约束,不可弱化)。
 *
 * 旧实现靠"换 `SourceViewModel` 实例 + 摘观察者"隔离,新实现靠代次比对 —— 这些用例是那条等价性的
 * 书面凭据。覆盖两个判据:代次相等才采信、以及"详情请求发不出去"的三种目标(它们同样必须换代次,
 * 见第二个用例的注释 —— V4 首版正是在这里漏了自增)。
 *
 * **未覆盖(已知缺口)**:没有任何用例锁"代次在哪几条路径上自增"。`DetailViewModel` 在纯 JUnit 下
 * 无法实例化(`Handler(Looper.getMainLooper())`、`App.getInstance()`、`MutableLiveData` 初始化器),
 * 所以那条不变量目前只有代码注释与 `loadDetail` 里的自增位置守着 —— 改动 `loadDetail`/`loadDetailInternal`
 * 时请人工确认:换片/换源/重试(含早退分支)必须换代,fallback 候选站沿用当代。
 */
class DetailResponseGuardTest {

    @Test
    fun responseFromCurrentGenerationPasses() {
        assertTrue(DetailResponseGuard.isCurrent(requestToken = 7, responseToken = 7))
    }

    @Test
    fun lateResponseFromPreviousGenerationIsDropped() {
        // 换片/换源/重试:请求代次已自增,上一代的回包即使 sourceKey/vodId 都对得上也必须丢
        assertFalse(DetailResponseGuard.isCurrent(requestToken = 8, responseToken = 7))
    }

    @Test
    fun responseAheadOfCurrentGenerationIsDropped() {
        // 反向不等同样丢(不假设回包一定"落后");顺带锁住"同源不同片"那一格:
        // 源 A 与源 B 命中同一部片时 sourceKey 会变而 vodId 可能相同,内容比对分不出来,只有代次能分
        assertFalse(DetailResponseGuard.isCurrent(requestToken = 7, responseToken = 8))
    }

    @Test
    fun untaggedResponseIsNotBelieved() {
        // null = 没有代次信息(代次上线前入队的在途请求),不采信
        assertFalse(DetailResponseGuard.isCurrent(requestToken = 1, responseToken = null))
    }

    @Test
    fun fallbackCandidatesAllCountAsCurrent() {
        // fallback 的最后一个候选站与第一个在同一代内 ⇒ 迟到的那个回包**会被采纳**。
        // 这是刻意的(旧实现里 fallback 全程用同一个 SourceViewModel 实例,同理);
        // 也正因"同代内不换代",换片/换源/重试那几条路径**必须**换代。
        val generation = 11
        assertTrue(DetailResponseGuard.isCurrent(generation, generation))
        assertFalse(DetailResponseGuard.isCurrent(generation + 1, generation))
    }

    @Test
    fun detailTargetsThatCannotBeLoaded() {
        // 这三种目标走 loadDetail 的早退分支(onDetailUnavailable)。早退**同样必须换代次** ——
        // V4 首版把换代次写在早退之后,于是"空 id / msearch 占位 / 源不在当前订阅"这类换片只改内容
        // 不换代,上一代的迟到回包被放行(同源不同片时连 sourceKey 比对都拦不住)。
        assertTrue(DetailResponseGuard.isUnloadableTarget(vodId = "", sourceMissing = false))
        assertTrue(DetailResponseGuard.isUnloadableTarget(vodId = "msearch:123", sourceMissing = false))
        assertTrue(DetailResponseGuard.isUnloadableTarget(vodId = "12345", sourceMissing = true))
    }

    @Test
    fun loadableTargetIsNotTreatedAsUnavailable() {
        assertFalse(DetailResponseGuard.isUnloadableTarget(vodId = "12345", sourceMissing = false))
        // 边界:前缀相近但不是 msearch 占位
        assertFalse(DetailResponseGuard.isUnloadableTarget(vodId = "msearch123", sourceMissing = false))
    }
}
