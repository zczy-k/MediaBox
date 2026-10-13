package com.github.tvbox.osc.player

import kotlin.math.sqrt

/**
 * 统一动态质量评分 —— 《源与线路健康度及智能调度系统》§4「动态质量评分模型」的**纯函数落地**(P0-2, 2026-10-13)。
 *
 * <p>## 它补的是哪个空白
 * 现有两处排序各管一半,谁都不全:
 * <ul>
 *   <li>[VideoQualityPolicy.score] 只答"谁更清晰"(清晰度 S + bpp + 可信度),**不看速度/稳定性**;</li>
 *   <li>[LineHealthPolicy.orderScore] 只答"这条线有没有问题"(−100/−50 三档粗粒度),**不看画质</b>。</li>
 * </ul>
 * 结果是"**又快又稳但清晰度中等**"与"**清晰但屡屡卡顿**"无法按场景权衡 —— 而文档主张的正是
 * **分场景动态权重**:起播阶段速度优先、稳态清晰度优先、弱网速度优先。
 *
 * <p>## 设计口径(与项目既有铁律一致)
 * <ol>
 *   <li>**只排序,不决定能不能选** —— 与 [LineHealthPolicy] 的"只降权不屏蔽"同款:</li>
 *   <li>**拿不准不猜** —— 尺寸/耗时未知的维度按中性参与,不因缺数据把候选判死;</li>
 *   <li>**先分档、档内不变** —— 本评分是"外部一层权重",不改 [LineQualitySelector] 内部
 *       (降档三条硬约束、升档一次到位)**已验证的比较规则**;</li>
 *   <li>**性能/成功率不压过画质到"错误方向"** —— 权重把两个维度都归一到 0..1 再加权,
 *       不会出现"480P 因网快而被选中当高清"。</li>
 * </ol>
 *
 * <p>纯逻辑,无 Android 依赖 —— 由 `QualityScoreTest` 锁行为。
 */
object QualityScore {

    /** 播放场景(对标文档 §0.2 的 PlayScene)。 */
    enum class Scene {
        /** 冷启动(首次起播):速度优先,低码率快速出画面 */
        COLD_START,

        /** 稳态播放:缓冲充足,清晰度优先 */
        STEADY,

        /** 弱网:能播就行,速度压倒一切 */
        WEAK_NETWORK,

        /** TV 大屏:带宽足、对画质敏感 */
        TV_BIG_SCREEN,
    }

    /** 网络类型(仅用于 [sceneOf] 推导,不依赖 Android)。 */
    enum class NetKind { WIFI, CELL_5G, CELL_4G, WEAK, UNKNOWN }

    /**
     * 场景权重:清晰度 vs 速度。两值之和恒为 1。
     *
     * <p>取值依据(文档 §1.1 网络×设备推荐值表):WiFi+TV 清晰 0.65(大屏吃画质);
     * 4G 手机速度 0.70(带宽不稳,流畅第一);弱网速度 0.90(能播就行)。
     */
    data class Weights(val quality: Double, val speed: Double) {
        init {
            require(quality in 0.0..1.0 && speed in 0.0..1.0) { "权重必须在 0..1" }
        }
    }

    /** 场景 → 权重表(集中一处,便于真机调参;禁止散落魔法数字)。 */
    fun weightsOf(scene: Scene): Weights = when (scene) {
        // 冷启动:先出画面,清晰度次之(文档 §4.1 "快起播、慢升清")
        Scene.COLD_START -> Weights(0.35, 0.65)
        // 稳态:缓冲充足才进来,清晰度优先
        Scene.STEADY -> Weights(0.60, 0.40)
        // 弱网:速度压倒一切
        Scene.WEAK_NETWORK -> Weights(0.10, 0.90)
        // TV 大屏:带宽足、对画质敏感
        Scene.TV_BIG_SCREEN -> Weights(0.65, 0.35)
    }

    /**
     * 由"网络类型 + 是否 TV + 是否首次起播"推导场景(纯函数)。
     *
     * <p>优先级:弱网 > 冷启动 > TV > 稳态。理由:弱网时不管是不是 TV 都该保流畅;
     * 冷启动时不管屏幕多大都该先出画面。
     */
    fun sceneOf(net: NetKind, isTv: Boolean, coldStart: Boolean): Scene = when {
        net == NetKind.WEAK -> Scene.WEAK_NETWORK
        coldStart -> Scene.COLD_START
        isTv -> Scene.TV_BIG_SCREEN
        else -> Scene.STEADY
    }

    /**
     * 综合评分(越大越优先)。三个维度都归一到 0..1:
     * <ul>
     *   <li>**清晰度** = √(W×H) / [REF_SHARPNESS](基准 1080P 级,超过则截顶为 1);</li>
     *   <li>**速度** = 由取流耗时 [resolveMs] 与实测码率共同决定(越快越高);</li>
     *   <li>**稳定性** = 由历史成功率推导([failStreak] 越大越低)。</li>
     * </ul>
     *
     * @param variant   候选线路的实测画质(尺寸未知时清晰度维度按 0,与"没数据不优先"一致)
     * @param resolveMs 取流耗时(ms);&lt;=0 = 未知,按中性 [NEUTRAL_SAMPLE] 参与
     * @param failStreak 该线路当前连续失败次数(0 = 健康)
     * @param scene     播放场景(权重来源)
     * @param deviceCapHeight 设备上限(像素高);&gt;0 且候选超出时返回 [REJECTED]
     */
    fun compute(
        variant: VideoQualityPolicy.Variant?,
        resolveMs: Long,
        failStreak: Int,
        scene: Scene,
        deviceCapHeight: Int = 0,
    ): Double {
        val w = weightsOf(scene)
        // 设备上限:与 VideoQualityPolicy.pickBest 同口径 —— 超限直接判死(不是排后面)
        if (deviceCapHeight > 0 && variant != null && variant.known && variant.height > deviceCapHeight) {
            return REJECTED
        }
        val quality = qualityRatio(variant)
        val speed = speedRatio(variant, resolveMs)
        val stability = stabilityRatio(failStreak)
        // 结构:清晰度与速度按场景加权(占主导),稳定性作为**乘法**折扣(不稳定整体贬值)
        val base = w.quality * quality + w.speed * speed
        return base * (STABILITY_FLOOR + (1 - STABILITY_FLOOR) * stability)
    }

    /** 被设备上限排除时的哨兵分(小于任何合法的 0..1 分)。 */
    const val REJECTED = -1.0

    /** 清晰度参考基数:1080P 级等效 S ≈ 1440。达到即得满分,超出仍为 1(不无限加分)。 */
    const val REF_SHARPNESS = 1440.0

    /** 速度采样缺失时的中性值(不过度奖励也不惩罚) */
    const val NEUTRAL_SAMPLE = 0.5

    /** 取流耗时达到此值即速度维度得 0 分(与 LineHealthPolicy.SLOW_LINE_MS 同口径) */
    const val SLOW_RESOLVE_MS = 5_000.0

    /** 取流耗时低于此值即速度维度得满分 */
    const val FAST_RESOLVE_MS = 1_000.0

    /** 稳定性折扣下限:即便最不稳也保底保留 60% 的分(避免"一票否决"式排序) */
    const val STABILITY_FLOOR = 0.6

    /** 连续失败到此次数即稳定性归零 */
    const val STABILITY_ZERO_STREAK = 3

    private fun qualityRatio(v: VideoQualityPolicy.Variant?): Double {
        if (v == null || !v.known) return 0.0
        val s = sqrt(v.width.toDouble() * v.height.toDouble())
        return (s / REF_SHARPNESS).coerceIn(0.0, 1.0)
    }

    /**
     * 速度维度:以取流耗时为主(越快越高)。
     *
     * <p>⚠️ 耗时的"未知"与"极快"必须分开:**`&lt; 0` 才是未知**(按中性 0.5 参与),
     * **`0` 是极快**(得满分)。此前把 `&lt;= 0` 一律当中性,会让"真·0ms"的实测线路被误判为"没数据",
     * 在速度优先场景下反而输给未知线路 —— 真机里 resolveMs 是从发起取流到结果被采纳的实测值,
     * 0 只可能来自"同一拍内立刻返回",那是快而非未知。
     */
    private fun speedRatio(v: VideoQualityPolicy.Variant?, resolveMs: Long): Double {
        if (resolveMs < 0L) return NEUTRAL_SAMPLE
        val latency = if (resolveMs <= FAST_RESOLVE_MS) {
            1.0
        } else if (resolveMs >= SLOW_RESOLVE_MS) {
            0.0
        } else {
            // 线性插值:1s→1.0,5s→0.0
            1.0 - (resolveMs - FAST_RESOLVE_MS) / (SLOW_RESOLVE_MS - FAST_RESOLVE_MS)
        }
        return latency
    }

    private fun stabilityRatio(failStreak: Int): Double {
        if (failStreak <= 0) return 1.0
        if (failStreak >= STABILITY_ZERO_STREAK) return 0.0
        return 1.0 - failStreak.toDouble() / STABILITY_ZERO_STREAK
    }
}