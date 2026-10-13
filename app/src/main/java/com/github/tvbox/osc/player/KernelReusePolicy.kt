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

    /**
     * 本次起播是不是**换片**(内核里躺着的是**另一部片**),而不是同一部的换源/换线/换集。
     *
     * <p>## 为什么要单独判"换片"(2026-10-13)
     * [isCrossContentSwitch] 太宽:它把换源/换线也算成"换内容",而这三条路复用上一部的内核是**收益**
     * (省一次内核重建;进度继承也正靠它)。真正**不能**复用上一部内核的只有换片 ——
     * 内核里那份是另一部片的画面与声音:取流要 1~3 秒,而这段时间里旧内容仍在播放器里
     * (缓冲态下 {@code playWhenReady} 甚至还是 true)⇒ 用户先听到上一部的声音、多走一次错误的起播,
     * 之后才被新片的地址顶掉。所以换片必须"上一部的内核/会话/待继承位置一律作废"。
     *
     * <p>判据取归属键 `源|片id|线路|集号`([com.github.tvbox.osc.player.PlaybackSession.playbackKey])
     * 的第 2 段(片 id):换源 = 同一片 id 不同源,换线/换集 = 同片同源不同线路/集号 ⇒ 都不算换片。
     *
     * @param startedKey 内核里那份内容的键;null/空 = 内核里本来没有内容(预热内核/已释放)
     * @return true = 必须先作废上一部内容再起播
     */
    @JvmStatic
    fun isDifferentVodSwitch(startedKey: String?, targetKey: String?): Boolean {
        // 内核里没内容 ⇒ 没有"上一部"可作废(这一条同时保住了"预热/空闲内核复用"的收益)
        if (startedKey.isNullOrEmpty()) return false
        return vodIdOf(startedKey) != vodIdOf(targetKey)
    }

    /**
     * 取归属键里的**片 id**(第 2 段)。段数不足(不该发生)时把整键当身份用 ——
     * 判不出就按"不是同一部"处理,宁可多重一次内核,也不能让上一部的声画留到新片里。
     */
    private fun vodIdOf(key: String?): String? {
        if (key.isNullOrEmpty()) return null
        val parts = key.split('|')
        return if (parts.size >= 2) parts[1] else key
    }
}
