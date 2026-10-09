package com.github.tvbox.osc.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.catvod.crawler.JsLoader
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.util.AvailabilityHeuristic
import com.github.tvbox.osc.util.AvailabilityMemory
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.SearchHelper
import com.github.tvbox.osc.util.SearchSettings
import com.github.tvbox.osc.util.SourceFailKind
import com.github.tvbox.osc.util.SourceHealthFilter
import com.github.tvbox.osc.util.SourceHealthMemory
import com.github.tvbox.osc.util.UA
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.LinkedHashMap

class SearchViewModel : ViewModel() {

    /**
     * 单个来源的搜索状态。
     *
     * <p>[Queued] 是"还没轮到搜"——分批加载后大量来源会长时间停在这个态,与 [Pending]("正在搜")
     * 必须分开:否则站点栏会对几十个根本没发起请求的源一起转圈,看着像卡死。
     *
     * <p>2026-10-08 把终态从单一 [Done] 拆成四种:此前"搜到了""没搜到""源挂了""超时了"全叫 Done,
     * 用户与排查者都无法区分"这条源确实没这部片"和"这条源请求失败" —— 而这两种情况该采取的
     * 动作完全相反(前者继续换源,后者该重试或降权)。
     */
    enum class ResultState {
        /** 还没轮到搜 */
        Queued,

        /** 正在搜 */
        Pending,

        /** 搜到了:源有回包,且过滤后至少留下 1 条匹配结果 */
        Done,

        /** 没有任何有效命中:源站正常响应,但没有匹配的结果 */
        Empty,

        /** 失败:网络错误 / 解析失败 / 源不可用 */
        Failed,

        /** 超时:等满单源限时仍未拿到回包 */
        Timeout,
        ;
    }

    /** 终态集合:这三种都表示"这个源不用再等了" */
    private fun ResultState.isTerminal(): Boolean =
        this == ResultState.Done || this == ResultState.Empty || this == ResultState.Failed ||
            this == ResultState.Timeout

    data class SourceResult(
        val sourceKey: String,
        val sourceName: String,
        val state: ResultState,
        val videos: List<Movie.Video>,
        val arrivedAt: Int = Int.MAX_VALUE,
        /** 本源耗时(ms),仅终态有值;来源不可用时为 -1 */
        val elapsedMs: Long = -1L,
    )


    val results = MutableStateFlow<List<SourceResult>>(emptyList())
    val running = MutableStateFlow(false)
    val searchedTitle = MutableStateFlow("")
    val matchMode = MutableStateFlow(SearchSettings.MatchMode.Smart)
    val sitesEmpty = MutableStateFlow(false)

    /** 还有没搜的来源(分批加载的游标);UI 据此显示"搜索更多来源"而不是"无结果" */
    val hasMore = MutableStateFlow(false)

    /** 已**发起**搜索的来源数 / 总来源数,用于"已搜索 x/y"进度 */
    val searchedCount = MutableStateFlow(0)
    val totalCount = MutableStateFlow(0)

    /**
     * 已**拿到终态**的来源数(2026-10-08)。
     *
     * <p>与 [searchedCount] 分开:后者在批次启动时就 +N,是"已发起";"已搜索 x/y"这句话
     * 在用户眼里指的是"搜完了多少",用一个还在转圈的数字冒充完成数会误导对当前批次的判断。
     */
    val settledCount = MutableStateFlow(0)

    /**
     * 当前订阅**全部可搜源**数(2026-10-09)。
     *
     * <p>与 [totalCount](= 本次实际参与搜索的勾选源数)配对:两者差距大说明勾选表在悄悄收窄
     * 搜索范围 —— 真机踩过:勾选表里只存了 10 个源(来源不明的旧配置残留),333 个可搜源里
     * 影视源全被排除,用户以为"其他源没结果",实际是**根本没搜**。搜索页 footer 据此显示提示。
     */
    val searchableCount = MutableStateFlow(0)


    val hotSearch = MutableStateFlow<List<String>>(emptyList())

    val suggest = MutableStateFlow<List<String>>(emptyList())

    private var suggestSeq = 0

    private var token = 0
    private var arriveSeq = 0
    private var semaphorePermits = KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT)
    private var semaphore = Semaphore(semaphorePermits)
    private val pendingSources = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()

    /**
     * 本轮每个源的"完成信号",终态语义比 [pendingSources] 更细(2026-10-08)。
     *
     * <p>为什么不能只靠 [pendingSources]:那个 map 只能回答"回包了没有",回答不了"是命中、
     * 无命中,还是失败/超时"。而"原词没打中要不要补一个紧凑变体"恰恰依赖后者 ——
     * 只有**无命中**才值得补,失败或超时补了也是白花请求。
     *
     * <p>key 与 [pendingSources] 一致;`null` 表示"取不到结果"(超时/取消),非 null 为终态。
     */
    private val pendingOutcomes = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<ResultState?>>()

    /** 每源开始搜索的时刻,用于算耗时;与 [pendingOutcomes] 同生命周期 */
    private val sourceStartAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 本轮是否已经进过"后续批"(首轮只搜快速源);用于决定本批大小 */
    private var startedNonFastRound = false

    private val scope = viewModelScope


    /** 尚未发起的来源(按优先级排好序);每批从队首取 [SearchBatchPolicy.DEFAULT_BATCH_SIZE] 个 */
    private var queuedSourceKeys: List<String> = emptyList()

    /** 本轮查询的变体(番号类关键词会多一个紧凑形式),分批共用同一份 */
    private var queryVariants: List<String> = emptyList()

    private var batchJob: kotlinx.coroutines.Job? = null

    /** 有批次在跑。用显式标记而不是 batchJob.isActive:续批是从上一批的收尾里发起的,那时 job 还没结束 */
    private var batchInFlight = false

    /**
     * 批次序号。收尾只允许"当前这一批"复位 [batchInFlight]:
     * cancel() 是异步的,上一批的 finally 可能晚于新批次的启动才执行,无脑复位会把新批次误标成"空闲",
     * 用户再滑一下就会重复启动一批。
     */
    private var batchSeq = 0

    /** 本批已发起搜索的来源。取消这一批时要把它们放回队列,否则会永远停在 Pending */
    private var inFlightKeys: List<String> = emptyList()

    /**
     * 用户点开某部影片后暂停自动续批。
     *
     * <p>详情页取数与搜索抢同一批线程池/爬虫锁,继续在后台把剩余上百个源搜完只会拖慢详情加载。
     * 手动点"搜索更多来源"仍可继续(它会清掉这个标记)。
     */
    private var batchingPaused = false

    companion object {
        private val SEARCH_SEQ = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * 单站搜索超时。原 30s 与详情页换源的 SOURCE_SEARCH_TIMEOUT_MS(8s)口径差 3.75 倍,
         *  同一个"拿片名去一个站搜"的动作不该两套标准 —— 统一为 8s,慢站早点让位
         *
         * <p>2026-10-08:超时后**必须显式撤掉**这条源的网络请求([SearchLoader] 新增的
         * 按源取消),否则 UI 已经判它 Timeout、用户已看到进度,而请求仍在 OkGo 队列里跑 ——
         * 改词或退出搜索页后仍在途的请求会继续抢带宽与线程,正是"搜索越用越卡"的来源之一。
         */
        private const val SEARCH_TIMEOUT_MS = 8_000L

        private const val DOUBAN_HOT_URL =
            "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range="

        private const val HOT_SEARCH_LIMIT = 20

        private const val SUGGEST_URL = "https://suggest.video.iqiyi.com/?if=mobile&key="

        private const val SUGGEST_LIMIT = 20

        @Volatile
        var checkedSources: HashMap<String, String>? = null
            private set

        @Volatile
        private var checkedSourcesApiUrl: String? = null

        @JvmStatic
        fun clearCheckedSources() {
            checkedSources = null
            checkedSourcesApiUrl = null
        }

        @JvmStatic
        fun loadCheckedSources() {
            val selection = SearchSettings.currentSelection()
            if (selection != null) {
                checkedSources = HashMap<String, String>().apply { selection.forEach { put(it, "1") } }
                checkedSourcesApiUrl = KV.get(HawkConfig.API_URL, "")
                return
            }
            val all = SearchHelper.getSources()
            if (all.isEmpty()) {
                checkedSources = null
                checkedSourcesApiUrl = null
                return
            }
            checkedSources = all
            checkedSourcesApiUrl = KV.get(HawkConfig.API_URL, "")
        }

        @JvmStatic
        fun isCheckedSourcesStale(): Boolean {
            if (checkedSources == null) return true
            if (checkedSourcesApiUrl != KV.get(HawkConfig.API_URL, "")) return true
            return SearchHelper.isSelectionStale(checkedSources)
        }
    }

    init {
        org.greenrobot.eventbus.EventBus.getDefault().register(this)
        fetchHotSearch()
    }

    override fun onCleared() {
        org.greenrobot.eventbus.EventBus.getDefault().unregister(this)
        batchJob?.cancel()
        batchJob = null
        // ⚠️ 这里也必须撤搜索请求:原实现只撤了 "suggest"。搜索页退出(点开影片/返回)后
        // 若放任 "search" 继续,几十条在途请求会继续占用线程与带宽,把详情页取数拖慢 ——
        // 这正是 SearchViewModel 里 pauseBatching 想避免的,却漏了"页面真正关闭"这一路。
        cancelSearchRequests(inFlightKeys)
        try {
            OkGo.getInstance().cancelTag("suggest")
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "cancel suggest requests failed")
        }
        // Spider 不响应 interrupt,只能靠 stopAll 让 JS 层自己收尾
        try {
            com.github.catvod.crawler.JsLoader.stopAll()
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "stop spiders on clear failed")
        }
        pendingOutcomes.clear()
        pendingSources.clear()
        sourceStartAt.clear()
    }


    private fun fetchHotSearch() {
        scope.launch(Dispatchers.IO) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(java.util.Date())
            val cached = KV.get(HawkConfig.HOME_HOT, "")
            if (KV.get(HawkConfig.HOME_HOT_DAY, "") == today && cached.isNotEmpty()) {
                hotSearch.value = parseHotTitles(cached)
                return@launch
            }
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            OkGo.get<String>(DOUBAN_HOT_URL + year + "," + year)
                .headers("User-Agent", UA.random())
                .execute(object : AbsCallback<String>() {
                    override fun onSuccess(response: com.lzy.okgo.model.Response<String>) {
                        val body = response.body().orEmpty()
                        if (body.isNotEmpty()) {
                            KV.put(HawkConfig.HOME_HOT, body)
                            KV.put(HawkConfig.HOME_HOT_DAY, today)
                        }
                        hotSearch.value = parseHotTitles(body)
                    }

                    override fun convertResponse(response: okhttp3.Response): String =
                        response.body.string()

                    override fun onError(response: com.lzy.okgo.model.Response<String>) {
                        super.onError(response)
                        hotSearch.value = parseHotTitles(KV.get(HawkConfig.HOME_HOT, ""))
                    }
                })
        }
    }

    private fun parseHotTitles(json: String): List<String> = try {
        val arr = org.json.JSONObject(json).optJSONArray("data") ?: return emptyList()
        (0 until minOf(arr.length(), HOT_SEARCH_LIMIT))
            .mapNotNull { arr.optJSONObject(it)?.optString("title")?.takeIf { t -> t.isNotEmpty() } }
    } catch (_: Throwable) {
        emptyList()
    }

    fun fetchSuggest(text: String) {
        val seq = ++suggestSeq
        OkGo.get<String>(SUGGEST_URL + java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20"))
            .tag("suggest")
            .execute(object : AbsCallback<String>() {
                override fun onSuccess(response: com.lzy.okgo.model.Response<String>) {
                    if (seq != suggestSeq) return
                    suggest.value = parseSuggest(response.body().orEmpty())
                }

                override fun convertResponse(response: okhttp3.Response): String =
                    response.body.string()

                override fun onError(response: com.lzy.okgo.model.Response<String>) {
                    super.onError(response)
                }
            })
    }

    fun clearSuggest() {
        suggestSeq++
        suggest.value = emptyList()
    }

    private fun parseSuggest(json: String): List<String> = try {
        val arr = org.json.JSONObject(json).optJSONArray("data") ?: return emptyList()
        (0 until minOf(arr.length(), SUGGEST_LIMIT)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            val title = o.optString("title")
            when {
                name.isNotEmpty() -> name
                title.isNotEmpty() -> title
                else -> null
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun search(title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        val configured = KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT)
        if (configured != semaphorePermits) {
            semaphorePermits = configured
            semaphore = Semaphore(configured)
        }
        token = SEARCH_SEQ.incrementAndGet()
        searchedTitle.value = t
        matchMode.value = SearchSettings.matchMode()
        HistoryHelper.setSearchHistory(t)
        clearSuggest()
        stopPreviousSearch()
        val home = ApiConfig.get().getHomeSourceBean()
        val checked = checkedSources
        val pool = ApiConfig.get().getSourceBeanList()
            .filter { it.isSearchable() && (checked == null || checked.containsKey(it.key)) }
        // 被屏蔽的源不参与搜索(防滥用封禁机制,P2):放在排序与分批**之前** ——
        // 否则首轮"快速源"里还留着它,第一批照样要等它超时。整池被滤空时 fail-open 回退。
        val sources = SourceHealthFilter.filter(pool).sortedBy { it.key != home.key }
        // ⚠️ 诊断:搜索到底搜了哪些源。别删。
        //
        // 为什么需要它:无资源标记在搜索页**从来没有命中过**(真机实测 rail-filter
        // raw 与 shown 恒等),而标记表里 16 条全是 js_douban|<id>。
        // 要判断这是"js_douban 不参与搜索"(数据无交集,属正常)还是
        // "键口径对不上"(真 bug),唯一办法就是把参与搜索的源键打出来。
        // 站点栏是匿名标签(源1..源12),从 UI 上根本看不出哪一个是 js_douban。
        //
        // 复用 echo-unavailable 前缀(已在 FILE_LOG_PREFIXES 登记),
        // 不新增前缀 —— 新增就得记得同步登记,否则日志被过滤、又是白等一轮。
        val searchedKeys = sources.map { it.key }
        LOG.i(
            "echo-unavailable search-sources n=" + searchedKeys.size +
                " hasDouban=" + searchedKeys.any { it.contains("douban", ignoreCase = true) } +
                " keys=" + searchedKeys.take(12).joinToString(",")
        )
        arriveSeq = 0
        // 先全部登记为"排队中":站点栏一次性给全,列表才不会随分批插入而跳动
        results.value = sources.map { SourceResult(it.key, it.name.orEmpty(), ResultState.Queued, emptyList()) }
        sitesEmpty.value = sources.isEmpty()
        totalCount.value = sources.size
        searchableCount.value = ApiConfig.get().getSourceBeanList().count { it.isSearchable() }
        searchedCount.value = 0
        settledCount.value = 0
        // 查询变体:番号类关键词(带横杠/下划线等)除原词外还有一个紧凑形式。
        // ⚠️ 变体不再对每个源无条件双发:由 runSourceSearch 逐源推进,
        // 原词没有任何有效命中时才补紧凑形式(见 runSourceSearch)。
        queryVariants = SearchSettings.queryVariants(t)
        startedNonFastRound = false
        // 首轮只搜"首页源 + quickSearch 源",其余排到后面按需展开(2026-10-08)。
        // 队列顺序 = 快速源在前、延后源在后(降权源垫底),所以批大小策略能自然形成"先快后全"。
        //
        // 降权(2026-10-09):上一轮搜索里超时的源排到队尾。超时是"内容无关"的证据 ——
        // 与这次搜什么片无关,所以跨关键词同样成立。降权不放宽准确度:它们照搜,只是不占首轮额度,
        // 于是"首屏"由上一轮**答过话**的源组成,命中更快;死源在队尾被慢慢消化。
        val penalized = SourceHealthFilter.penalizedKeys()
        val (fastKeys, deferredKeys) = SearchBatchPolicy.splitFastRoundSources(sources, home.key, penalized)
        queuedSourceKeys = fastKeys + deferredKeys
        hasMore.value = queuedSourceKeys.isNotEmpty()
        batchingPaused = false
        LOG.i(
            "echo-searchplan token=" + token + " total=" + sources.size +
                " fast=" + fastKeys.size + " deferred=" + deferredKeys.size +
                " defer=" + penalized.size +
                " variants=" + queryVariants.size + " threads=" + semaphorePermits
        )
        if (sources.isEmpty()) {
            running.value = false
            return
        }
        loadNextBatch()
    }


    /** 收掉上一轮搜索:停批、停爬虫、撤销在途请求(与旧 search() 的收尾同口径) */
    private fun stopPreviousSearch() {
        batchJob?.cancel()
        batchJob = null
        batchInFlight = false
        val stopped = inFlightKeys
        inFlightKeys = emptyList()
        // 换代次:被取消那批的 finally 之后才跑,不能让它复位新批次的在跑标记
        batchSeq++
        try {
            JsLoader.stopAll()
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "JsLoader.stopAll failed, continue new search")
        }
        cancelSearchRequests(stopped)
        // 全部以"取不到结果"收尾:唤醒在等结果的协程,让它们从 withTimeoutOrNull/await 退出。
        // ⚠️ 必须 complete(null) 而不是 complete(Unit):新语义里 null 表示"没结果可依据",
        // 协程据此不再追加紧凑变体 —— 被换词打断的旧轮次不该再发新请求。
        for (entry in pendingOutcomes) {
            entry.value.complete(null)
        }
        for (entry in pendingSources) {
            entry.value.complete(Unit)
        }
        pendingOutcomes.clear()
        pendingSources.clear()
        sourceStartAt.clear()
        running.value = false
    }


    /**
     * 启动下一批来源(队首若干个,首轮上限 [SearchBatchPolicy.FAST_ROUND_SOURCE_LIMIT])。
     *
     * <p>全站搜索动辄数百源,一次全提交会让后台长时间占满线程与爬虫锁,还把用户点进详情后的取数
     * 一起拖慢。改为按需分批:首批只搜"首页源 + 快速源",用户往下滑或点"搜索更多来源"再续。
     */
    fun loadNextBatch() {
        val myToken = token
        if (myToken == 0 || batchInFlight) return
        // 手动续搜视为用户主动要求继续,清掉"点开影片后暂停"的标记
        batchingPaused = false
        val isFirstRound = !startedNonFastRound
        val size = SearchBatchPolicy.batchSizeFor(queuedSourceKeys, isFirstRound)
        val keys = SearchBatchPolicy.nextQueuedKeys(queuedSourceKeys, size)
        if (keys.isEmpty()) {
            running.value = false
            hasMore.value = false
            return
        }
        queuedSourceKeys = queuedSourceKeys.drop(keys.size)
        hasMore.value = queuedSourceKeys.isNotEmpty()
        searchedCount.value += keys.size
        markPending(keys)
        running.value = true
        batchInFlight = true
        inFlightKeys = keys
        val myBatch = ++batchSeq
        val tokenStr = myToken.toString()
        // 队列被消耗过就不是首轮了:下一批恢复 DEFAULT_BATCH_SIZE,把延后源一批批搜完
        if (queuedSourceKeys.isNotEmpty()) startedNonFastRound = true
        batchJob = scope.launch {
            try {
                coroutineScope {
                    keys.map { key ->
                        async {
                            semaphore.withPermit {
                                if (myToken != token) return@async
                                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                                val outcome = kotlinx.coroutines.CompletableDeferred<ResultState?>()
                                pendingSources[key] = done
                                pendingOutcomes[key] = outcome
                                sourceStartAt[key] = android.os.SystemClock.elapsedRealtime()
                                try {
                                    // 单源限时。超时后显式撤掉这条源的网络请求,
                                    // 避免"UI 已判超时、请求还在 OkGo 队列里跑"。
                                    val settled = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                                        val state = runSourceSearch(myToken, tokenStr, key, outcome)
                                        state
                                    }
                                    val finalState = settled ?: ResultState.Timeout
                                    settleSource(key, finalState)
                                    if (finalState == ResultState.Timeout) {
                                        cancelSourceRequests(key)
                                    }
                                } finally {
                                    pendingSources.remove(key, done)
                                    pendingOutcomes.remove(key, outcome)
                                    sourceStartAt.remove(key)
                                }
                            }
                        }
                    }.awaitAll()
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                // 换词/退出导致的整体取消:逐源已在 settleSource 之外统一回滚(见 cancelInFlightBatch),
                // 这里只把本批仍停在 Pending 的源标成"被取消",避免站点栏永远转圈
                if (myToken == token) markUnsettledAsQueued(keys)
                throw cancellation
            } catch (error: Throwable) {
                // ⚠️ 原来这里没有 catch:任一来源抛出未被 Loader 吸收的异常都会跳过
                // `running=false` 与自动续批,UI 会永远停在"搜索中",且无法判断出了什么事。
                // 现在按"本批全部失败"收口并留日志,批处理仍能正常推进到下一批。
                LOG.i("echo-searchplan batch-error token=" + myToken + " batch=" + myBatch + " ex=" + error)
                if (myToken == token) keys.forEach { key ->
                    val outcome = pendingOutcomes[key]
                    if (outcome != null && !outcome.isCompleted) {
                        settleSource(key, ResultState.Failed)
                    }
                }
            } finally {
                if (batchSeq == myBatch) {
                    batchInFlight = false
                    inFlightKeys = emptyList()
                }
            }
            if (myToken != token) return@launch
            running.value = false
            logBatchSummary(myBatch, keys.size)
            maybeContinueBatching()
        }
    }

    /**
     * 单个来源的请求序列:先发原词,**只有在没有任何有效命中时**才补发紧凑变体。
     *
     * <p>2026-10-08 改动的核心。原来是"每个源把全部变体一次性连发",于是番号类查询的
     * 请求量直接翻倍,且第二个请求的命中会被当作"该源已完成"而被丢弃 —— 白花请求还漏结果。
     * 现在逐个发:拿到 [ResultState.Done] 就收工;拿到 [ResultState.Empty] 且还有待发变体才继续。
     *
     * <p>失败/超时([ResultState.Failed] / [ResultState.Timeout])不再补变体:源本身没响应,
     * 再换个关键词问它一次仍是失败,纯属浪费。
     */
    private suspend fun runSourceSearch(
        myToken: Int,
        tokenStr: String,
        key: String,
        firstOutcome: kotlinx.coroutines.CompletableDeferred<ResultState?>,
    ): ResultState? = withContext(Dispatchers.IO) {
        val variants = queryVariants
        val first = variants.firstOrNull() ?: searchedTitle.value
        var outcome = firstOutcome
        searchCaller.getSearch(key, first, tokenStr)
        if (variants.size <= 1) return@withContext outcome.await()
        var current = outcome.await() ?: return@withContext null
        var index = 1
        while (current == ResultState.Empty && index < variants.size && myToken == token) {
            val next = variants[index]
            // 补发前重置完成信号:否则 await 会立刻拿到上一轮的 Empty,补发形同虚设
            outcome = kotlinx.coroutines.CompletableDeferred<ResultState?>()
            pendingOutcomes[key] = outcome
            searchCaller.getSearch(key, next, tokenStr)
            current = outcome.await() ?: return@withContext null
            index++
        }
        current
    }


    /**
     * 用户离开搜索页(点开影片):暂停自动续批,**并停掉在途的这一批**。
     *
     * <p>只"不再开新批"是不够的:一批 50 个源的搜索会占住爬虫装载锁与网络,
     * 而详情页取数要拿同一把锁 —— 不放掉的话详情页就一直卡在加载。
     *
     * <p>被停掉的源会放回队首并复位为 Queued:用户返回后站点栏不会永远转圈,
     * 下滑或点"搜索更多来源"即可继续。
     */
    fun pauseBatching() {
        batchingPaused = true
        cancelInFlightBatch()
    }

    private fun cancelInFlightBatch() {
        if (!batchInFlight) return
        batchJob?.cancel()
        batchJob = null
        batchInFlight = false
        // 换代次:被取消那批的 finally 之后才跑,不能让它复位新批次的标记
        batchSeq++
        val stopped = inFlightKeys
        inFlightKeys = emptyList()
        // 网络型搜索(OkGo)可以真正撤掉;爬虫型搜索不响应 interrupt,只能等它自己超时
        cancelSearchRequests(stopped)
        // 同 stopPreviousSearch:以"无结果"唤醒等待者,让它们不再追加变体
        for (entry in pendingOutcomes) {
            entry.value.complete(null)
        }
        for (entry in pendingSources) {
            entry.value.complete(Unit)
        }
        pendingOutcomes.clear()
        pendingSources.clear()
        sourceStartAt.clear()
        if (stopped.isNotEmpty()) {
            val stoppedSet = stopped.toHashSet()
            results.value = results.value.map { existing ->
                if (existing.sourceKey in stoppedSet && existing.state == ResultState.Pending) {
                    existing.copy(state = ResultState.Queued)
                } else {
                    existing
                }
            }
            val requeued = stopped.filter { key ->
                results.value.any { it.sourceKey == key && it.state == ResultState.Queued }
            }
            queuedSourceKeys = requeued + queuedSourceKeys
            searchedCount.value = (searchedCount.value - requeued.size).coerceAtLeast(0)
            hasMore.value = queuedSourceKeys.isNotEmpty()
        }
        running.value = false
    }

    /**
     * 把某个来源收口到终态,并记录耗时(2026-10-08)。
     *
     * <p>耗时是这个改造里最缺的东西:此前所有"搜索慢"的判断都只能靠体感,
     * 因为既没有每源耗时,也没有"这个源到底是没命中还是挂了"的区分。
     */
    private fun settleSource(sourceKey: String, state: ResultState) {
        val elapsed = sourceStartAt[sourceKey]?.let {
            android.os.SystemClock.elapsedRealtime() - it
        } ?: -1L
        var updated: SourceResult? = null
        results.value = results.value.map { existing ->
            if (existing.sourceKey != sourceKey || existing.state.isTerminal()) {
                existing
            } else {
                val next = existing.copy(state = state, elapsedMs = elapsed)
                updated = next
                next
            }
        }
        // 只在真正从"非终态"切进终态时计数,避免重复回包把进度算多
        if (updated != null) settledCount.value = (settledCount.value + 1).coerceAtMost(totalCount.value)
        recordSourceOutcome(sourceKey, state, updated)
    }

    /**
     * 把终态写进源健康台账(防滥用封禁机制,P1 记录侧)。
     *
     * <p>口径:
     * <ul>
     *   <li>**超时**一律记一次失败(需求规则 3);**请求失败**同类记一次 —— 两者都是"源没给出有效应答";</li>
     *   <li>**无命中(`Empty`)绝不记失败**:源没这部片不是源的错。但它是**"源还活着"**的证据,
     *       要反过来把连续超时计数清零(2026-10-09);</li>
     *   <li>**命中(`Done`)**同样清零连续超时,并登记"这些片名该源有收录",供首页判断"还有没有别的可用源";</li>
     *   <li>计数按**搜索词**归到"影片"维度:同一轮里所有源失败都算同一部片 ⇒ 一次断网/一次全网抖动
     *       最多凑到 1 部影片,达不到"≥2 部不同影片"的门槛,不会误封。</li>
     * </ul>
     */
    private fun recordSourceOutcome(sourceKey: String, state: ResultState, settled: SourceResult?) {
        when (state) {
            ResultState.Timeout ->
                notifyIfBanned(SourceHealthMemory.recordFail(SourceFailKind.SEARCH_TIMEOUT, sourceKey, searchedTitle.value))

            ResultState.Failed ->
                notifyIfBanned(SourceHealthMemory.recordFail(SourceFailKind.SEARCH_FAILED, sourceKey, searchedTitle.value))

            ResultState.Done -> {
                settled?.videos?.takeIf { it.isNotEmpty() }?.let { videos ->
                    SourceHealthMemory.recordCarriers(sourceKey, videos.mapNotNull { it.name })
                }
                // 答话了(哪怕只是答"我有这些")⇒ 连续超时归零
                SourceHealthMemory.recordAnswered(sourceKey)
            }

            // 答"我没有"也是答话:源是活的,只是这部片它没有
            ResultState.Empty -> SourceHealthMemory.recordAnswered(sourceKey)

            else -> Unit
        }
    }

    /** 触发封禁时广播一次,让首页(源清单/卡片可见性)立刻跟上 —— 不必等下次全网取数 */
    private fun notifyIfBanned(banned: Boolean) {
        if (!banned) return
        // 与本文件其它处一致,用全限定名(该 VM 没有 import EventBus)
        org.greenrobot.eventbus.EventBus.getDefault()
            .post(com.github.tvbox.osc.event.RefreshEvent(com.github.tvbox.osc.event.RefreshEvent.TYPE_SOURCE_BLOCK_CHANGE))
    }

    /** 批次异常/取消时,仍停在 Pending 的源复位为 Queued,免得站点栏一直转圈 */
    private fun markUnsettledAsQueued(keys: List<String>) {
        val keySet = keys.toHashSet()
        results.value = results.value.map { existing ->
            if (existing.sourceKey in keySet && existing.state == ResultState.Pending) {
                existing.copy(state = ResultState.Queued)
            } else {
                existing
            }
        }
    }

    /**
     * 撤掉某个来源的网络请求(2026-10-08)。
     *
     * <p>OkGo 的 tag 是**整批共享**的 {@code "search"},没有"按源撤"的粒度;
     * 而按源撤销必须精确到 key,否则会连带把同批其它源正在等的请求也撤掉 ——
     * 那正是原实现只能整批撤的原因。这里给每个源派生唯一 tag(见 [sourceRequestTag]),
     * 超时的那一个源单独撤掉,同批其它源照常等结果。
     *
     * <p>两条路径都走:OkGo 自己的 tag 登记,以及按 tag 扫一遍默认客户端的在途/排队调用
     * (复用 {@code OkHttp.cancel},与 {@code Connect.cancelByTag} 同一手法)。
     * 后者是兜底 —— 不依赖 OkGo 内部如何登记 tag,真机上"撤不掉"的表现同样是超时请求
     * 继续抢带宽,很难一眼看出是哪个环节没生效。
     *
     * <p>Spider 不响应 interrupt,撤不掉;这类源靠 [ResultState.Timeout] 与 JsLoader.stopAll 兜底。
     */
    private fun cancelSourceRequests(sourceKey: String) {
        val tag = sourceRequestTag(sourceKey)
        try {
            OkGo.getInstance().cancelTag(tag)
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "cancel source requests failed key=" + sourceKey)
        }
        try {
            com.github.catvod.net.OkHttp.cancel(OkGoHelper.getDefaultClient(), tag)
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "cancel source call failed key=" + sourceKey)
        }
    }

    /**
     * 撤掉整轮搜索的在途请求:逐源撤,而不是只撤一个整批 tag。
     *
     * <p>仍保留 {@code cancelTag("search")}:详情页那条"按片名反查其它源"的兜底搜索
     * ([DetailViewModel] 里那次 getSearch)用的就是 {@code "search"} 这个 tag,
     * 它的撤收出口在详情页自己那里;这里撤是为了保持"搜索页退出/换词时详情兜底搜索也停掉"
     * 的原有行为不因本次改动而失效。
     */
    private fun cancelSearchRequests(inFlight: Collection<String>) {
        inFlight.forEach { cancelSourceRequests(it) }
        try {
            OkGo.getInstance().cancelTag(SearchHelper.legacySearchRequestTag())
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "cancel search requests failed")
        }
    }

    /**
     * 每个源派生唯一请求 tag。
     *
     * <p>⚠️ 不要指望 {@code cancelTag("search")} 能前缀命中 {@code "search-xxx"}:
     * OkGo 的 tag 取消是**精确匹配**它自己那张登记表的 key,不是前缀扫描。
     * 整批撤销因此走 [cancelSearchRequests] 里显式的 {@code cancelTag("search")}
     * (给详情页兜底搜索用)加逐源撤销,而不是靠前缀。
     */
    private fun sourceRequestTag(sourceKey: String): String = SearchHelper.searchRequestTag(sourceKey)

    /** 本轮批次的耗时与命中分布,用于真机核对"第一批多久出结果、失败/超时各占多少" */
    private fun logBatchSummary(batch: Int, size: Int) {
        val settled = results.value.filter { it.state.isTerminal() }
        val hit = settled.count { it.videos.isNotEmpty() }
        val empty = settled.count { it.state == ResultState.Empty }
        val failed = settled.count { it.state == ResultState.Failed }
        val timeout = settled.count { it.state == ResultState.Timeout }
        val slowest = settled.filter { it.elapsedMs >= 0 }.maxByOrNull { it.elapsedMs }
        LOG.i(
            "echo-searchplan batch-summary token=" + token + " batch=" + batch + " size=" + size +
                " settled=" + settled.size + " hit=" + hit + " empty=" + empty +
                " failed=" + failed + " timeout=" + timeout +
                " slowestMs=" + (slowest?.elapsedMs ?: -1L) +
                " queued=" + queuedSourceKeys.size
        )
    }


    /**
     * 本批结束后是否自动续批:只在"至今一条结果都没有"时续。
     *
     * <p>有结果时把要不要继续交给用户 —— 这正是分批的意义;一条都没有则必须自动往下走,
     * 否则用户看到空屏,不会知道下面还有没搜的源。
     */
    private fun maybeContinueBatching() {
        if (batchingPaused || batchInFlight) return
        val hasAnyResult = results.value.any { it.videos.isNotEmpty() }
        if (SearchBatchPolicy.shouldAutoContinue(hasAnyResult, queuedSourceKeys.size)) {
            loadNextBatch()
        }
    }

    private fun markPending(keys: List<String>) {
        val keySet = keys.toHashSet()
        results.value = results.value.map { existing ->
            if (existing.sourceKey in keySet && existing.state == ResultState.Queued) {
                existing.copy(state = ResultState.Pending)
            } else {
                existing
            }
        }
    }

    @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.MAIN)
    fun onSearchResultEvent(event: com.github.tvbox.osc.event.RefreshEvent) {
        if (event.type == com.github.tvbox.osc.event.RefreshEvent.TYPE_VOD_UNAVAILABLE) {
            refreshAvailability()
            return
        }
        if (event.type != com.github.tvbox.osc.event.RefreshEvent.TYPE_SEARCH_RESULT) return
        val data = event.obj as? AbsXml ?: return
        val myToken = token
        if (data.searchToken != myToken.toString()) return
        val sourceKey = data.sourceKey ?: return
        if (results.value.none { it.sourceKey == sourceKey }) return
        val mode = matchMode.value
        val keyword = searchedTitle.value
        val raw = data.movie?.videoList.orEmpty()
        val videos = raw
            // 2026-10-08:别名一起参与匹配 —— 只看 name 会把"源站按别名/英文名搜到"的条目整批丢掉
            .filter { SearchSettings.matches(it.name, it.alias, keyword, mode) }
            // 相关度从高到低,让最贴合的条目浮到最前;同分再按"原名是否等于关键词"稳定排序
            .sortedWith(
                compareByDescending<Movie.Video> { SearchSettings.relevanceScore(it.name, it.alias, keyword) }
                    .thenByDescending { it.name?.trim() == keyword },
            )
        mergeResult(sourceKey, videos)
        // 终态由"过滤后还剩几条"决定:源站返回了一堆但全被匹配规则滤掉,等同于这个源没有该片。
        // ⚠️ 必须在 merge 之后完成,否则补发紧凑变体时会与并入逻辑抢同一个信号。
        pendingSources.remove(sourceKey)?.complete(Unit)
        pendingOutcomes.remove(sourceKey)?.complete(
            if (videos.isEmpty()) ResultState.Empty else ResultState.Done,
        )
    }

    /**
     * 把某一源新到的一批结果**并入**既有结果(按 id 去重后重排)。
     *
     * <p>之所以是"并入"而非"覆盖":一个源可能因查询变体(番号带/不带分隔符)回包多次,
     * 后到的变体结果不能把先到的冲掉。
     *
     * <p>⚠️ 这里**不写终态**:终态与 [settledCount] 的计数统一由 [settleSource] 负责。
     * 此前本方法直接置 [ResultState.Done],于是 [settleSource] 的"非终态才计数"守卫
     * 会把它跳过 —— 命中源的"已完成"数永远不涨,进度只统计失败/超时,数字与站点栏对不上。
     */
    private fun mergeResult(sourceKey: String, incoming: List<Movie.Video>) {
        val keyword = searchedTitle.value
        results.value = results.value.map { existing ->
            if (existing.sourceKey != sourceKey) return@map existing
            val merged = LinkedHashMap<String, Movie.Video>()
            existing.videos.forEach { v -> merged[v.id ?: v.name ?: ""] = v }
            incoming.forEach { v -> merged[v.id ?: v.name ?: ""] = v }
            val sorted = merged.values.sortedWith(
                compareByDescending<Movie.Video> { SearchSettings.relevanceScore(it.name, it.alias, keyword) }
                    .thenByDescending { it.name?.trim() == keyword },
            )
            // 首次到包才分配到达序号,后续变体并入不改动,保证竖排站点栏顺序稳定。
            // 判据用 arrivedAt 而不是 state:终态已不在本方法里设置,state 在并入时仍是 Pending,
            // 用它判断会让"第二个变体又重新编号一次"。
            val arrivedAt = if (existing.arrivedAt == Int.MAX_VALUE) ++arriveSeq else existing.arrivedAt
            existing.copy(
                videos = sorted,
                arrivedAt = arrivedAt,
            )
        }
    }


    /**
     * 详情页那边"这部片子当前真的没资源"被确认后,由 [com.github.tvbox.osc.ui.activity.DetailViewModel]
     * 通过 `availabilitySink` 回调过来 —— 让这里把已入库的结果按新标记重算一次,
     * 用户返回搜索页时那张误导人的海报就已经不在了(方案 B 第二层)。
     *
     * <p>为什么改 `results` 而不是让 UI 再算一次:`results` 是搜索结果的唯一真源,
     * UI 侧只是它的投影。在 ViewModel 里改一次,轨道视图与分组视图同时生效,不必两处同步。
     *
     * <p>幂等:同样的标记重复触发也只是把同样的条目再滤一次,不会累积副作用。
     */
    fun refreshAvailability() {
        val marks = AvailabilityMemory.activeMarks()
        //⚠️ 无条件打印,包括"标记表是空的"这种早退 —— 诊断日志的价值恰恰在于必然出现。
        // 之前挂在 `if (after != before)` 上,于是"过滤跑了但一条没滤掉"与"过滤压根没跑"
        // 在日志里长得一模一样,排查时无法区分(这正是 search-purge 实测 0 次的原因)。
        if (marks.isEmpty()) {
            LOG.i("echo-unavailable search-purge skip: marks empty rev=" + AvailabilityMemory.marksRevision)
            return
        }
        val before = results.value.fold(0) { acc, r -> acc + r.videos.size }
        val next = results.value.map { r ->
            r.copy(videos = r.videos.filterNot { AvailabilityHeuristic.mightBeUnavailable(it, marks) })
        }
        results.value = next
        val after = next.fold(0) { acc, r -> acc + r.videos.size }
        LOG.i(
            "echo-unavailable search-purge before=" + before + " after=" + after +
                " marks=" + marks.size + " rev=" + AvailabilityMemory.marksRevision
        )
    }

    private val searchCaller = SourceViewModel()
}
