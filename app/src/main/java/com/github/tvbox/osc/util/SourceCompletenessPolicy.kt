package com.github.tvbox.osc.util

/**
 * 影视源"完整度"判定的**纯函数规则**(P-完整性方案,2026-10-09;无 Android 依赖,可 JVM 单测)。
 *
 * <p>## 分层设计
 * <ul>
 *   <li>**权威集数**([CompletenessMemory]):这部片"应该有多少集"的参照系,跨源共享;</li>
 *   <li>**先验集数**([priorCountFromNote]):候选详情未加载时,从源站自述(note"更新至30集")
 *       零成本解析的参考值 —— 只做**负面预筛**(明显落后者提前降位),不参与权威抬升;</li>
 *   <li>**档位与排序分**([tier]/[rankScore]):换源链据此决定候选优先级。</li>
 * </ul>
 *
 * <p>## 设计哲学(与可用性粗筛、同名聚合一致)
 * **宁漏并不误并、拿不准不降权**:权威未知 → 不判定;先验未知 → 中性分;
 * 判定只在"双方都确知"时发生。降权只改排序不屏蔽 —— 完整源全失败时备用源仍可顶上。
 */
object SourceCompletenessPolicy {

    enum class Tier {
        /** 实绩/先验 ≥ 权威:完整 */
        COMPLETE,
        /** ≥80% 且 <100%:轻微落后 */
        NEAR,
        /** <80%:严重不全,只作备用 */
        BACKUP,
        /** 权威已知但该源先验未知:中性,不降权 */
        UNKNOWN_SOURCE,
        /** 权威未知:整体不判,排序保持原序 */
        UNKNOWN_AUTHORITY,
    }

    /**
     * 从源站自述解析先验集数,解析不出返回 0。
     * 只认"描述总量"的措辞(更新至N集/共N集/N集全|完结);**不认**"第N集" —— 那是单集序号不是总量。
     */
    fun priorCountFromNote(note: String?): Int {
        val text = note?.trim().orEmpty()
        if (text.isEmpty()) return 0
        val patterns = listOf(
            Regex("更新至\\s*(\\d{1,4})\\s*集"),
            Regex("共\\s*(\\d{1,4})\\s*集"),
            Regex("(\\d{1,4})\\s*集\\s*(?:全|完结|完)"),
        )
        for (pattern in patterns) {
            val m = pattern.find(text) ?: continue
            val v = m.groupValues[1].toIntOrNull() ?: continue
            if (v > 0) return v.coerceAtMost(CompletenessMemory.AUTHORITY_MAX)
        }
        return 0
    }

    /**
     * 完整度档位。判定只发生在"权威与先验都确知"时:
     * 先验 ≥ 权威 → 完整;≥80% → 轻微落后;否则 → 严重不全(备用)。
     */
    fun tier(prior: Int, authority: Int): Tier = when {
        authority <= 0 -> Tier.UNKNOWN_AUTHORITY
        prior <= 0 -> Tier.UNKNOWN_SOURCE
        prior >= authority -> Tier.COMPLETE
        prior * 10 >= authority * 8 -> Tier.NEAR
        else -> Tier.BACKUP
    }

    /**
     * 线路缺失区间(P-完整性第三批,选集面板标注用):该线路可数集数 [count] 落后权威
     * [authority] 时返回缺失区间 (count+1 .. authority);不落后/不可数/权威未知返回 null。
     * 单集缺失返回 (N, N),由调用方决定文案形态。
     */
    fun missingRange(count: Int, authority: Int): Pair<Int, Int>? {
        if (authority <= 0 || count <= 0 || count >= authority) return null
        return (count + 1) to authority
    }

    /**
     * 换源链排序分(越大越优先)。四段含义:
     * <ul>
     *   <li>完整(3000+):最优先;同档内集数多者优先 —— "更新越快权重越高";</li>
     *   <li>轻微落后(2000+):已知有大部分集数,优于未知;</li>
     *   <li>未知(1500):中性 —— 权威未知时不降权(保持到达序),权威已知但先验未知时
     *       排在已知完整/落后之后(已知信息优于未知赌博);</li>
     *   <li>严重不全(1000+):备用,仅在完整/轻微/未知源全部失败后才尝试。</li>
     * </ul>
     */
    fun rankScore(tier: Tier, prior: Int): Int = when (tier) {
        Tier.COMPLETE -> 3000 + prior.coerceAtMost(CompletenessMemory.AUTHORITY_MAX)
        Tier.NEAR -> 2000 + prior
        Tier.UNKNOWN_SOURCE, Tier.UNKNOWN_AUTHORITY -> 1500
        Tier.BACKUP -> 1000 + prior
    }
}
