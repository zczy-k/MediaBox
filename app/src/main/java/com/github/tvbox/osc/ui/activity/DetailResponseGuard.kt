package com.github.tvbox.osc.ui.activity

/**
 * 详情回包的代次守卫(V4)。
 *
 * `DetailViewModel` 原先靠"换 `SourceViewModel` 实例"隔离迟到回包:旧实例上的回包回到已摘除观察者的
 * 通道,天然到不了当前内容。改成单实例 + 流收集后这层隔离消失,必须**显式**判代次 ——
 * 尤其是"换到另一个源、但同一个 vodId"的场景(源 A 与源 B 都能搜到同一部片),此时
 * `sourceKey`/`vodId` 的比对分不出旧回包与新回包,只有代次能分。
 *
 * 规则:回包自带的代次与当前代次不等 ⇒ 丢弃(换片、fallback 换站、重试三条路径共用同一条规则);
 * 不带代次(`null`,代次功能上线前入队的在途请求)同样不采信。
 */
internal object DetailResponseGuard {

    fun isCurrent(requestToken: Int, responseToken: Int?): Boolean =
        responseToken != null && responseToken == requestToken

    /**
     * 详情请求"根本发不出去"的目标(空 id / 聚合搜索占位 id / 源不在当前订阅 / 索引型源)。
     *
     * 抽成纯函数是因为它决定 [DetailViewModel.loadDetail] 是否早退,而早退分支**同样必须换代次**:
     * V4 首版把换代次写在早退之后,导致这类换片只改内容不换代,上一代的迟到回包被守卫放行
     * (同源不同片时 `sourceKey` 相同,内容比对也拦不住) ⇒ 旧片顶掉新页。用例见
     * `DetailResponseGuardTest.detailTargetsThatCannotBeLoaded`。
     *
     * <p>## 为什么 2026-10-09 要加 [indexSource]
     *
     * `SourceBean.indexs`(配置里 `indexs=1`)声明"索引型源:卡片只是关键词入口,**只走搜索不进详情**"。
     * 这个字段此前**从未被消费** —— 于是 js_douban 这类索引型源带着**真实数字 id**(不是 `msearch:` 占位)
     * 的卡片照常发起详情请求,而它注定返回没有 `list` 字段的回包 ⇒ 解析空 ⇒ 页面停 Loading 等 120 源聚合搜索。
     * 真机实测:同一部片在 10-08 23:22 / 10-09 13:49 各复现一次,每次白等 43 秒。
     * 判定早退后走 [DetailViewModel.onDetailUnavailable],直接用**片名**做聚合搜索 —— 与 `msearch:` 同路。
     */
    fun isUnloadableTarget(vodId: String, sourceMissing: Boolean, indexSource: Boolean = false): Boolean =
        vodId.isEmpty() || vodId.startsWith("msearch:") || sourceMissing || indexSource
}
