package com.github.tvbox.osc.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.catvod.crawler.JsLoader
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
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

    enum class ResultState { Pending, Done }

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

    val hotSearch = MutableStateFlow<List<String>>(emptyList())

    val suggest = MutableStateFlow<List<String>>(emptyList())

    private var suggestSeq = 0

    private var token = 0
    private var arriveSeq = 0
    private var semaphorePermits = KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT)
    private var semaphore = Semaphore(semaphorePermits)
    private val pendingSources = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
    private val scope = viewModelScope

    companion object {
        private val SEARCH_SEQ = java.util.concurrent.atomic.AtomicInteger(0)

        private const val SEARCH_TIMEOUT_MS = 30_000L

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
        val myToken = token
        val tokenStr = myToken.toString()
        searchedTitle.value = t
        matchMode.value = SearchSettings.matchMode()
        HistoryHelper.setSearchHistory(t)
        clearSuggest()
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
        val home = ApiConfig.get().getHomeSourceBean()
        val checked = checkedSources
        val sources = ApiConfig.get().getSourceBeanList()
            .filter { it.isSearchable() && (checked == null || checked.containsKey(it.key)) }
            .sortedBy { it.key != home.key }
        arriveSeq = 0
        results.value = sources.map { SourceResult(it.key, it.name.orEmpty(), ResultState.Pending, emptyList()) }
        sitesEmpty.value = sources.isEmpty()
        if (sources.isEmpty()) {
            running.value = false
            return
        }
        running.value = true
        // 查询变体:番号类关键词(带横杠/下划线等)除原词外再查一个紧凑形式,源站库常以无分隔符存番号
        val queryVariants = SearchSettings.queryVariants(t)
        scope.launch {
            coroutineScope {
                sources.map { bean ->
                    async {
                        semaphore.withPermit {
                            if (myToken != token) return@async
                            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                            pendingSources[bean.key] = done
                            try {
                                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                                    withContext(Dispatchers.IO) {
                                        for (variant in queryVariants) {
                                            if (myToken != token) break
                                            searchCaller.getSearch(bean.key, variant, tokenStr)
                                        }
                                    }
                                    done.await()
                                }
                            } finally {
                                pendingSources.remove(bean.key, done)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (myToken == token) running.value = false
        }
    }

    @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.MAIN)
    fun onSearchResultEvent(event: com.github.tvbox.osc.event.RefreshEvent) {
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

    private val searchCaller = SourceViewModel()
}
