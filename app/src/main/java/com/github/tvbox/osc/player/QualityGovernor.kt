package com.github.tvbox.osc.player

/**
 * 自适应画质调度器(2026-10-10 第一批):**纯逻辑**,所有状态显式传参,可 JVM 单测。
 *
 * <p>设计(《自适应画质统一方案》):降档链(看门狗→tryDowngradeLine→tryNextLine→换源)保持不动,
 * 本对象只承载**新增的向上路径**的门控——
 * <ul>
 *   <li>升档门槛:网络富余(看门狗采样) + 存在更高实测档 + 用户画质档位门控 + 稳定期 + 额度 + 会话锁;</li>
 *   <li>升档失败回滚窗口:升档后 90s 内出现失败/判劣质 → 先回出发地(已验证可播),不走通用链漫游;</li>
 *   <li>防振荡:回滚即锁本会话,不再升档。</li>
 * </ul>
 *
 * <p>为什么档内修饰而不是 0-100 加权:与搜索相关度同一取舍——真实样本个位数,档位可解释、可测。
 */
object QualityGovernor {

    /** 档位阶梯:480/720/1080/1440/2160+,与 [DeviceCapability.LADDER] 同构 */
    fun tierOf(height: Int): Int = when {
        height <= 0 -> -1
        height <= 480 -> 0
        height <= 720 -> 1
        height <= 1080 -> 2
        height <= 1440 -> 3
        else -> 4
    }

    /** 稳定期:进集不足此时长不升档(起播振荡期,采样信号不可信) */
    const val MIN_WATCH_MS = 60_000L

    /** 升档失败回滚窗口:窗口内的失败/判劣质先回出发地 */
    const val ROLLBACK_WINDOW_MS = 90_000L

    /** 每集自动升档额度 */
    const val MAX_UPGRADES_PER_EPISODE = 2

    /**
     * 升档门槛(全部满足才允许)。
     *
     * @param mode              用户画质档位:SPEED_FIRST 永不升;AUTO 仅 ≥2 档差;QUALITY_FIRST ≥1 档
     * @param currentHeight     当前线路**实测**高度(≤0 = 未知,不升——没数据不猜)
     * @param targetHeight      候选线路实测高度
     * @param sinceEpisodeStartMs 进集时长;-1 = 未知(起播标记未到达,不升)
     * @param upgradesDone      本集已升档次数
     * @param sessionLockedTier 会话锁定档(回滚后=出发地档;高于它的目标一律拒绝;-1 = 未锁)
     */
    fun canUpgrade(
        mode: DeviceCapability.QualityMode,
        currentHeight: Int,
        targetHeight: Int,
        sinceEpisodeStartMs: Long,
        upgradesDone: Int,
        sessionLockedTier: Int,
    ): Boolean {
        if (currentHeight <= 0 || targetHeight <= 0) return false
        val currentTier = tierOf(currentHeight)
        val targetTier = tierOf(targetHeight)
        if (targetTier <= currentTier) return false
        if (mode == DeviceCapability.QualityMode.SPEED_FIRST) return false
        val gain = targetTier - currentTier
        if (mode == DeviceCapability.QualityMode.AUTO && gain < 2) return false
        if (sinceEpisodeStartMs < 0 || sinceEpisodeStartMs < MIN_WATCH_MS) return false
        if (upgradesDone >= MAX_UPGRADES_PER_EPISODE) return false
        if (sessionLockedTier >= 0 && targetTier > sessionLockedTier) return false
        return true
    }

    /** 升档失败是否仍在回滚窗口内(出发地快照为空 = 无升档在途,不在窗口) */
    fun inRollbackWindow(nowElapsed: Long, upgradedAtElapsed: Long): Boolean =
        upgradedAtElapsed > 0L && nowElapsed - upgradedAtElapsed <= ROLLBACK_WINDOW_MS
}
