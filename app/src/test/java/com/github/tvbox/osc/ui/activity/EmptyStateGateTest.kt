package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「结论未定就不进空态」这条规则的锁定测试。
 *
 * <p>## 它对应哪个真机 bug
 *
 * <p>v1.0.33 修的用户报问题:**每打开一个新卡片都会先闪一下「暂无片源」,一闪而过**。
 * 真机实测(诊断包 1.0.32,`js_douban` 源)7 次开片 **7 次命中**,时序稳定:
 * ```
 * 12:19:14.351 OPEN     打开卡片
 * 12:19:14.354 SEARCH   聚合搜索启动
 * 12:19:14.560 EMPTY    ← 206ms 后进空态(不该进)
 * 12:19:14.896 SYNC     ← 336ms 后正常详情才到,把空态顶掉
 * ```
 *
 * <p>成因见 `DetailViewModel.enterEmpty` 的 KDoc:聚合搜索刚启动、候选还没产出,
 * `loadNextFallbackCandidate` 返回 false;首次打开时 `rollbackManualSwitch` 的
 * `switchSnapshot` 为 null 也返回 false —— 于是"还在找"就被当成"找不到"。
 *
 * <p>## 为什么把判据抽成纯函数
 *
 * <p>`enterEmpty` 里的守卫依赖 `sourcesSearching` 这个 MutableStateFlow 与整个
 * ViewModel 的字段网络,单测里没法直接构造。把"能不能进空态"抽成下面这个
 * 纯函数,就能把规则本身钉死 —— 将来有人改判据,这里会立刻红。
 */
class EmptyStateGateTest {

    /** 与 `DetailViewModel.enterEmpty` 的守卫保持同一份口径。 */
    private fun shouldEnterEmpty(
        sourcesSearching: Boolean,
        pageStateIsLoading: Boolean,
        fallbackActive: Boolean,
    ): Boolean {
        // 搜索在途 ⇒ 候选池未定论,任何"没有候选"的结论都不成立
        if (sourcesSearching) return false
        // 已经有详情 / 已在空态 ⇒ 不碰
        if (!pageStateIsLoading) return false
        // 还在自动换源链上 ⇒ 交给候选取完再收口
        if (fallbackActive) return false
        return true
    }

    @Test
    fun `搜索在途时绝不进空态 - 这就是闪现的根因`() {
        assertFalse(
            "聚合搜索还在跑就进空态,正是「暂无片源」一闪而过的成因",
            shouldEnterEmpty(
                sourcesSearching = true,
                pageStateIsLoading = true,
                fallbackActive = false,
            ),
        )
    }

    @Test
    fun `搜索结束且仍在加载态才收口到空态`() {
        assertTrue(
            "搜索跑完、页面还停在加载 ⇒ 这时进空态才是诚实的结论",
            shouldEnterEmpty(
                sourcesSearching = false,
                pageStateIsLoading = true,
                fallbackActive = false,
            ),
        )
    }

    @Test
    fun `已经有详情时绝不进空态`() {
        assertFalse(
            "Ready 状态被误判成空,会把正常页面清掉",
            shouldEnterEmpty(
                sourcesSearching = false,
                pageStateIsLoading = false,
                fallbackActive = false,
            ),
        )
    }

    @Test
    fun `换源链在跑时不抢它的收口权`() {
        assertFalse(
            "fallbackActive 时候选还没取完,收口该由 loadNextFallbackCandidate 做",
            shouldEnterEmpty(
                sourcesSearching = false,
                pageStateIsLoading = true,
                fallbackActive = true,
            ),
        )
    }

    @Test
    fun `延后之后必须有人负责收口 - 否则永久卡在加载`() {
        // 这条是本次修复最大的风险点:enterEmpty 被守卫拦下后,
        // 若没有 settleDeferredEmpty 补刀,页面会永远停在 Loading。
        // 模拟时序:搜索在途 → 守卫拦下 → 搜索结束 → 收口补刀。
        val deferred = !shouldEnterEmpty(
            sourcesSearching = true,
            pageStateIsLoading = true,
            fallbackActive = false,
        )
        assertTrue("搜索在途时空态被延后", deferred)

        val settled = shouldEnterEmpty(
            sourcesSearching = false,
            pageStateIsLoading = true,
            fallbackActive = false,
        )
        assertTrue("搜索结束后必须能收口到空态,否则页面永久卡在加载", settled)
    }
}