package com.github.tvbox.osc.util

/**
 * 「这是不是同一部片」的判据(2026-10-16,纯函数,可 JVM 单测)。
 *
 * <p>## 补的是哪个洞
 * 真机 v1.1.49:用户从首页点进《希望》,换源列表里有一条源的卡片其实叫《希望一次过》
 * —— **另一部电影**。切过去之后详情正常返回、播放器进了播放态、`echo-firstframe` 打了、
 * 一次错误回调都没有 ⇒ 系统拿到的事实是"这部片播成功了"。于是不换源、还给这个源记了一次
 * `PLAY_SUCCESS` 权重分、并撤销它已有的 🟡。
 * 根因:搜索那一路的匹配模式语义是**找片**(宽松才捞得到别名/译名/变体写法),
 * 换源这一路要的却是**同一部片的其他源** —— 宽一寸就是另一部电影。
 * 所以这里不是把搜索判据改严,而是给"是不是本部片"单独一个判据。
 *
 * <p>## 三档,以及为什么"拿不准 = 放行"
 * - [Verdict.SAME] 归一化后相等;或剥掉年份与季/集/版本后缀([SearchSettings.coreTitle],
 *   与搜索的"去年份+去后缀"变体**同一把刀**)后相等 —— 「希望 HD」「希望2023」「希望更新至20集」都算本部片。
 * - [Verdict.DIFFERENT] **只在**"一方是另一方前缀、剥完修饰后核心仍不同"时成立:
 *   《希望一次过》《希望之怒》《希望2》就是这种 —— 同前缀、后面接的是片名正文或续集编号。
 * - [Verdict.UNKNOWN] 互不为前缀(源站用英文名/别名/繁简体写法)⇒ **判不了**。
 *
 * ⚠️ UNKNOWN 一律放行,既不拦也不记失败:错杀一条源 = 让用户少一个能看的来源,
 * 比放过一部错片更糟(与 [com.github.tvbox.osc.player.LineHealthPolicy] 的"宁漏不误杀"同口径)。
 * 判据保守地收在"只认前缀扩展"这一条形状上:它覆盖真机这种"同前缀续写片名"的主流坑,
 * 又不碰别名/译名那一整片无法静态判定的区域。
 */
object PlayTitleMatch {

    enum class Verdict { SAME, DIFFERENT, UNKNOWN }

    /**
     * `coreTitle` 之后仍可能挂在片名后面的**版本/画质词**。
     *
     * <p>为什么不塞进 [SearchSettings] 那把刀:那把刀服务"搜索变体"(去年份、去季集),
     * 「正片/无删减」这类词拿去**改查询串**没有意义、还会动召回;
     * 但对"是不是同一部片"它们是纯噪音。两处需求不同,所以只在判据这一侧补,
     * 年份与季/集/季数仍然复用同一把刀 —— 那部分两边口径必须一致。
     */
    private val VERSION_WORDS = Regex(
        "正片|完整版|未删减|无删减|独家|中字|简中|繁中|双语|国语|粤语|原声|高清|超清|标清|蓝光|高清版|视频|在线|免费"
    )

    private fun coreOf(title: String?): String =
        VERSION_WORDS.replace(SearchSettings.coreTitle(title), "").trim()

    /**
     * @param playingTitle 当前这条内容自己的标题(候选卡片名 / 详情回包的 name)
     * @param filmTitle 用户点进来的那部片的标题(整场固定,见 `DetailViewModel.entryTitle`)
     */
    fun verdict(playingTitle: String?, filmTitle: String?): Verdict {
        val playing = SearchSettings.normalizeCached(playingTitle)
        val film = SearchSettings.normalizeCached(filmTitle)
        if (playing.isEmpty() || film.isEmpty()) return Verdict.UNKNOWN
        if (playing == film) return Verdict.SAME
        // 剥完年份/季集/版本词还相等 ⇒ 同一部,只是站方写法不同
        val corePlaying = coreOf(playingTitle)
        val coreFilm = coreOf(filmTitle)
        if (corePlaying.isNotEmpty() && corePlaying == coreFilm) return Verdict.SAME
        // 不是前缀关系(译名/别名/系列名/繁简差异)⇒ 判不了,放行
        if (!(playing.startsWith(film) || film.startsWith(playing))) return Verdict.UNKNOWN
        return Verdict.DIFFERENT
    }

    /** 只在"明确是另一部片"时才动手;UNKNOWN/SAME 都算可以播 */
    fun isDifferentFilm(playingTitle: String?, filmTitle: String?): Boolean =
        verdict(playingTitle, filmTitle) == Verdict.DIFFERENT
}
