package com.github.tvbox.osc.player

/**
 * 线路优选编排:**纯逻辑**,网络与爬虫调用由调用方注入,所以可单测。
 *
 * <p>为什么是"串行 + 早停"而不是"并行探测全部":
 * <ul>
 *   <li>要拿到第 2 条线路的直链,必须先对它跑一次爬虫解析,而**同类 Spider 共用静态状态、不能并发**
 *       (见 `api/WarmQueue.java` 的同名注释);</li>
 *   <li>每条解析实测 1~3 秒,并行探测 N 条等于把起播推迟好几秒,而收益只是"多试了几条";</li>
 *   <li>命中"够好"就停 ⇒ 最好的情况(第一条就达标)**零额外延迟**。</li>
 * </ul>
 */
object LineQualitySelector {

    /**
     * 逐条解析 + 探测,选出最优 flag。
     *
     * @param flags           候选线路(调用方已按初筛排好序)
     * @param deviceCapHeight 设备上限,超出的档直接排除;0 = 不限制
     * @param thresholdHeight 早停水位:找到一个不低于它的档就停(给"够好"不是"最好")
     * @param resolve         flag → 直链;返回 null / 抛异常 = 这条不可用,跳过
     * @param probe           直链 → 实测画质;返回 null = 探测不到,跳过(不猜)
     * @return 选中的 flag；全部不可用时返回 null(调用方回落站点原序)
     */
    suspend fun pickWithProbe(
        flags: List<String>,
        deviceCapHeight: Int,
        thresholdHeight: Int,
        resolve: suspend (String) -> String?,
        probe: suspend (String) -> VideoQualityPolicy.Variant?,
    ): String? {
        var best: VideoQualityPolicy.Variant? = null
        for (flag in flags) {
            val url = runCatchingBlocking { resolve(flag) } ?: continue
            val measured = runCatchingBlocking { probe(url) } ?: continue
            if (measured == null) continue
            val candidate = measured.copy(flag = flag)
            best = VideoQualityPolicy.pickBest(listOfNotNull(best, candidate), deviceCapHeight)
            if (VideoQualityPolicy.goodEnough(best, thresholdHeight)) return best?.flag
        }
        return best?.flag
    }

    /**
     * 同步选线:只用**已记忆的实测值**,零网络、零延迟。
     *
     * <p>这是绝大多数场景走的路径(第二次及以后播放同一部);记忆缺失时返回 null,
     * 调用方据此决定要不要付"异步探测"那 1~3 秒。
     */
    fun pickFromMemory(
        remembered: List<VideoQualityPolicy.Variant>,
        deviceCapHeight: Int,
    ): String? = VideoQualityPolicy.pickBest(remembered, deviceCapHeight)?.flag

    /**
     * 把"记忆里有的"与"没测过的"合成完整候选序:有实测值的按实测排在前,
     * 没测过的保持站点原序接在后面 —— 探测是"锦上添花",不能因为缺数据就把整条路堵死。
     */
    fun mergeCandidates(
        flagsInSiteOrder: List<String>,
        remembered: List<VideoQualityPolicy.Variant>,
        deviceCapHeight: Int,
        limit: Int,
    ): List<String> {
        if (flagsInSiteOrder.isEmpty() || limit <= 0) return emptyList()
        // known 保留全部实测值:它同时承担"这条测过没有"的判断,过滤掉超上限的会让它们被当成未知而重排
        val known = LinkedHashMap<String, VideoQualityPolicy.Variant>()
        for (v in remembered) if (v.flag.isNotEmpty()) known[v.flag] = v
        val eligible = known.values.filter { deviceCapHeight <= 0 || !it.known || it.height <= deviceCapHeight }
        val ranked = VideoQualityPolicy.pickBest(eligible, deviceCapHeight)
        val ordered = ArrayList<String>(limit)
        if (ranked != null) ordered.add(ranked.flag)
        val rest = eligible.filter { it.flag != ranked?.flag }.sortedByDescending { VideoQualityPolicy.score(it) }
        for (v in rest) if (ordered.size < limit) ordered.add(v.flag)
        for (f in flagsInSiteOrder) if (ordered.size < limit && !known.containsKey(f)) ordered.add(f)
        return ordered
    }

    /** suspend 块里的同步保护:任何异常都当成"这条不可用",绝不让探测失败冒泡到播放链路 */
    private suspend fun <T> runCatchingBlocking(block: suspend () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        null
    }

    /**
     * 挑一条**实测分辨率更低**的候选,用于卡顿时降档。
     *
     * <p>"降档"与"换线"是同一个操作(换 flag),区别只在**选哪一条**:按站点顺序换可能换到同样高的档,
     * 白折腾一次起播;按实测高度降则直击"分辨率超过网络/设备能力"这个真因。
     *
     * <p>三条硬约束,少一条就会误伤:
     * <ul>
     *   <li>当前高度未知(0)⇒ 返回 null —— 不知道现在多高就没法判断"更低";</li>
     *   <li>候选必须有**实测**高度(`known`)—— 没测过的不能当"更低",那是猜;</li>
     *   <li>已试过的线路跳过 —— 否则会在两条之间来回切。</li>
     * </ul>
     *
     * @return 目标 flag；没有可降的档时 null(调用方继续走"换线 → 换源")
     */
    @JvmStatic
    fun pickDowngrade(
        measured: List<VideoQualityPolicy.Variant>,
        currentFlag: String,
        currentHeight: Int,
        triedFlags: Set<String>,
    ): String? {
        if (currentHeight <= 0) return null
        val candidates = measured.filter {
            it.flag.isNotEmpty() && it.flag != currentFlag && it.known &&
                it.height < currentHeight && !triedFlags.contains(it.flag)
        }
        if (candidates.isEmpty()) return null
        // 降得最少:取低于当前档里最高的那条
        return candidates.maxByOrNull { it.height }?.flag
    }
}
