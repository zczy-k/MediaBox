package com.github.tvbox.osc.player

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

    /** 单源或单线路没有可比较对象:跳过全部预检,让调用方直接起播。 */
    fun shouldPreflightQuality(sourceCount: Int, lineCount: Int): Boolean =
        sourceCount > 1 && lineCount > 1

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

    /**
     * 排出本轮真正需要探测的线路:已记住的线路参与最终比较,不重复发起网络探测;
     * 候选总数仍受 [limit] 限制,未知线路按站点顺序补位。
     */
    fun candidatesToProbe(
        flagsInSiteOrder: List<String>,
        remembered: List<VideoQualityPolicy.Variant>,
        deviceCapHeight: Int,
        limit: Int,
    ): List<String> {
        val rememberedFlags = remembered.mapNotNullTo(HashSet<String>()) { variant ->
            variant.flag.takeIf { it.isNotEmpty() }
        }
        return mergeCandidates(flagsInSiteOrder, remembered, deviceCapHeight, limit)
            .filterNot { rememberedFlags.contains(it) }
    }

    /** suspend 块里的同步保护:任何异常都当成"这条不可用",绝不让探测失败冒泡到播放链路 */
    private suspend fun <T> runCatchingBlocking(block: suspend () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        null
    }

    /**
     * **并发**探测候选线路并返回成功测得的结果,供调用方与记忆候选统一排序。
     *
     * <p>与 [pickWithProbe] 的区别:那个是串行,因为每条要先跑爬虫解析(同类 Spider 共享静态状态,
     * 不能并发);这个用于**直连型**线路 —— `VodSeries.url` 本身就是可播放地址时,探测只需要一次小请求,
     * 没有共享状态,自然可以并发,总耗时等于最慢那一条而不是全部之和。
     *
     * <p>不做逐条早停:`awaitAll` 语义更简单可预测,而带宽由调用方的候选数上限兜住
     * (K 条 × ≤256KB,3 条最坏 768KB)。整体还有调用方的硬超时。
     *
     * @return 探测成功的线路画质;调用方可与记忆候选一起排名
     */
    suspend fun probeVariantsParallel(
        flags: List<String>,
        resolve: suspend (String) -> String?,
        probe: suspend (String) -> VideoQualityPolicy.Variant?,
    ): List<VideoQualityPolicy.Variant> = coroutineScope {
        flags.map { flag ->
            async {
                val url = runCatchingBlocking { resolve(flag) } ?: return@async null
                val measured = runCatchingBlocking { probe(url) } ?: return@async null
                measured.copy(flag = flag)
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun pickWithProbeParallel(
        flags: List<String>,
        deviceCapHeight: Int,
        resolve: suspend (String) -> String?,
        probe: suspend (String) -> VideoQualityPolicy.Variant?,
    ): String? = VideoQualityPolicy.pickBest(
        probeVariantsParallel(flags, resolve, probe),
        deviceCapHeight,
    )?.flag

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

    /**
     * 升档选线(2026-10-10,自适应画质):[pickDowngrade] 的镜像 —— 取**高于**当前实测档里
     * 最低的那条("升得最少",单步走,本集还有额度可继续升)。
     *
     * <p>候选只来自实测记忆,不探测(探测属第二批后台择优);没记忆不猜,与降档同一口径。
     */
    @JvmStatic
    fun pickUpgrade(
        measured: List<VideoQualityPolicy.Variant>,
        currentFlag: String,
        currentHeight: Int,
        triedFlags: Set<String>,
    ): String? {
        if (currentHeight <= 0) return null
        val candidates = measured.filter {
            it.flag.isNotEmpty() && it.flag != currentFlag && it.known &&
                it.height > currentHeight && !triedFlags.contains(it.flag)
        }
        if (candidates.isEmpty()) return null
        // 升得最少:取高于当前档里最低的那条
        return candidates.minByOrNull { it.height }?.flag
    }
}
