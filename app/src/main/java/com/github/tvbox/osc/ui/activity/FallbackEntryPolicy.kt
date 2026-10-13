package com.github.tvbox.osc.ui.activity

/**
 * 换源链的**入口闸门**判定(纯规则,无 Android 依赖,可 JVM 单测)。
 *
 * <p>## 这道闸门保护的是什么
 * 源配置里 `changeable=0` 表示"这是一个**钉住的播放源**":最典型的是
 * `🗂我的云盘┃配置`(`csp_MyDriveGuard`)。这类源是用户自己的内容入口,一旦取不到内容,
 * 应当**如实报错**,而绝不能被后台静默换成某个随机聚合站去播 —— 那既不是用户要的东西,
 * 也可能变成"点自己的云盘,放出来一部别处的片"。
 *
 * <p>## 为什么 2026-10-13 要开一个"索引型"豁口
 * `changeable` 这道闸门写在**详情兜底链**上,而后来(2026-10-09)详情多了一条**索引型早退**:
 * `indexs=1`、或实测"回包合法 JSON 却缺 list"≥2 次(见
 * [com.github.tvbox.osc.util.DetailNoListPolicy])的源,根本**不发详情请求**,
 * 直接走 [DetailViewModel.onDetailUnavailable] 让聚合搜索接管。
 *
 * <p>问题在于:索引型源(实测 `js_douban`,`"searchable":0,"quickSearch":0,"changeable":0`)
 * **本身就不是播放源** —— 它没有任何线路,内容永远只能靠聚合搜索从别站取。
 * 对它而言 `changeable=0` 的语义只是"站内没有可换的线路",**不是**"不许去别处取内容"。
 * 照旧拦下 ⇒ 链一启动就被否掉 ⇒ 已经灌进候选表的种子候选
 * (见 [DetailViewModel.seedCandidatesFromCache])**没人消费**,一直躺在列表里,
 * 直到某次迟到回包把链偶然拉起来。
 *
 * <p>真机实测(v1.0.83-diag,2026-10-13 16:49,js_douban《潜伏6》二次进入):
 * `cache-seed n=1` → 链被闸门否掉 → **55 秒后**才靠新到候选起播。
 *
 * <p>## 关键:这道闸门**本来就没能拦住**换源,只是把换源推迟了
 * 详情为空时会置 `currentSourceConfirmedEmpty = true`(见
 * [DetailViewModel.handleEmptyDetail]),而 [DetailViewModel.shouldAutoTakeOver] 的判据是
 * `fallbackAutoSwitch || currentSourceConfirmedEmpty`。于是**任何**一次聚合搜索回包
 * 都会经由 `onSearchResultEvent → shouldAutoTakeOver → loadNextFallbackCandidate`
 * 直接拉起换源链 —— **这条路根本不经过本闸门**。
 *
 * <p>也就是说 `changeable=0` 对"详情取不到内容"这类源从来只是**延迟**换源(延迟多久取决于
 * 慢源何时回包,实测 55 秒),并非**阻止**换源。既然如此,本闸门在索引型源上唯一确定的
 * 效果就是让用户白等;把它改成放行,不新增任何原本做不到的能力,只是去掉那段空转。
 *
 * <p>## 为什么"已在链上"也要放行
 * 候选站同样可能带 `changeable=0`(如 `csp_MyDriveGuard` 的 `searchable=1`,会被聚合搜索收进候选)。
 * 若在链上还按源配置裁决,就会**在某个候选站上硬生生断链**,既不再换源也不报错推进。
 * 一旦 [DetailViewModel] 的 `fallbackActive` 为真,说明"去别处取内容"这个决定早已作出
 * (而且入口那一刻已经受过闸门保护),此后只该按候选表往前走。
 */
object FallbackEntryPolicy {

    /**
     * 是否该**否决**本次换源链入口。
     *
     * @param changeable 当前源配置的 `changeable`(`isChangeable()`);true = 允许自动换源
     * @param indexLike 当前源是否按索引型对待(`indexs=1` 或实测"详情缺 list"已达门槛)
     * @param onChain 是否已在换源链上(`fallbackActive`)。已上链时一律放行。
     * @return true = 否决(不启动/不推进换源链)
     */
    fun blocksFallbackEntry(changeable: Boolean, indexLike: Boolean, onChain: Boolean = false): Boolean {
        // 已在链上:去别处取内容的决定已经作出且入口已受保护,这里只该按候选表往前走,
        // 不能因为某个候选站自己是"钉住的播放源"就把整条链断在半路。
        if (onChain) return false
        // 允许换源的普通源:照旧放行。
        if (changeable) return false
        // changeable=0 且**不是**播放源(索引型):它本就没有内容可取,拦住等于让用户白等。
        return !indexLike
    }
}
