package com.github.tvbox.osc.player

/**
 * 跨源画质提示的纯判据(零副作用,可单测)。
 *
 * <p>只回答一个问题:**当前源已收敛、且对端有实测记忆明显更好时,要不要给用户一句提示**。
 * 不做任何切换、不拉详情;点击后的换源由 ViewModel 走既有 switchSource 链路。
 */
object CrossSourceHintPolicy {

    /** 值得切的最小清晰度比(AUTO 档),与 [QualityGovernor] 的 θ 同口径 */
    const val THETA_AUTO = 1.08

    /** 画质优先档的比值阈值(更激进地追高) */
    const val THETA_QUALITY_FIRST = 1.03

    /** 最小清晰度差(像素级 S 之差),避免 720→768 这类骚扰式提示 */
    const val MIN_DELTA_S = 32

    /**
     * 是否应提示。
     *
     * @param current 当前源本线路的实测值(可为 null = 未知 ⇒ 不提示)
     * @param candidate 对端的最佳实测值(可为 null ⇒ 不提示)
     * @param qualityFirst 是否为画质优先档
     * @param sourceConverged 当前源是否已收敛(sweep 已出结论,不会再自动升档)
     */
    @JvmStatic
    fun shouldHint(
        current: VideoQualityPolicy.Variant?,
        candidate: VideoQualityPolicy.Variant?,
        qualityFirst: Boolean,
        sourceConverged: Boolean,
    ): Boolean {
        if (!sourceConverged) return false
        if (current == null || candidate == null) return false
        if (!current.known || !candidate.known) return false

        // 当前已达 1080P 锚且非画质优先:不折腾,与源内 sweep 同一口径
        if (!qualityFirst && VideoQualityPolicy.meetsAnchor(current, VideoQualityPolicy.ANCHOR_WIDTH)) {
            return false
        }
        // 当前已到天花板(4K 级):任何模式都不再向上提示
        if (VideoQualityPolicy.isAtCeiling(current)) return false

        val sc = VideoQualityPolicy.sharpness(current).toDouble()
        val st = VideoQualityPolicy.sharpness(candidate).toDouble()
        if (sc <= 0.0) return false
        val theta = if (qualityFirst) THETA_QUALITY_FIRST else THETA_AUTO
        return st / sc >= theta && (st - sc) >= MIN_DELTA_S
    }
}
