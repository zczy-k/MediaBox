package com.github.tvbox.osc.player

/** 起播前"能否复用现有内核"的判定结果 */
enum class KernelDecision { REUSE, REBUILD }

/**
 * 「这次起播要不要复用播放内核」的唯一判定:调度层的释放决策与各起播点的 replay/start 决策必须共用本函数。
 * 分散判定会造成动作分裂 —— 上游判定"保留",下游又把它释放,两侧各执行半套动作。
 */
object KernelReusePolicy {

    /**
     * REBUILD 是强结论(调用方须先释放再新建);REUSE 是弱结论 —— 起播点现场若发现"必须重建"标记,允许升级为 REBUILD。
     *
     * @param kernelPresent   内核实例是否还在(已释放/未创建为 false)
     * @param rebuildRequired "必须重建"标记已置位(渲染方式或 EXO 解码方式变更)
     * @param dedicatedPath   本次起播强制专用路径(dash 等),复用内核无法生效
     * @param reuseAllowed    是否允许复用(调度层意图;起播点不设门槛时传 true)
     */
    @JvmStatic
    fun decide(
        kernelPresent: Boolean,
        rebuildRequired: Boolean,
        dedicatedPath: Boolean,
        reuseAllowed: Boolean,
    ): KernelDecision {
        if (!kernelPresent) return KernelDecision.REBUILD
        if (rebuildRequired || dedicatedPath) return KernelDecision.REBUILD
        return if (reuseAllowed) KernelDecision.REUSE else KernelDecision.REBUILD
    }

    /**
     * 内核里躺着的**不是**本次要播的同一集(换片/换源/换线,或同片换线后换集) —— 与"同片同线路换集"相对。
     * 只用来分辨提示语与进度落盘口径;归属键格式见 [PlaybackSession.playbackKey] `源|片id|线路|集号`。
     */
    @JvmStatic
    fun isCrossContentSwitch(startedKey: String?, targetKey: String?): Boolean {
        if (startedKey.isNullOrEmpty()) return false
        val prefix = sameVodEpisodePrefix(targetKey)
        if (prefix.isEmpty()) return true
        return !startedKey.startsWith(prefix)
    }

    /** 同片同线路的归属前缀:按目标键最后一个 `|` 切出,内核里那条键以它开头即同集换集;切不出返回空串(判不出按换内容) */
    private fun sameVodEpisodePrefix(targetKey: String?): String {
        if (targetKey.isNullOrEmpty()) return ""
        val cut = targetKey.lastIndexOf('|')
        return if (cut > 0) targetKey.substring(0, cut + 1) else ""
    }
}
