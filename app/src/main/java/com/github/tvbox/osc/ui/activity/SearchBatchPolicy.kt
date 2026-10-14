package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.SourceWeightPolicy

/** 纯逻辑分批策略,避免一次把数百源都启动。 */
internal object SearchBatchPolicy {

    /**
     * 首批/每批的来源数。
     *
     * <p>取 50 的理由:全站搜索的目的是"找到这部片",而命中率取决于覆盖了多少源 —— 只搜 8 个源时
     * 常见的情况是首批一条都没有,用户看到空屏也不会去滑。50 个源基本能覆盖主流站点,
     * 又远小于聚合订阅动辄数百的量级,不会把线程池和爬虫装载锁一次性占满。
     *
     * <p>并发仍由 `SEARCH_THREADS` 的信号量兜住(默认 32),所以"排队 50 个"不等于"同时打 50 个请求"。
     */
    const val DEFAULT_BATCH_SIZE = 50

    /**
     * 快速首轮的来源数上限(2026-10-08)。
     *
     * <p>为什么单独设一个更小的值:全站搜索的首要目标是"尽快给出第一条可用结果",不是"一轮把库扫完"。
     * 数百源里真正又快又高命中的只是一部分(配置里已有 {@code quickSearch} 标记),首轮先搜这些,
     * 其余源留在队列里等用户滑动或点"搜索更多来源"。
     *
     * <p>取 24 的依据:大于 [DEFAULT_BATCH_SIZE] 的一半,能在多数订阅里覆盖首页源 + 主流快源,
     * 又让"首轮"与"后续批"的体感差异明显 —— 用户先看到结果,再决定要不要全搜。
     */
    const val FAST_ROUND_SOURCE_LIMIT = 24

    private const val PREFETCH_ROWS = 3

    fun nextQueuedKeys(queuedKeys: List<String>, limit: Int = DEFAULT_BATCH_SIZE): List<String> =
        if (limit <= 0) emptyList() else queuedKeys.take(limit)

    /**
     * 把参与搜索的源分成"首轮快速源"与"其余按需源"(2026-10-08)。
     *
     * <p>快速源 = 首页源 + {@code quickSearch != 0} 的源;其余源排到后面。
     *
     * <p>⚠️ **不排除**任何源:慢源/非快速源只是延后,仍可通过"搜索更多来源"或滑动搜到。
     * 静默丢弃慢源会让"库里明明有这部片子却搜不到",那是准确度事故,不是优化。
     *
     * @param sources   已按用户勾选与 searchable 过滤、并排好序的源
     * @param penalized 本轮要**降权**的源(近期搜索超时,见 SourceHealthPolicy.shouldDefer)。
     *                  降权 ≠ 屏蔽:它们照搜,只是排到**整个队列的最后**,不占首轮的并发额度。
     * @return 首轮 key(去重)与延后 key(健康延后源在前、降权源垫底)
     */
    fun splitFastRoundSources(
        sources: List<SourceBean>,
        homeKey: String,
        penalized: Set<String> = emptySet(),
    ): Pair<List<String>, List<String>> {
        val fast = LinkedHashSet<String>()
        val healthyDeferred = ArrayList<String>()
        val penalizedDeferred = ArrayList<String>()
        for (source in sources) {
            val isHome = source.key == homeKey
            val isPenalized = penalized.isNotEmpty() && penalized.contains(source.key)
            // 首页源**永不被降权**:用户刚从这个源看到片子,最可能就在这,把它排到队尾等于"首轮一定慢"。
            // 这与 SourceHealthMemory 里"首页源不豁免"不矛盾 —— 那里说的是"照样记它的失败证据",
            // 不是"首轮不许它上"。
            if (isHome || (source.isQuickSearch() && !isPenalized)) {
                fast.add(source.key)
            } else if (isPenalized) {
                penalizedDeferred.add(source.key)
            } else {
                healthyDeferred.add(source.key)
            }
        }
        return fast.toList() to (healthyDeferred + penalizedDeferred)
    }

    /**
     * 权重驱动的分批(2026-10-13,《源与线路的权重模型》P1-1)。
     *
     * <p>与 [splitFastRoundSources] 的两点差异:
     * <ol>
     *   <li>降权判据从**二值** `penalized` 升级为**连续权重** `weightOf` ——
     *       同为"被降权"的源,权重 45 与权重 5 不再被判成一样靠后;</li>
     *   <li>首轮强制纳入**保底配额**(权重最低的 N 个)—— 这是"只降权"的安全网(设计稿 §5.1)。
     *       没有它,降权源排到 328 源队尾后首轮 24 个源就出结果,用户不再滚动 ⇒ **队尾永不被搜到**,
     *       即"没有安全网的事实封禁"。</li>
     * </ol>
     *
     * <p>⚠️ 与旧口径一致的三条不变量:
     * **首页源永不被降权**(用户正从它浏览);**一个源都不能少**(降权 ≠ 屏蔽);
     * **快速源仍优先进首轮**(权重只在 fast/deferred 之内做次序,不改变"快源优先"的总策略)。
     *
     * <p>## 分层次序(低权重优先于"快速源"判定)
     * 一条低权重源即便带 quickSearch 标记,也**不该**进首轮 —— 否则"低权重"对快源完全失效,
     * 而那恰是"反复超时的快源"最常见的形态。故判定顺序为:
     * <ol>
     *   <li>首页源 ⇒ 首轮(无条件);</li>
     *   <li>否则低权重(权重 &lt; 绿档阈值)⇒ 延后(除非被保底配额捞入首轮);</li>
     *   <li>否则快速源 ⇒ 首轮;其余健康源 ⇒ 延后。</li>
     * </ol>
     *
     * @param sources        已过滤排序的源
     * @param homeKey        首页源 key
     * @param weightOf       取某源的有效权重;缺失按 [SourceWeightPolicy.NEUTRAL]
     * @param lastSearchedAtOf 取某源"上次被搜时刻"(0=无);供保底配额轮换
     * @param floorQuotaN    保底配额数(权重最低的强制进首轮);&lt;=0 关闭
     * @param onFloorPicked  可选回调:保底配额**实际捞进首轮**的 key 列表(供调用方打日志/诊断)。
     *                       本类刻意不依赖 LOG(保持纯逻辑可单测),故用回调把"捞了谁"交给调用方。
     *                       这是"证明兜底真实生效"的唯一可观测点 —— 没有它,只能看到计数、看不到成员。
     * @return 首轮 key(去重)与延后 key(健康延后在前、低权重垫底)
     */
    @JvmOverloads
    fun splitFastRoundSourcesByWeight(
        sources: List<SourceBean>,
        homeKey: String,
        weightOf: (String) -> Int,
        lastSearchedAtOf: (String) -> Long = { 0L },
        floorQuotaN: Int = SourceWeightPolicy.FLOOR_QUOTA_N,
        onFloorPicked: ((List<String>) -> Unit)? = null,
    ): Pair<List<String>, List<String>> {
        if (sources.isEmpty()) return emptyList<String>() to emptyList()
        // ① 保底配额:权重最低(并列时最久未搜)的 N 个,强制进首轮(首页源除外,它本就在首轮)
        val floorKeys: Set<String> = SourceWeightPolicy.floorQuota(
            candidates = sources.map { it.key },
            scoreAt = { weightOf(sources[it].key) },
            lastSearchedAtAt = { lastSearchedAtOf(sources[it].key) },
            n = floorQuotaN,
        ).toSet()
        val fast = LinkedHashSet<String>()
        val healthyDeferred = ArrayList<String>()
        val lowWeightDeferred = ArrayList<String>()
        for (source in sources) {
            val isHome = source.key == homeKey
            val isLow = weightOf(source.key) < SourceWeightPolicy.TIER_GREEN
            when {
                // 首页源永不被降权:用户正从它浏览,排到队尾等于"首轮一定慢"
                isHome -> fast.add(source.key)
                // 低权重优先于"快速源"判定:反复超时的快源正是最常见的低权重源
                isLow -> lowWeightDeferred.add(source.key)
                source.isQuickSearch() -> fast.add(source.key)
                else -> healthyDeferred.add(source.key)
            }
        }
        // ② 保底配额补进首轮:只补"本会落到延后列表"的,避免重复
        val floorAdded = ArrayList<String>()
        for (key in floorKeys) {
            if (key != homeKey && !fast.contains(key)) {
                fast.add(key)
                floorAdded.add(key)
            }
        }
        // 上报保底配额结果(调用方打 echo-weight floor 日志)。
        // ⚠️ **无条件回调**(2026-10-13 真机教训):此前 `if (floorAdded.isNotEmpty())` 守卫导致
        // "配额没生效(0 个)"与"配额生效但无低权重源可捞"在日志上都是 0 条,无法区分
        // —— 真机上正是因此误判"保底配额没实现"。回调**始终**发生,把候选数一并传出,
        // 调用方据 floor=<捞入数> cand=<候选数> 即可判断是"无料可捞"还是"根本没跑"。
        onFloorPicked?.invoke(floorAdded)
        // ③ 延后列表里,被保底捞走的要去掉(它已在首轮),其余保持原次序
        val deferred = (healthyDeferred + lowWeightDeferred).filterNot { fast.contains(it) }
        return fast.toList() to deferred
    }

    /**
     * 把**降权源**排到队尾,**不改变候选取舍**(返回列表与入参元素完全相同,只变顺序)。
     *
     * <p>## 为什么单独有它,而不是复用 [splitFastRoundSources]
     * 那个函数回答的是"首轮快速源该放哪几个",会把**非快速源也一并延后**;
     * 而详情页换源链的候选池**已经**全是快速源(`isQuickSearch()` 已在池构造时过滤),
     * 它只需要"降权垫底"这一个动作。两者混用会让"换源链到底降了谁"变得难读。
     *
     * <p>## ⚠️ 调用方必须**先做上限截断(take)再调本函数**
     * 若先降权后截断,被降权的源会被上限直接砍掉 —— 那就不是"降权"而是"屏蔽"了,
     * 正是《源与线路的权重模型》第 2 节警告的"没有安全网的事实封禁"。
     * 本函数只重排不剔除,所以它本身没有这个风险;风险在调用顺序上。
     *
     * <p>刻意**不豁免首页源**(与 [splitFastRoundSources] 不同):换源链的当前源
     * 正是刚刚失败的那个,给它任何优先都没有依据;不豁免让语义更简单、少一个特例。
     *
     * @param sources   已完成过滤与上限截断的候选(顺序即原调度优先级)
     * @param penalized 本轮要降权的源 key(近期搜索超时,见 `SourceHealthPolicy.shouldDefer`)
     */
    fun penalizedLast(sources: List<SourceBean>, penalized: Set<String>): List<SourceBean> {
        if (sources.size < 2 || penalized.isEmpty()) return sources
        val normal = ArrayList<SourceBean>(sources.size)
        val deferred = ArrayList<SourceBean>()
        for (source in sources) {
            if (penalized.contains(source.key)) deferred.add(source) else normal.add(source)
        }
        // 一个降权源都没有 ⇒ 原样返回(省一次重排,也确实无需改动顺序)
        if (deferred.isEmpty()) return sources
        normal.addAll(deferred)
        return normal
    }

    /**
     * 详情换源链的**权重驱动**排序(2026-10-13,优化方案问题 3 修复)。
     *
     * <p>## 为什么需要它
     * 详情页此前用 [penalizedLast] + `SourceHealthFilter.penalizedKeys()`(旧**二值**口径),
     * 而搜索页已升级为 [splitFastRoundSourcesByWeight](**连续权重**)。两处口径不一致会造成:
     * 同一个"刚超时 5 次的慢源"在搜索页被排到队尾,在这里却仍占前几批并发额度。
     *
     * <p>本函数把详情的排序也切到**同一份权重台账**,与搜索页口径对齐:
     * <ul>
     *   <li>按有效权重降序(权重高的在前),并列时保持原顺序(稳定排序);</li>
     *   <li>**一个源都不少** —— 只重排不剔除(与"降权≠屏蔽"一致);</li>
     *   <li>首页源不特殊照顾:详情页候选池已 `sortedBy { it.key != home.key }` 把首页源排最前,
     *       这里再按权重排会削弱"首页优先";故**首页源固定在最前**,其余按权重。</li>
     * </ul>
     *
     * <p><b>2026-10-14 用户定稿 —— "有权重按权重降序 + 无权重按自然序"</b>:
     * 旧实现只按 `weightOf` 降序,对**无记录源**返回 [SourceWeightPolicy.NEUTRAL](60),
     * 于是"从没搜过的新源"会和"历史衰减到 60 的源"混排、被虚假插入到中间,
     * 破坏了"无数据就按源列表自然序排在后面"的语义。现改为**三层**:
     * <ol>
     *   <li>首页源固定置顶;</li>
     *   <li>其余:**有权重记录的源整体在前**,其内按权重降序;</li>
     *   <li>**无权重记录的源**统一排在最后,且**保持自然序**(稳定排序,不写 tie-break)。</li>
     * </ol>
     *
     * @param sources       已过滤的候选池
     * @param homeKey       首页源 key(固定置顶)
     * @param weightOf      取某源有效权重;仅对**有权重记录**的源调用
     * @param hasWeightOf   该源**是否有权重记录**(决定进"按权重降序"层还是"自然序"层)
     */
    fun orderByWeight(
        sources: List<SourceBean>,
        homeKey: String,
        weightOf: (String) -> Int,
        hasWeightOf: (String) -> Boolean = { true },
        /**
         * 快搜源分层(2026-10-14,详情页换源用):true = 快搜源、false = 需现算地址的慢源。
         *
         * <p>换源池此前**硬性排除**慢源 ⇒ 那些源永远不参与换源(用户"找得全"的诉求落空)。
         * 改为纳入但整层排后:首轮 1/3 仍然全是快源(换源首轮速度不受影响),慢源由后续轮次补齐。
         * 默认 `{ true }` = 不分层,搜索页行为一字不变。
         */
        quickSearchOf: (SourceBean) -> Boolean = { true },
    ): List<SourceBean> {
        if (sources.size < 2) return sources
        // 稳定排序:同层内保留原到达序(无权重源据此保持自然序)。
        return sources.sortedWith(
            compareByDescending<SourceBean> { it.key == homeKey }
                .thenByDescending { quickSearchOf(it) }
                .thenByDescending { hasWeightOf(it.key) }
                .thenByDescending { if (hasWeightOf(it.key)) weightOf(it.key) else 0 },
        )
    }

    /**
     * 本次应该启动多少个源:首轮用 [FAST_ROUND_SOURCE_LIMIT],之后每批恢复 [DEFAULT_BATCH_SIZE]。
     *
     * <p>用"队列里是否还含延后源"判断首轮:首轮只取快速源,队列一旦被消耗过就说明已经进过后续批。
     */
    fun batchSizeFor(queuedKeys: List<String>, isFirstRound: Boolean): Int {
        val limit = if (isFirstRound) FAST_ROUND_SOURCE_LIMIT else DEFAULT_BATCH_SIZE
        return limit.coerceAtMost(queuedKeys.size)
    }

    /** 首批/本批完全无命中时自动续搜,避免空屏被误认为全库无结果。 */
    fun shouldAutoContinue(hasAnyResult: Boolean, queuedSourceCount: Int): Boolean =
        !hasAnyResult && queuedSourceCount > 0

    // ==================== 弱网超时自适应(2026-10-13,优化方案 P2-2) ====================

    /** 常规单源超时(与 SearchViewModel.SEARCH_TIMEOUT_MS 同口径) */
    const val BATCH_TIMEOUT_MS = 8_000L

    /**
     * 弱网下的单源超时(ms)。
     *
     * <p>取 4.5s:与本仓详情换源链的快探超时(4s)同量级。弱网时若仍等 8s,
     * 一批 50 个源里有大半会各占满 8s,把**首屏**硬拖到"一个 8s 波次之后"——
     * 而这批源在弱网下大概率等满也只有超时,提前让出额度反而让少数能答话的源更快上屏。
     *
     * <p>⚠️ **超时不等于丢证据**:让出的只是协程额度,请求仍在跑,晚到的结果照样计入
     * (与 [DETAIL_PROBE_TIMEOUT_MS] 同款说明)。
     */
    const val BATCH_TIMEOUT_DEGRADED_MS = 4_500L

    /**
     * 本批超时占比达到此比例 ⇒ 判定弱网,下一批缩短超时。
     *
     * <p>取 0.5:一半以上都超时,说明大概率是**整体网络**问题而非个别源;
     * 低于一半时仍按常规超时,避免"个别慢源"被误当成弱网而全面缩短超时(那会让好源也被提前掐断)。
     */
    const val DEGRADED_TIMEOUT_RATIO = 0.5

    /**
     * 根据**上一批**的结算结果推导本批单源超时(纯函数)。
     *
     * <p>判据:[settled] 总数 ≥ [MIN_SAMPLE_FOR_DEGRADE] 且超时占比 ≥ [DEGRADED_TIMEOUT_RATIO]
     * 才降级。两者都要:
     * <ul>
     *   <li>**最小样本**:批很小时(比如只剩 2 个源)1 个超时就过半,不足以判弱网;</li>
     *   <li>**比例阈值**:见 [DEGRADED_TIMEOUT_RATIO]。</li>
     * </ul>
     *
     * <p>只有两种返回值(常规 / 降级),**不做多级**:超时是粗信号,多级调节会让行为难以解释,
     * 而收益有限。
     *
     * @param settledCount 上一批**已结算**(终态)的源数
     * @param timeoutCount 其中判为超时的源数
     */
    fun batchTimeoutMs(settledCount: Int, timeoutCount: Int): Long {
        if (settledCount < MIN_SAMPLE_FOR_DEGRADE) return BATCH_TIMEOUT_MS
        val ratio = timeoutCount.toDouble() / settledCount
        return if (ratio >= DEGRADED_TIMEOUT_RATIO) BATCH_TIMEOUT_DEGRADED_MS else BATCH_TIMEOUT_MS
    }

    /** 判定弱网所需的最小样本:小批次的比例不可信(1/2 也过半) */
    const val MIN_SAMPLE_FOR_DEGRADE = 8

    /**
     * 详情页换源链一轮里**最多补发多少次查询变体**(2026-10-14)。
     *
     * <p>补发的触发条件是"这个源答了话却一个候选都没给",而冷门片时**所有源**都满足这个条件 ⇒
     * 没有预算就是 源数 × 变体数 次请求(322 × 4 ≈ 1300),全量耗时翻几倍。
     * 取 40:片名写法差异通常在权重最高的那批源里就能补回来,再往后边际收益急剧下降。
     */
    const val DETAIL_VARIANT_REASK_BUDGET = 40

    // ==================== 详情换源链的**并发自适应**(2026-10-14) ====================

    /**
     * 详情页并发上限(2026-10-14 定案:**不是**照抄搜索页的线程数)。
     *
     * <p>搜索页并发的时候没有播放在场;详情页聚合搜索却是在**给正在缓冲的视频流抢带宽**,
     * 照抄 32/64 会把"点换源就卡播"变成新问题。所以:用户线程数**打对折**再封顶 16。
     * 下限 4:再少就搜不完(322 源 × 4 并发 × 8s 是十分钟量级)。
     */
    const val DETAIL_CONCURRENCY_MAX = 16
    const val DETAIL_CONCURRENCY_MIN = 4

    /** 由用户设定推导出详情页并发上界:`max(4, min(16, 用户线程数 / 2))`。 */
    @JvmStatic
    fun detailConcurrencyCap(userThreads: Int): Int =
        (userThreads / 2).coerceIn(DETAIL_CONCURRENCY_MIN, DETAIL_CONCURRENCY_MAX)

    /**
     * 波与波之间调整并发(纯函数):判据与 [batchTimeoutMs] **完全共用**(样本 ≥8 且超时占比 ≥0.5)。
     *
     * <p>为什么值得自动调而不是只信用户滑块:真机超时率常年在 75~83%,弱网时高并发只是把
     * 更多请求堆在超时上排队;而网络好时死守 12 又白慢。同一份信号已经用来缩超时,
     * 再用来缩并发是一致的做法 —— 用户不需要再多一个旋钮。
     *
     * <p>调节是**粗粒度**的:弱网砍一半,健康时 +4 逐步升回上限,上限就是用户额度折算后的 cap。
     *
     * @param settledCount 上一波**结算**的源数(等不到样本就原样保持)
     * @param timeoutCount 其中没答话(超时/异常)的源数
     * @param current      当前并发
     * @param cap          [detailConcurrencyCap] 算出的上限
     */
    @JvmStatic
    fun nextDetailConcurrency(
        settledCount: Int,
        timeoutCount: Int,
        current: Int,
        cap: Int,
    ): Int {
        if (settledCount < MIN_SAMPLE_FOR_DEGRADE) return current
        val ratio = timeoutCount.toDouble() / settledCount
        return if (ratio >= DEGRADED_TIMEOUT_RATIO) {
            maxOf(DETAIL_CONCURRENCY_MIN, current / 2)
        } else {
            minOf(cap, current + 4)
        }
    }

    // ==================== 详情换源链的"快探"(2026-10-13) ====================

    /**
     * 详情换源链里享受**快探超时**的源数(优先级最高的前 N 个)。
     *
     * <p>取值 = 搜索并发数(12):恰好**一个并发波次**。这 12 个是"为第一个候选下注"的那批,
     * 给它们更短的超时 ⇒ 额度更快回收 ⇒ 排队的下一个源更早被投出去。
     *
     * <p>⚠️ 它**不是"首批大小"** ——源不会被切成批、也没有批次屏障(见
     * {@code DetailViewModel.startSourceSearch}):全部源一次性入队,并发由信号量控。
     * 这个常量只回答"前几个源用短超时"。
     */
    const val DETAIL_PROBE_SIZE = 12

    /**
     * 快探源的**单源超时**(ms)。
     *
     * <p>常规单源超时是 8s(`SOURCE_SEARCH_TIMEOUT_MS`),而真机源超时率 75~83% ⇒
     * 排在并发窗口里的慢源会各占 8s 额度,把后面的源硬生生压住。
     *
     * <p>取 4s:快源(1~2s)完全来得及。**超时不等于丢证据** ——
     * 协程超时只是让本任务提前让出额度,该源的请求仍在跑,结果晚到照样经
     * `onSearchResultEvent` 计入候选与台账(口径不变)。
     *
     * <p>⚠️ 对**爬虫型源(type 3)无效**:那类走 `BoundedCall` 阻塞调用,
     * 超时由站点声明的取数限时决定(默认 8s),协程取消打断不了它 ——
     * 所以这个值只对 0/1/4 型源起效,这一点写在注释里避免日后误解。
     */
    const val DETAIL_PROBE_TIMEOUT_MS = 4_000L

    /**
     * 详情换源链**单源**的超时:按候选顺序,前 [probeCount] 个走快探超时,其余走常规超时。
     *
     * <p>单独抽成纯函数是为了让"快探那批必须短"这条口径可被单测钉住 ——
     * 它很容易在后续重构里被无意改回统一超时,而那正是起播慢的成因。
     */
    fun detailSourceTimeoutMs(
        index: Int,
        probeCount: Int = DETAIL_PROBE_SIZE,
        probeMs: Long = DETAIL_PROBE_TIMEOUT_MS,
        normalMs: Long = 8_000L,
    ): Long = if (index >= 0 && index < probeCount) probeMs else normalMs

    /**
     * 起播成功后是否该**冻结**后台聚合搜索(2026-10-13,P1)。
     *
     * <p>画面已经出来就没必要再把剩下的源搜完:候选只在"这条源放不出来"时才有用,
     * 而那种情况会由换源链重新发起一轮新搜索 —— 搜索的重启判据只看"搜索是否在跑"
     * (见 {@code DetailViewModel.ensureSourceSearchRunning}),所以冻结时必须把该状态置回 false。
     *
     * <p>两条判据都必要:
     * <ul>
     *   <li>没起播:没什么可省的,搜索该跑完(它正是"起播失败"时的救命通道);</li>
     *   <li>搜索已收工:别去动一个已经收口的状态 —— 重复置位/重复落盘都是白做,
     *       而且会把"这一轮到底结束没有"的语义搅浑。</li>
     * </ul>
     * 冻结的兑现口径(不在这里表达,由调用方保证):**停投新源 + 在途源照跑**,
     * 在途结果仍计入候选与台账 —— 冻结的是"还要不要继续开新请求",不是"已发出的证据要不要"。
     */
    fun shouldFreezeOnPlaybackStarted(playbackStarted: Boolean, searchRunning: Boolean): Boolean =
        playbackStarted && searchRunning

    /** 仅用户正在滚动且接近列表尾部时预取;短列表不会因初始布局把所有源自动搜完。 */
    fun shouldLoadMoreOnScroll(
        isScrollInProgress: Boolean,
        lastVisibleItemIndex: Int,
        totalItemCount: Int,
        hasMore: Boolean,
        running: Boolean,
    ): Boolean = isScrollInProgress && hasMore && !running && totalItemCount > 0 &&
        lastVisibleItemIndex >= (totalItemCount - 1 - PREFETCH_ROWS).coerceAtLeast(0)
}
