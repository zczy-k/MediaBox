package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.player.LineHealthPolicy

/**
 * 线路标签的**显示**口径:序号 + 实测画质。
 *
 * <p>为什么不直接用站点的 flag 名:那是站点自报的字符串(常见"1080P""蓝光""高清"),
 * 与真实画质没有必然关系;更糟的是有些 flag 直接带站名/域名,连源身份一起泄露。
 * 而我们其实**有实测值**(见 `VideoQualityMemory`),拿真数据去顶掉假标签才是正解。
 *
 * <p>为什么"没测到就不显示画质":宁缺勿假。没有实测值的线路只给序号,用户看到"线路3"
 * 就知道这条还没数据,而不会误以为它真是某个清晰度。
 *
 * <p>纯逻辑,可直接 JVM 单测。
 */
internal object LineLabelPolicy {

    /** 标准清晰度档位(从高到低)。实测高度**向下取档**,保证只低报不高报。 */
    private val TIERS = intArrayOf(2160, 1440, 1080, 720, 576, 480, 360, 240)

    /**
     * 实测高度 → 画质后缀。
     *
     * @param measuredHeight 该线路的实测高度(像素);<=0 表示没测到
     * @return 如 "1080P";没测到时返回空串(调用方只显示线路序号)
     */
    fun qualitySuffix(measuredHeight: Int): String {
        if (measuredHeight <= 0) return ""
        for (tier in TIERS) {
            if (measuredHeight >= tier) return "${tier}P"
        }
        // 低于最低档(竖屏短视频类):照实报,不硬套档位
        return "${measuredHeight}P"
    }

    /**
     * 线路的**健康标签**(2026-10-14 追加式设计)—— 在实测画质**之后**再补一个词。
     *
     * <p>## 为什么是"追加"而不是"替换画质"
     * 画质是**无歧义的事实**(用户能直接感知清不清晰);把它换成"综合评分"是**信息降级**,
     * 且会引发"这条评分不低为何不自动切"的困惑(受"只降不升"铁律约束,系统本就不能升)。
     * 故保留画质,只在**有明确健康信号**时追加一个词 —— 与既有"缺集"后缀同一模式。
     *
     * <p>## 判据(宁缺勿假:拿不准就不标)
     * <ul>
     *   <li>[LineHealthPolicy.SUSPECT_STREAK](2)次连续失败 ⇒ "不稳定";</li>
     *   <li>最近取流耗时 ≥ [LineHealthPolicy.SLOW_LINE_MS](5s) ⇒ "起播慢";</li>
     *   <li>成功起播过且无上述问题 ⇒ "稳定";</li>
     *   <li>**没数据 ⇒ 空串**(只显示序号+画质,不编)。</li>
     * </ul>
     *
     * <p>稳定性优先于速度(不稳比慢更严重);只报一个最关键的词,避免标签过长。
     */
    fun healthSuffix(failStreak: Int, resolveMs: Long, playedAt: Long): String = when {
        failStreak >= LineHealthPolicy.SUSPECT_STREAK -> UNSTABLE
        resolveMs >= LineHealthPolicy.SLOW_LINE_MS -> SLOW
        playedAt > 0L -> STABLE
        else -> ""
    }

    /** 健康标签文案(常量集中,便于 i18n/调参) */
    const val UNSTABLE = "不稳定"
    const val SLOW = "起播慢"
    const val STABLE = "稳定"
}
