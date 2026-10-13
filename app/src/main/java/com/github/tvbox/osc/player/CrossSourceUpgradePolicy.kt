package com.github.tvbox.osc.player

/**
 * 跨源升清判据(纯函数,可 JVM 单测)—— 《源与线路健康度及智能调度系统》"视频 → 统一线路池 →
 * 选最优"理念的**轻量落地**。
 *
 * <p>## 它补的是哪个空白(P0-1)
 * 现有升档 [LineQualitySelector.pickUpgrade] 的候选**被 sourceKey 锁死**:
 * `VideoQualityMemory.lookupAll(vod.sourceKey, vod.id, lineFlags)` 只看得见**当前源**的线路。
 * 于是"源A 最高只有 480P、源B 有 1080P"时系统完全看不到源B ——
 * 跨源能力仅剩 [CrossSourceHintPolicy] 弹一句提示、等用户手点。
 *
 * <p>本判据把 [CrossSourceHintPolicy.shouldHint] 的"要不要提示"升级为
 * **"要不要自动切、切哪个源"** —— 复用同一套 θ/ΔS 口径,不新造第二套阈值。
 *
 * <p>## 为什么不是"重型统一线路池"
 * 建设完整 `LinePool`(聚合所有源的每一集线路、预热解析)要付出成倍的网络与内存成本,
 * 而收益中**最主要**的那部分("升清不能跨源")用一个纯判据就能拿到:
 * 详情页**已经**能零网络查到每个候选源的**最佳实测画质**([VideoQualityMemory.lookupBestBatch]),
 * 只需在这里决定"值不值得切过去"。故本判据只处理**源级粒度**(每源一条最佳线路),
 * 与文档"线路池"的完整形态是"先拿到 80% 收益"的第一步。
 *
 * <p>## 三条硬约束(缺一条就会误伤)
 * <ol>
 *   <li>**只升不降** —— 候选 S 必须显著高于当前(θ + ΔS 双门槛),与源内升档同口径;</li>
 *   <li>**拿不准不切** —— 当前或候选尺寸未知 ⇒ 不切(没数据不猜);</li>
 *   <li>**源内优先** —— 本判据只在"源内已无更高档"时才被调用,绝不越过源内升档
 *       (源内换线代价远小于跨源:不重搜索、不重取详情)。</li>
 * </ol>
 *
 * <p>纯逻辑,无 IO / Android 依赖 —— 由 `CrossSourceUpgradePolicyTest` 锁行为。
 */
object CrossSourceUpgradePolicy {

    /** 值得跨源切的最小清晰度比(AUTO 档),与 [QualityGovernor]/[CrossSourceHintPolicy] 同口径 */
    const val THETA_AUTO = CrossSourceHintPolicy.THETA_AUTO

    /** 画质优先档的比值阈值 */
    const val THETA_QUALITY_FIRST = CrossSourceHintPolicy.THETA_QUALITY_FIRST

    /** 最小清晰度差(等效 S 之差),避免 720→768 这类无感切换 */
    const val MIN_DELTA_S = CrossSourceHintPolicy.MIN_DELTA_S

    /**
     * 一个跨源候选:某候选源在本集的最佳实测画质。
     *
     * @param sourceKey 候选源的 key(切换凭据)
     * @param variant   该源最佳线路的实测画质(flag 为线路 flag)
     */
    data class Candidate(val sourceKey: String, val variant: VideoQualityPolicy.Variant)

    /**
     * 是否**值得**从当前源跨到候选源(只判"值不值得",不判"切哪条")。
     *
     * <p>与 [CrossSourceHintPolicy.shouldHint] 是同一套口径 —— 提示与自动切本就该同门槛,
     * 否则会出现"系统觉得值得提示、但自己不切"或反之的割裂。
     *
     * @param current        当前源本线路的实测画质(null / 未知 ⇒ 不切)
     * @param candidate      候选源最佳线路的实测画质(null / 未知 ⇒ 不切)
     * @param qualityFirst   是否画质优先档(更激进)
     * @param sourceConverged 当前源是否已收敛(源内 sweep 已出结论、不会再自动升档)——
     *                        **未收敛不跨源**:源内还有更高档没试完,跨源是舍近求远
     */
    @JvmStatic
    fun worthCrossSource(
        current: VideoQualityPolicy.Variant?,
        candidate: VideoQualityPolicy.Variant?,
        qualityFirst: Boolean,
        sourceConverged: Boolean,
    ): Boolean {
        if (!sourceConverged) return false
        if (current == null || candidate == null) return false
        if (!current.known || !candidate.known) return false
        // 当前已达天花板(4K 级):任何模式都不再向上追
        if (VideoQualityPolicy.isAtCeiling(current)) return false
        // 自动档下当前已达 1080P 锚:不折腾(与源内 sweep / 跨源提示同口径)
        if (!qualityFirst && VideoQualityPolicy.meetsAnchor(current, VideoQualityPolicy.ANCHOR_WIDTH)) {
            return false
        }
        val sc = VideoQualityPolicy.sharpness(current).toDouble()
        val st = VideoQualityPolicy.sharpness(candidate).toDouble()
        if (sc <= 0.0) return false
        val theta = if (qualityFirst) THETA_QUALITY_FIRST else THETA_AUTO
        return st / sc >= theta && (st - sc) >= MIN_DELTA_S
    }

    /**
     * 从跨源候选里挑出**最值得切**的那一个源。
     *
     * <p>排序口径与 [LineQualitySelector.pickUpgrade] 完全一致:**一次到位取最优**
     * (按 [VideoQualityPolicy.score],即 清晰度S → bpp → 可信度),不逐级。
     * 逐级意味着 N 次跨源重载 = N 次闪屏 + N 次重取详情,代价远高于源内换线。
     *
     * <p>同分保持传入顺序(严格 `>`),不打乱调用方给出的候选优先级
     * (调用方通常已按完整度/权重排过序)。
     *
     * @param candidates     跨源候选列表(已排除当前源与已用过的源)
     * @param current        当前实测画质
     * @param qualityFirst   是否画质优先档
     * @param sourceConverged 当前源是否已收敛
     * @param deviceCapHeight 设备上限(像素高);>0 时超限候选**直接排除**(不是排后面)
     * @return 选中的候选源凭据;不值得切时返回 null
     */
    @JvmStatic
    fun pickCrossSourceUpgrade(
        candidates: List<Candidate>,
        current: VideoQualityPolicy.Variant?,
        qualityFirst: Boolean,
        sourceConverged: Boolean,
        deviceCapHeight: Int,
    ): Candidate? {
        if (candidates.isEmpty()) return null
        var best: Candidate? = null
        var bestScore = Long.MIN_VALUE
        for (c in candidates) {
            val v = c.variant
            // 设备上限:与 VideoQualityPolicy.pickBest 同口径 —— 超限直接排除
            if (deviceCapHeight > 0 && v.known && v.height > deviceCapHeight) continue
            // 逐个套 "值得切" 判据:既保证只升不降,也保证达到 θ/ΔS
            if (!worthCrossSource(current, v, qualityFirst, sourceConverged)) continue
            val s = VideoQualityPolicy.score(v)
            if (s > bestScore) {
                bestScore = s
                best = c
            }
        }
        return best
    }
}