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
import com.github.tvbox.osc.util.SearchHelper
import com.github.tvbox.osc.util.SearchSettings
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
     */
    enum class ResultState { Queued, Pending, Done }

    data class SourceResult(
        val sourceKey: String,
        val sourceName: String,
        val state: ResultState,
        val videos: List<Movie.Video>,
        val arrivedAt: Int = Int.MAX_VALUE,
    )

    val results = MutableStateFlow<List<SourceResult>>(emptyList())
    val running = MutableStateFlow(false)
    val searchedTitle = MutableStateFlow("")
    val matchMode = MutableStateFlow(SearchSettings.MatchMode.Smart)
    val sitesEmpty = MutableStateFlow(false)

    /** 还有没搜的来源(分批加载的游标);UI 据此显示"搜索更多来源"而不是"无结果" */
    val hasMore = MutableStateFlow(false)

    /** 已发起搜索的来源数 / 总来源数,用于"已搜索 x/y"进度 */
    val searchedCount = MutableStateFlow(0)
    val totalCount = MutableStateFlow(0)

    val hotSearch = MutableStateFlow<List<String>>(emptyList())

    val suggest = MutableStateFlow<List<String>>(emptyList())

    private var suggestSeq = 0

    private var token = 0
    private var arriveSeq = 0
    private var semaphorePermits = KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT)
    private var semaphore = Semaphore(semaphorePermits)
    private val pendingSources = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
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

        /** 单站搜索超时。原 30s 与详情页换源的 SOURCE_SEARCH_TIMEOUT_MS(8s)口径差 3.75 倍,
         *  同一个"拿片名去一个站搜"的动作不该两套标准 —— 统一为 8s,慢站早点让位 */
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
        try {
            OkGo.getInstance().cancelTag("suggest")
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "cancel suggest requests failed")
        }
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
        val sources = ApiConfig.get().getSourceBeanList()
            .filter { it.isSearchable() && (checked == null || checked.containsKey(it.key)) }
            .sortedBy { it.key != home.key }
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
        searchedCount.value = 0
        queuedSourceKeys = sources.map { it.key }
        hasMore.value = queuedSourceKeys.isNotEmpty()
        // 查询变体:番号类关键词(带横杠/下划线等)除原词外再查一个紧凑形式,源站库常以无分隔符存番号
        queryVariants = SearchSettings.queryVariants(t)
        batchingPaused = false
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
        inFlightKeys = emptyList()
        // 换代次:被取消那批的 finally 之后才跑,不能让它复位新批次的在跑标记
        batchSeq++
        try {
            JsLoader.stopAll()
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "JsLoader.stopAll failed, continue new search")
        }
        try {
            OkGo.getInstance().cancelTag("search")
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "cancel previous search requests failed")
        }
        for (entry in pendingSources) {
            entry.value.complete(Unit)
        }
        pendingSources.clear()
        running.value = false
    }

    /**
     * 启动下一批来源(队首 [SearchBatchPolicy.DEFAULT_BATCH_SIZE] 个)。
     *
     * <p>全站搜索动辄数百源,一次全提交会让后台长时间占满线程与爬虫锁,还把用户点进详情后的取数
     * 一起拖慢。改为按需分批:首批给"够看一屏"的量,用户往下滑或点"搜索更多来源"再续。
     */
    fun loadNextBatch() {
        val myToken = token
        if (myToken == 0 || batchInFlight) return
        // 手动续搜视为用户主动要求继续,清掉"点开影片后暂停"的标记
        batchingPaused = false
        val keys = SearchBatchPolicy.nextQueuedKeys(queuedSourceKeys)
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
        val variants = queryVariants
        batchJob = scope.launch {
            try {
                coroutineScope {
                    keys.map { key ->
                        async {
                            semaphore.withPermit {
                                if (myToken != token) return@async
                                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                                pendingSources[key] = done
                                try {
                                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                                        withContext(Dispatchers.IO) {
                                            for (variant in variants) {
                                                if (myToken != token) break
                                                searchCaller.getSearch(key, variant, tokenStr)
                                            }
                                        }
                                        done.await()
                                    }
                                } finally {
                                    pendingSources.remove(key, done)
                                }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                if (batchSeq == myBatch) {
                    batchInFlight = false
                    inFlightKeys = emptyList()
                }
            }
            if (myToken != token) return@launch
            running.value = false
            maybeContinueBatching()
        }
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
        // 网络型搜索(OkGo)可以真正撤掉;爬虫型搜索不响应 interrupt,只能等它自己超时
        try {
            OkGo.getInstance().cancelTag("search")
        } catch (ignored: Throwable) {
            LOG.d("SearchViewModel", "cancel in-flight search requests failed")
        }
        for (entry in pendingSources) {
            entry.value.complete(Unit)
        }
        pendingSources.clear()
        val back = inFlightKeys
        inFlightKeys = emptyList()
        if (back.isNotEmpty()) {
            val backSet = back.toHashSet()
            results.value = results.value.map { existing ->
                if (existing.sourceKey in backSet && existing.state == ResultState.Pending) {
                    existing.copy(state = ResultState.Queued)
                } else {
                    existing
                }
            }
            val requeued = back.filter { key ->
                results.value.any { it.sourceKey == key && it.state == ResultState.Queued }
            }
            queuedSourceKeys = requeued + queuedSourceKeys
            searchedCount.value = (searchedCount.value - requeued.size).coerceAtLeast(0)
            hasMore.value = queuedSourceKeys.isNotEmpty()
        }
        running.value = false
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
        pendingSources.remove(sourceKey)?.complete(Unit)
        val mode = matchMode.value
        val keyword = searchedTitle.value
        val videos = data.movie?.videoList.orEmpty()
            .filter { SearchSettings.matches(it.name, keyword, mode) }
            // 相关度从高到低,让最贴合的条目浮到最前;同分再按"原名是否等于关键词"稳定排序
            .sortedWith(
                compareByDescending<Movie.Video> { SearchSettings.relevanceScore(it.name, keyword) }
                    .thenByDescending { it.name?.trim() == keyword },
            )
        mergeResult(sourceKey, videos)
    }

    /**
     * 把某一源新到的一批结果**并入**既有结果(按 id 去重后重排)。
     *
     * <p>之所以是"并入"而非"覆盖":一个源可能因查询变体(番号带/不带分隔符)回包多次,
     * 后到的变体结果不能把先到的冲掉。
     */
    private fun mergeResult(sourceKey: String, incoming: List<Movie.Video>) {
        val keyword = searchedTitle.value
        results.value = results.value.map { existing ->
            if (existing.sourceKey != sourceKey) return@map existing
            val merged = LinkedHashMap<String, Movie.Video>()
            existing.videos.forEach { v -> merged[v.id ?: v.name ?: ""] = v }
            incoming.forEach { v -> merged[v.id ?: v.name ?: ""] = v }
            val sorted = merged.values.sortedWith(
                compareByDescending<Movie.Video> { SearchSettings.relevanceScore(it.name, keyword) }
                    .thenByDescending { it.name?.trim() == keyword },
            )
            // 首次到包才分配到达序号,后续变体并入不改动,保证竖排站点栏顺序稳定
            val arrivedAt = if (existing.state == ResultState.Pending) ++arriveSeq else existing.arrivedAt
            SourceResult(sourceKey, existing.sourceName, ResultState.Done, sorted, arrivedAt)
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
