package com.github.tvbox.osc.player

/**
 * 画质切换调度器:**纯逻辑**,所有状态显式传参,可 JVM 单测。
 *
 * <p>判据口径在 2026-10-10 晚做过一次整体替换(见《选线机制设计》附录 A / 附录 D):
 * **删除了按高度"向上取整"的档位阶梯 `tierOf`**,改用等效线性清晰度 `S = √(W×H)` 的**相对**比较。
 * 原因(真机取证):旧口径把 1280×534 与 1728×720 判成同一档 ⇒ 任何模式都升不上去,
 * 整天零升档;而 480→534 却被算成 +1 档,自相矛盾。
 *
 * <p>现在的门控链(按顺序短路):
 * <ol>
 *   <li>流量节省开启 ⇒ 一切向上动作拒绝(口径见附录 F);</li>
 *   <li>当前/目标都必须有**实测**尺寸(没数据不猜);</li>
 *   <li>目标不得超设备上限(超了直接拒,不是排后面 —— 选中后才失败会白等一次起播超时);</li>
 *   <li>当前已达 4K 级 ⇒ **达顶即停**,不再无限追高;</li>
 *   <li>"值得切"([worthSwitching]):S 比值 ≥θ 且 ΔS ≥[MIN_DELTA_S],或同清晰度下 bpp 显著更高;</li>
 *   <li>稳定期 ≥[MIN_WATCH_MS]、本集额度 <[MAX_UPGRADES_PER_EPISODE]、未触会话锁。</li>
 * </ol>
 */
object QualityGovernor {

    /** 稳定期:进集不足此时长不切(起播振荡期,采样信号不可信) */
    const val MIN_WATCH_MS = 60_000L

    /** 切换失败回滚窗口:窗口内的失败/判劣质先回出发地 */
    const val ROLLBACK_WINDOW_MS = 90_000L

    /** 每集自动切换额度 */
    const val MAX_UPGRADES_PER_EPISODE = 2

    /** 相对提升门槛 θ:自动档保守(防振荡),画质优先激进(愿意为小提升切一次) */
    const val THETA_AUTO = 1.08
    const val THETA_QUALITY_FIRST = 1.03

    /**
     * 绝对提升下限 ΔS。
     *
     * <p>为什么比值之外还要绝对量:低 S 时比值会失真 —— S=60→66 也是 1.1 倍,
     * 但这点提升肉眼不可见,不值得付一次 1~3s 的重载。
     */
    const val MIN_DELTA_S = 32

    /** 同清晰度下"每像素码率"的例外倍数:码率显著更高时允许切(同清晰度选高码率) */
    const val BPP_RATIO_FOR_EQUAL_S = 1.5

    private fun thetaOf(mode: DeviceCapability.QualityMode): Double = when (mode) {
        DeviceCapability.QualityMode.QUALITY_FIRST -> THETA_QUALITY_FIRST
        else -> THETA_AUTO
    }

    /**
     * "值得切"判据(纯函数)。
     *
     * <p>1) S 不降 且 `S_t / S_c ≥ θ` 且 `ΔS ≥ MIN_DELTA_S`;
     * 2) 例外:双方 bpp 已知、目标 `bpp ≥ BPP_RATIO × 当前 bpp` 且 S 不降 ⇒ 允许
     * (每像素码率是"同清晰度下的画质"代理量,见 [VideoQualityPolicy.bitsPerPixel])。
     *
     * <p>不判断设备上限/稳定期/额度 —— 那些由 [canSwitchUp] 统一管。
     */
    @JvmStatic
    fun worthSwitching(
        current: VideoQualityPolicy.Variant,
        target: VideoQualityPolicy.Variant,
        mode: DeviceCapability.QualityMode,
    ): Boolean {
        val currentS = VideoQualityPolicy.sharpness(current)
        val targetS = VideoQualityPolicy.sharpness(target)
        if (currentS <= 0 || targetS <= 0) return false
        if (targetS < currentS) return false
        if (targetS - currentS >= MIN_DELTA_S &&
            targetS.toDouble() / currentS >= thetaOf(mode)
        ) {
            return true
        }
        // bpp 例外:同清晰度但目标码率显著更高 ⇒ 画质更好,允许切一次
        val currentBpp = VideoQualityPolicy.bitsPerPixel(current)
        val targetBpp = VideoQualityPolicy.bitsPerPixel(target)
        return currentBpp > 0 && targetBpp > 0 && targetBpp.toDouble() >= currentBpp * BPP_RATIO_FOR_EQUAL_S
    }

    /**
     * 是否允许向 [target] 切换(全部条件满足才 true)。
     *
     * @param mode              生效档位(调用方传 [DeviceCapability.effectiveMode],不是用户原值)
     * @param current           当前线路**实测**画质;null / 尺寸未知 ⇒ 拒绝(没数据不猜)
     * @param target            候选线路**实测**画质;null / 尺寸未知 ⇒ 拒绝
     * @param deviceCapHeight   设备上限(像素高度);0 = 不限制
     * @param sinceEpisodeStartMs 进集时长;-1 = 未知(起播标记未到达,不切)
     * @param switchesDone      本集已切换次数
     * @param sessionLockedS    会话锁定档:**回滚后=出发地的 S**;目标 S 高于它一律拒;-1 = 未锁
     * @param trafficSaver      「流量节省」开关;开启 ⇒ 一切向上动作拒绝(附录 F)
     */
    @JvmStatic
    fun canSwitchUp(
        mode: DeviceCapability.QualityMode,
        current: VideoQualityPolicy.Variant?,
        target: VideoQualityPolicy.Variant?,
        deviceCapHeight: Int,
        sinceEpisodeStartMs: Long,
        switchesDone: Int,
        sessionLockedS: Int,
        trafficSaver: Boolean = false,
    ): Boolean {
        // 流量节省优先于其它一切条件:开启即不追高(口径见《选线机制设计》附录 F.6)
        if (trafficSaver) return false
        if (current == null || target == null) return false
        if (!current.known || !target.known) return false
        if (mode == DeviceCapability.QualityMode.SPEED_FIRST) return false
        if (deviceCapHeight > 0 && target.height > deviceCapHeight) return false
        // 天花板:已达 4K 级即停(附录 A:达顶即停,不得无限向上追问)
        if (VideoQualityPolicy.isAtCeiling(current)) return false
        if (!worthSwitching(current, target, mode)) return false
        if (sinceEpisodeStartMs < 0 || sinceEpisodeStartMs < MIN_WATCH_MS) return false
        if (switchesDone >= MAX_UPGRADES_PER_EPISODE) return false
        val targetS = VideoQualityPolicy.sharpness(target)
        if (sessionLockedS >= 0 && targetS > sessionLockedS) return false
        return true
    }

    /** 升档失败是否仍在回滚窗口内(出发地快照为空 = 无升档在途,不在窗口) */
    @JvmStatic
    fun inRollbackWindow(nowElapsed: Long, upgradedAtElapsed: Long): Boolean =
        upgradedAtElapsed > 0L && nowElapsed - upgradedAtElapsed <= ROLLBACK_WINDOW_MS
}
