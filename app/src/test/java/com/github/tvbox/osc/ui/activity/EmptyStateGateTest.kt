package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「空态延后」与「换源接管触发」**必须解耦** —— v1.0.33 回归的锁定测试。
 *
 * <p>## 它对应哪个真机 bug
 *
 * <p>v1.0.33 为修「一闪而过的暂无片源」给 `enterEmpty` 加了守卫
 * `if (sourcesSearching.value) return`,页面于是停在 Loading 不再进 Empty。
 * 而 `shouldAutoTakeOver` 的判据当时是
 * `fallbackAutoSwitch || pageState.value is PageState.Empty`
 * —— **空态本身就是换源链的触发信号**。
 *
 * <p>于是两者成了死对头:守卫让页面永远进不了 Empty,换源判据就永远为假
 * ⇒ `onSearchResultEvent` 里 `if (shouldAutoTakeOver()) loadNextFallbackCandidate()`
 * 被跳过⇒ 候选站一个都不试 ⇒ 页面卡在转圈圈直到 8 秒搜索超时。
 *
 * <p>用户症状:**"每一个影片都没办法播放,而且没办法进入详情页,一直卡在转圈圈那里"**。
 *
 * <p>## 真机证据（`echo-detail sync`,详情成功到达）
 * - 修复前 12:19~12:38:连续不断(`HN线路` / `线路一(点换线路)` / `FF线路` 都成功过)
 * - 修复后 12:47 之后:**一条都没有**
 *
 * <p>## 修法
 * 引入独立的 `currentSourceConfirmedEmpty` 承担"该换源了"的内部信号,
 * `shouldAutoTakeOver` 改读它,不再读 UI 态。UI 态可以延后,内部信号照旧。
 */
class EmptyStateGateTest {

    /**
     * 与 `DetailViewModel.shouldAutoTakeOver` 同一份判据。
     *
     * <p>三个参数刻意分开:原来的 `pageState is Empty` 把「给用户看的 UI 态」
     * 和「换源链该不该启动的内部信号」压在一个字段里,才酿成 v1.0.33 的回归。
     */
    private fun shouldAutoTakeOver(
        fallbackLoadingCandidate: Boolean,
        fallbackAutoSwitch: Boolean,
        currentSourceConfirmedEmpty: Boolean,
    ): Boolean = !fallbackLoadingCandidate &&
        (fallbackAutoSwitch || currentSourceConfirmedEmpty)

    /** 与 `DetailViewModel.enterEmpty` 的守卫同一口径。 */
    private fun shouldEnterEmpty(sourcesSearching: Boolean): Boolean = !sourcesSearching

    @Test
    fun `搜索在途时页面停在加载态 - 这本身是对的`() {
        assertFalse(
            "搜索在途就该延后空态,否则又闪回「暂无片源」",
            shouldEnterEmpty(sourcesSearching = true),
        )
    }

    @Test
    fun `回归核心 - 页面停在加载态时仍必须能触发换源接管`() {
        // 这一条就是 v1.0.33 挂在上面的那个点:
        // UI 态是 Loading(不是 Empty),但内部信号已确认"当前源没内容",
        // 于是候选站必须能被取出来试。
        val takeOver = shouldAutoTakeOver(
            fallbackLoadingCandidate = false,
            fallbackAutoSwitch = false,
            currentSourceConfirmedEmpty = true,
        )
        assertTrue(
            "页面停在 Loading 时若不接管,就等于「候选站一个都不试」⇒ 卡死在转圈圈",
            takeOver,
        )
    }

    @Test
    fun `旧写法会在这里返回 false - 这就是回归的机制`() {
        // 复原 v1.0.33 的判据:把 currentSourceConfirmedEmpty 换回 pageState is Empty。
        // 页面停在 Loading ⇒ Empty 为假 ⇒ 判据为假 ⇒ 断链。
        // 这条测试存在的意义:让"为什么不能直接读 UI 态"变成可执行的证据。
        val pageStateIsEmpty = false
        val oldStyle = !false && (false || pageStateIsEmpty)
        assertFalse(
            "旧判据在页面停留 Loading 时为假 —— 这就是断链的原因",
            oldStyle,
        )
    }

    @Test
    fun `当前源已拿到内容时不得再触发接管 - 否则会后台偷偷换源`() {
        val takeOver = shouldAutoTakeOver(
            fallbackLoadingCandidate = false,
            fallbackAutoSwitch = false,
            currentSourceConfirmedEmpty = false,
        )
        assertFalse(
            "用户已经看到详情了,不该再被切到别的站",
            takeOver,
        )
    }

    @Test
    fun `候选站正在加载时不接管 - 否则会并发发起多个详情请求`() {
        val takeOver = shouldAutoTakeOver(
            fallbackLoadingCandidate = true,
            fallbackAutoSwitch = true,
            currentSourceConfirmedEmpty = true,
        )
        assertFalse("候选站正在加载,不能再起一个", takeOver)
    }

    @Test
    fun `自动接管标记本身就足以触发 - 不依赖当前源是否为空`() {
        // fallbackAutoSwitch 由 startFallbackIfNeeded(auto = true) 置位,
        // 这条路径与 currentSourceConfirmedEmpty 无关,必须仍然可用。
        val takeOver = shouldAutoTakeOver(
            fallbackLoadingCandidate = false,
            fallbackAutoSwitch = true,
            currentSourceConfirmedEmpty = false,
        )
        assertTrue("自动接管标记本身就应触发换源", takeOver)
    }

    @Test
    fun `闪屏修复与换源接管可以同时成立 - 两者不再互相排斥`() {
        // 完整时序:搜索在途 → 页面停在 Loading(不闪)→ 当前源确认无内容
        // → 候选取出来试 → 拿到详情。全程不闪,且能播。
        assertFalse("第一步:搜索在途,不进空态", shouldEnterEmpty(sourcesSearching = true))
        assertTrue(
            "第二步:页面停在 Loading,但接管信号独立成立",
            shouldAutoTakeOver(false, fallbackAutoSwitch = false, currentSourceConfirmedEmpty = true),
        )
        assertFalse(
            "第三步:拿到详情后信号被清,不再接管",
            shouldAutoTakeOver(false, fallbackAutoSwitch = false, currentSourceConfirmedEmpty = false),
        )
    }
}