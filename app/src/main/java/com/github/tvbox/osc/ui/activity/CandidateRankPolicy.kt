package com.github.tvbox.osc.ui.activity

/**
 * **换源候选排序判据**(纯函数,可 JVM 单测,2026-10-14)。
 *
 * <p>## 它要解决的现场
 * 旧的候选排序第一键是"总覆盖率档位"(完整/轻微/未知/严重不全),那是**整部剧**的口径;
 * 可用户点换源的那一刻,真正丢不起的是**手上这一集以及往后能不能连着看**。
 * 两者会给出相反的结论:
 * <pre>
 *   一部 50 集的剧,用户看到第 5 集:
 *     源A 只更到 20 集 → 总覆盖 40%(旧判据判"严重不全"压到队尾),但此刻完全可用(往后还有 15 集)。
 *     源B 缺第 5 集   → 总覆盖 98%(旧判据判"轻微落后"排在前面),切过去正好卡在手上这一集 —— 等于没资源。
 * </pre>
 * 本判据把"此刻能不能看"提到最前,把总覆盖率降为同档内的次级信息。
 *
 * <p>## 三条硬约束
 * <ul>
 *   <li>**只有证据够硬才判"不可用"**:缺集断言要求该集号已被 ≥2 个独立源印证([confirmed]),
 *       单源自述或我方一次观测都不作数 —— 与 EpisodeSetMath/CompletenessMemory 既有纪律一致;</li>
 *   <li>**数据不足一律中性**,绝不因"还不知道"就沉底(否则新源/电影类会被系统性压死);</li>
 *   <li>**只降权不剔除**:不可用的源拿到最低分沉在队尾,前面的候选全部放不出来时仍会被试到,
 *       且用户切到别的集号后它会自动浮回来(每次取候选前都会重算)。</li>
 * </ul>
 *
 * <p>## 电影/单集为什么天然不受影响
 * 权威集数集合为空(`confirmed` 空)或当前集号不可数 ⇒ 全部候选同分,排序直接落到第二键(源权重)。
 */
object CandidateRankPolicy {

    /** 可连着看到权威末尾(4000+):最高优先,同档内按已覆盖集数微排 */
    private const val BASE_FULL_RUN = 4000

    /** 往后能连续一段但没到末尾(3000+run) */
    private const val BASE_PARTIAL_RUN = 3000

    /** 无逐集集合、但站方自述"更新至 N 集"且 N ≥ 当前集(2000+余量):大概率可用 */
    private const val BASE_PRIER_OK = 2000

    /** 完全没有集数信息:中性,不降权(电影/单集/新源都走这里) */
    const val SCORE_UNKNOWN = 1500

    /** 明确缺当前这一集:沉底(不删,理由见类注释) */
    const val SCORE_MISSING_CURRENT = 0

    /** 单源自述的余量上限:防止"更新至 9999 集"这类脏数据把分档撑破 */
    private const val PRIOR_MARGIN_CAP = 500

    /**
     * 从 [currentEpisode] 起、在 [own] 里能连续拿到的集数(含当前集本身)。
     * 集号不可数 / 该源没有观测数据 ⇒ -1(不判)。
     */
    @JvmStatic
    fun runFrom(own: Set<Int>, currentEpisode: Int): Int {
        if (currentEpisode <= 0 || own.isEmpty()) return -1
        var count = 0
        var episode = currentEpisode
        while (own.contains(episode)) {
            count++
            episode++
        }
        return count
    }

    /**
     * **此刻不可用**判据:该源明确拿不到用户手上这一集。
     *
     * <p>两个来源任一成立即算:
     * ①有逐集观测集合,且权威确认存在该集、该源却没有它;
     * ②只有站方自述"更新至 N 集",而 N 已经小于当前集号(自述是上限,不必要求权威印证 ——
     *   "更新至 20 集"本身就排除了第 25 集)。
     */
    @JvmStatic
    fun unavailableNow(
        own: Set<Int>?,
        priorCount: Int,
        confirmed: Set<Int>,
        currentEpisode: Int,
    ): Boolean {
        if (currentEpisode <= 0) return false
        if (own != null && own.isNotEmpty()) {
            return confirmed.contains(currentEpisode) && !own.contains(currentEpisode)
        }
        return priorCount > 0 && priorCount < currentEpisode
    }

    /**
     * 第一键:可看性分(越大越优先)。
     *
     * @param own            该源**实测观测到**的正片集号集合;无数据传 null
     * @param priorCount     站方自述集数(`更新至N集`/`共N集全`),解析不出传 0
     * @param confirmed      已跨源印证的可信集号集合(权威)
     * @param currentEpisode 用户当前集号;不可数传 0(此时一切判断退化为中性)
     */
    @JvmStatic
    fun watchabilityRank(
        own: Set<Int>?,
        priorCount: Int,
        confirmed: Set<Int>,
        currentEpisode: Int,
    ): Int {
        if (currentEpisode <= 0 || confirmed.isEmpty()) return SCORE_UNKNOWN
        if (unavailableNow(own, priorCount, confirmed, currentEpisode)) return SCORE_MISSING_CURRENT
        if (own != null && own.isNotEmpty()) {
            val run = runFrom(own, currentEpisode)
            if (run > 0) {
                val remaining = confirmed.count { it >= currentEpisode }
                // 一直连续到权威末尾 ⇒ 这部片在该源"从头到尾不缺",给最高档
                return if (run >= remaining) BASE_FULL_RUN + run else BASE_PARTIAL_RUN + run
            }
            // 有集合但缺当前集的判定已在 unavailableNow 拦掉;走到这里说明集合不含当前集
            // 而权威也不含该集(不可数/花絮编号错位)⇒ 中性,不冤枉它。
            return SCORE_UNKNOWN
        }
        if (priorCount >= currentEpisode) {
            return BASE_PRIER_OK + (priorCount - currentEpisode).coerceAtMost(PRIOR_MARGIN_CAP)
        }
        return SCORE_UNKNOWN
    }
}
