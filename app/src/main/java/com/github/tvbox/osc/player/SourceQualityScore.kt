package com.github.tvbox.osc.player

/**
 * **源级评分**(纯函数,可 JVM 单测,2026-10-14) —— 换源候选排序的判据。
 *
 * <p>## 它回答的问题(与线路级评分的分工)
 * [QualityScore] 是**线路级**(某条线现在好不好用);本类是**源级**(这个源整体靠不靠谱)。
 * 用户的核心痛点:某片在当前源里所有线路画质都差,想换源,但**几十上百个匿名候选源里不知道挑哪个**。
 * 本判据把各源的**历史表现**聚合成一个分,让"历史更靠谱的源"排前面。
 *
 * <p>## 边界(与《源与线路评分系统设计》一致)
 * 这是**基于历史的概率预测**,不是对当前影片的实测:
 * <ul>
 *   <li>只回答"该源历史靠不靠谱"(跨片可复用 ⇒ **首次播放也有效**);</li>
 *   <li>**不能**回答"这部片在该源多少清晰度"(那需要跨源探测,已禁);</li>
 *   <li>用途是**排序**,**不是保证**。</li>
 * </ul>
 *
 * <p>## 为什么"无数据 → 中性"而不是"无数据 → 低分"
 * 新源/没播过的源**不是坏源**,只是**没证据**。若把它们判低分,等于用"不知道"去惩罚一个可能很好的源
 * (与项目"拿不准不降权"铁律冲突)。故无记录一律按 [NEUTRAL] 参与,并列时回退到调用方的原序。
 *
 * <p>## 明确不做(见设计文档 §6)
 * 不纳入卡顿率(归因不可靠、易误伤)、不做动态权重(双重收缩)、不新增网络/存储。
 *
 * <p>纯逻辑,无 IO / Android 依赖 —— 由 `SourceQualityScoreTest` 锁行为。
 */
object SourceQualityScore {

    /** 中性分(0..100),与 [com.github.tvbox.osc.util.SourceWeightPolicy.NEUTRAL] 同口径。 */
    const val NEUTRAL = 60.0

    /** 源属性分内部:表现分(源权重) 与 完整度分 的配比。 */
    private const val W_WEIGHT = 0.5
    private const val W_COMPLETENESS = 0.5

    /** 线路聚合:上限优先 —— 换源要的是"能不能给我更好的",上限比均值重要。 */
    private const val W_LINE_MAX = 0.6
    private const val W_LINE_AVG = 0.4

    /** 源分中"线路分"的占比(仅当该源对该片**有线路线索**时才生效;否则纯用属性分)。 */
    private const val W_LINE_IN_SOURCE = 0.5

    /** 被"降权"(非封禁)时的折扣;封禁则直接 0。 */
    const val PENALIZED_FACTOR = 0.5

    /**
     * 完整度归一基准。
     *
     * <p>取 [SourceCompletenessPolicy.rankScore] 的实际量程(1000=备用 … 3000=完整)。
     * 归一后 1500(未知)→ 50 分,与"中性偏低"的语义一致(未知信息不如"已知完整")。
     */
    const val COMPLETENESS_RANK_MAX = 3000.0

    /**
     * 一条源的全部输入信号。
     *
     * <p>**由调用方从各台账读出**(本类不碰 IO),保证纯函数可测。
     *
     * @param weightScore      该源的表现分([com.github.tvbox.osc.util.SourceWeightMemory.scoreOf],0..100);
     *                         **-1 = 无记录** ⇒ 按 [NEUTRAL]
     * @param completenessRank 该源对该片的完整度分([SourceCompletenessPolicy.rankScore],1000..3000+);
     *                         **-1 = 无记录** ⇒ 按 [NEUTRAL]
     * @param penalized        是否被降权([com.github.tvbox.osc.util.SourceHealthFilter.penalizedKeys])
     * @param blocked          是否被封禁(正常情况下候选池已过滤封禁源;此处作**防御**)
     * @param lineScores       该源对该片的**线路分**列表([QualityScore.compute] 输出,0..1);
     *                         空 = 无线索(多数候选源的真实情况)
     */
    data class Signals(
        val weightScore: Int = -1,
        val completenessRank: Int = -1,
        val penalized: Boolean = false,
        val blocked: Boolean = false,
        val lineScores: List<Double> = emptyList(),
    )

    /**
     * 源级综合评分(0..100,越大越优先)。合成顺序:
     * <ol>
     *   <li>**属性分** = 0.5·表现分 + 0.5·完整度分(跨片信号,首次播放也有效);</li>
     *   <li>**有线路线索**时与线路聚合分对半混合;无线索则纯用属性分;</li>
     *   <li>**健康惩罚**:封禁→0;降权→×[PENALIZED_FACTOR];</li>
     * </ol>
     *
     * <p>不做"连续置信度收缩":样本量在本项目不易精确获得,强行引入只会制造不可解释的双重收缩
     * (见设计文档 §3.3/§6)。取而代之:**无记录 → 中性**,效果等价且简单。
     */
    fun score(s: Signals): Double {
        val attr = attributeScore(s.weightScore, s.completenessRank)
        val line = lineAggregate(s.lineScores)
        val raw = if (line != null) {
            W_LINE_IN_SOURCE * line + (1 - W_LINE_IN_SOURCE) * attr
        } else {
            attr
        }
        val health = when {
            s.blocked -> 0.0
            s.penalized -> raw * PENALIZED_FACTOR
            else -> raw
        }
        return health.coerceIn(0.0, 100.0)
    }

    /**
     * 源属性分(跨片信号)。
     *
     * @param weightScore      表现分;范围外(含 -1)⇒ [NEUTRAL]
     * @param completenessRank 完整度分;负值 ⇒ [NEUTRAL];否则按 [COMPLETENESS_RANK_MAX] 归一
     */
    fun attributeScore(weightScore: Int, completenessRank: Int): Double {
        val weight = if (weightScore in 0..100) weightScore.toDouble() else NEUTRAL
        val comp = if (completenessRank >= 0) {
            (completenessRank / COMPLETENESS_RANK_MAX * 100.0).coerceIn(0.0, 100.0)
        } else {
            NEUTRAL
        }
        return W_WEIGHT * weight + W_COMPLETENESS * comp
    }

    /**
     * 线路分聚合(上限优先)。输入是 [QualityScore.compute] 的 0..1 输出。
     *
     * @return 0..100 的聚合分;列表为空/全为 [QualityScore.REJECTED] 时返回 null(= 无线索)
     */
    fun lineAggregate(lineScores: List<Double>): Double? {
        // 排除 REJECTED(<0):被判死的线路不该参与"上限/均值"
        val valid = lineScores.filter { it >= 0.0 }
        if (valid.isEmpty()) return null
        val maxValue = valid.max()
        val avgValue = valid.average()
        return (W_LINE_MAX * maxValue + W_LINE_AVG * avgValue) * 100.0
    }
}