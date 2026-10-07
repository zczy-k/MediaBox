package com.github.tvbox.osc.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.util.AvailabilityHeuristic
import com.github.tvbox.osc.util.AvailabilityMemory
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HomeSettings
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.sourcedata.SourceRuntimeState
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.sourcedata.observeAsFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject
import kotlin.coroutines.resume

class HomeViewModel : ViewModel() {
    /** 资源文案:ViewModel 无 Context,走 LanguageManager(Application 的 base 切语言不会重挂) */
    private fun str(resId: Int, vararg args: Any): String {
        val app = App.getInstance() ?: return ""
        return LanguageManager.localized(app).getString(resId, *args)
    }

    sealed interface PartitionState {
        data object Idle : PartitionState
        data object Loading : PartitionState
        data object Empty : PartitionState
        data object Ready : PartitionState
        data object Error : PartitionState
    }

    data class Partition(
        val sort: MovieSort.SortData,
        val state: PartitionState,
        val videos: List<Movie.Video>,
        val nextPage: Int,
        val maxPage: Int,
    ) {
        companion object {
            const val FIRST_PAGE = 1
        }

        val hasMore: Boolean get() = !(maxPage > 0 && nextPage > maxPage)
    }

    data class Rec(val state: PartitionState, val videos: List<Movie.Video>)

    val currentSource = MutableStateFlow<SourceBean?>(null)
    val sources = MutableStateFlow<List<SourceBean>>(emptyList())
    val allSorts = MutableStateFlow<List<MovieSort.SortData>>(emptyList())
    val sorts = MutableStateFlow<List<MovieSort.SortData>>(emptyList())
    val rec = MutableStateFlow(Rec(PartitionState.Loading, emptyList()))
    val partitions = MutableStateFlow<List<Partition>>(emptyList())

    val pageLoading = MutableStateFlow(true)
    private val sortsLoaded = MutableStateFlow(false)
    private val bootReady = MutableStateFlow(false)
    val pageErrorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** 分类取数失败(含一次自动重试仍失败):首页整页错误态,与真空区分 */
    val sortLoadFailed = MutableStateFlow(false)
    private var sortRetried = false
    private val listRetried = HashSet<String>()

    private val scope = viewModelScope
    private val sortViewModel = SourceViewModel()
    private val actionViewModel = SourceViewModel()
    private val recViewModel = SourceViewModel()
    private val loaders = HashMap<String, PartitionLoader>()
    private val loadSemaphore = Semaphore(2)
    private var loadGeneration = 0
    private var loadingSourceKey: String? = null
    private var watchdogJob: Job? = null

    var activeSortId: String? = null
        private set

    var defaultLiveLaunched = false
    var lastBackTime = 0L

    val actionMessages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        EventBus.getDefault().register(this)
        scope.launch { sortViewModel.sortResult.observeAsFlow().collect { onSortResult(it) } }
        scope.launch { recViewModel.sortResult.observeAsFlow().collect { onRecResult(it) } }
        scope.launch {
            actionViewModel.actionResult.observeAsFlow().collect { json ->
                val msg = json?.optString("msg").orEmpty()
                if (msg.isNotEmpty()) actionMessages.tryEmit(msg)
            }
        }
        sources.value = ApiConfig.get().getSwitchSourceBeanList()
        currentSource.value = ApiConfig.get().getHomeSourceBean()
        scope.launch {
            AppBootstrap.state.collect {
                bootReady.value = it is AppBootstrap.Boot.Ready
                if (it is AppBootstrap.Boot.Ready) loadHome()
            }
        }
        scope.launch {
            combine(bootReady, rec, sortsLoaded) { ready, r, loaded ->
                ready && loaded && r.state != PartitionState.Loading
            }.collect { ready ->
                if (ready && pageLoading.value) {
                    pageLoading.value = false
                }
            }
        }
    }

    override fun onCleared() {
        // 三个通道的收集器不用手工摘:onCleared 返回后框架才取消 viewModelScope,桥接器的 awaitClose 随之摘观察者
        EventBus.getDefault().unregister(this)
        val staleLoaders = ArrayList(loaders.values)
        loaders.clear()
        staleLoaders.forEach { it.release() }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_API_URL_CHANGE) {
            reload()
            return
        }
        // 详情页确认某部片没资源了:把已加载的卡片摘掉(见 refreshAvailability)
        if (event.type == RefreshEvent.TYPE_VOD_UNAVAILABLE) {
            refreshAvailability()
        }
    }

    fun reload() {
        LOG.i("echo--sort-reload")
        SourceRuntimeState.clearRuntimeCache()
        loadHome()
    }

    fun switchSource(bean: SourceBean) {
        LOG.i("echo--sort-switch: key=${bean.key}")
        ApiConfig.get().setSourceBean(bean)
        currentSource.value = bean
        loadHome()
    }

    fun loadHome() {
        sources.value = ApiConfig.get().getSwitchSourceBeanList()
        val home = ApiConfig.get().getHomeSourceBean()
        loadingSourceKey = if (home.key.isNullOrEmpty()) null else home.key
        LOG.i("echo--sort-loadHome: key=${loadingSourceKey} name=${home.name} srcCount=${sources.value.size}")
        currentSource.value = home
        pageLoading.value = true
        sortsLoaded.value = false
        sortLoadFailed.value = false
        sortRetried = false
        listRetried.clear()
        rec.value = Rec(PartitionState.Loading, emptyList())
        partitions.value = emptyList()
        val staleLoaders = ArrayList(loaders.values)
        loaders.clear()
        staleLoaders.forEach { it.release() }
        loadGeneration++
        armWatchdog()
        sortViewModel.getSort(loadingSourceKey, HomeSettings.current() == HomeSettings.HomeLayout.Horizontal)
    }

    private fun onHomeLoadTimeout() {
        val recLoading = rec.value.state == PartitionState.Loading
        val partitionLoading = partitions.value.any { it.state == PartitionState.Loading }
        if (!recLoading && !partitionLoading) return
        if (recLoading) {
            rec.value = Rec(PartitionState.Error, emptyList())
        }
        if (partitionLoading) {
            partitions.value = partitions.value.map { p ->
                if (p.state == PartitionState.Loading) p.copy(state = PartitionState.Error) else p
            }
        }
        pageErrorEvents.tryEmit(
            if (recLoading) str(R.string.home_load_failed)
            else str(R.string.home_load_partial_timeout)
        )
        pageLoading.value = false
    }

    fun retryPartition(partition: Partition) {
        if (partition.state != PartitionState.Error) return
        listRetried.remove(partition.sort.id)
        partitions.value = partitions.value.map {
            if (it.sort.id == partition.sort.id) it.copy(state = PartitionState.Loading) else it
        }
        requestPartition(partition, Partition.FIRST_PAGE)
    }

    /** 整页错误态(分类取数失败)手动重试 */
    fun retrySort() {
        val key = loadingSourceKey ?: return
        LOG.i("echo--sort-manual-retry: key=$key")
        sortLoadFailed.value = false
        sortRetried = true
        rec.value = Rec(PartitionState.Loading, emptyList())
        sortsLoaded.value = false
        pageLoading.value = true
        armWatchdog()
        sortViewModel.getSort(key, HomeSettings.current() == HomeSettings.HomeLayout.Horizontal)
    }

    fun ensureLoaded(sortId: String) {
        activeSortId = sortId
        val current = partitions.value.firstOrNull { it.sort.id == sortId } ?: return
        if (current.state != PartitionState.Idle) return
        partitions.value = partitions.value.map {
            if (it.sort.id == sortId) it.copy(state = PartitionState.Loading) else it
        }
        requestPartition(current, Partition.FIRST_PAGE)
    }

    fun onLayoutChanged() {
        if (HomeSettings.current() != HomeSettings.HomeLayout.Horizontal) return
        val idle = partitions.value.filter { it.state == PartitionState.Idle }
        if (idle.isNotEmpty()) {
            partitions.value = partitions.value.map {
                if (it.state == PartitionState.Idle) it.copy(state = PartitionState.Loading) else it
            }
            idle.forEach { p -> requestPartition(p, Partition.FIRST_PAGE) }
        }
        val key = loadingSourceKey
        val hasRecSort = allSorts.value.any { it.id == "my0" }
        if (key != null && hasRecSort && rec.value.videos.isEmpty() &&
            rec.value.state != PartitionState.Loading
        ) {
            rec.value = Rec(PartitionState.Loading, emptyList())
            recViewModel.getSort(key, true)
        }
    }

    private fun onSortResult(absXml: AbsSortXml?) {
        val key = loadingSourceKey
        if (key == null) {
            LOG.i("echo--sort-null-key: srcName=${currentSource.value?.name} srcCount=${sources.value.size} absXml=${absXml != null}")
            rec.value = Rec(PartitionState.Empty, emptyList())
            partitions.value = emptyList()
            sorts.value = emptyList()
            allSorts.value = emptyList()
            sortsLoaded.value = true
            return
        }
        if (absXml?.sourceKey != null && absXml.sourceKey != key) {
            LOG.i("echo--sort-stale-drop: key=$key absKey=${absXml.sourceKey}")
            return
        }

        if (absXml != null && absXml.loadFailed) {
            if (!sortRetried) {
                sortRetried = true
                LOG.i("echo--sort-retry: src=$key")
                sortViewModel.getSort(key, HomeSettings.current() == HomeSettings.HomeLayout.Horizontal)
                return
            }
            LOG.i("echo--sort-failed: src=$key")
            sortLoadFailed.value = true
            rec.value = Rec(PartitionState.Empty, emptyList())
            partitions.value = emptyList()
            sorts.value = emptyList()
            allSorts.value = emptyList()
            sortsLoaded.value = true
            return
        }

        LOG.i("echo--sort-result: src=$key hasClasses=${absXml?.classes?.sortList != null} sortSize=${absXml?.classes?.sortList?.size}")
        val adjusted = if (absXml?.classes?.sortList != null) {
            DefaultConfig.adjustSort(key, absXml.classes.sortList, true)
        } else {
            DefaultConfig.adjustSort(key, ArrayList(), true)
        }
        allSorts.value = adjusted

        val recSort = adjusted.firstOrNull { it.id == "my0" }
        if (recSort != null) {
            loadRec(absXml)
        } else {
            rec.value = Rec(PartitionState.Empty, emptyList())
        }

        val visible = adjusted.filter { it.id != "my0" }
        if (visible.isEmpty() && absXml != null && absXml.videoList.isNullOrEmpty()) {
            if (!sortRetried) {
                sortRetried = true
                LOG.i("echo--sort-empty-retry: src=$key sortSize=${absXml.classes?.sortList?.size}")
                val gen = loadGeneration
                scope.launch {
                    delay(2000)
                    if (loadingSourceKey == key && loadGeneration == gen) {
                        sortViewModel.getSort(key, HomeSettings.current() == HomeSettings.HomeLayout.Horizontal)
                    }
                }
                return
            }
            LOG.i("echo--sort-empty-final: src=$key sortSize=${absXml.classes?.sortList?.size}")
            sortLoadFailed.value = true
            rec.value = Rec(PartitionState.Empty, emptyList())
            partitions.value = emptyList()
            sorts.value = emptyList()
            allSorts.value = emptyList()
            sortsLoaded.value = true
            return
        }
        sorts.value = visible
        val vertical = HomeSettings.current() == HomeSettings.HomeLayout.Vertical
        val active = activeSortId?.takeIf { id -> visible.any { it.id == id } } ?: visible.firstOrNull()?.id
        activeSortId = active
        val newPartitions = visible.map { sort ->
            if (vertical && sort.id != active) {
                Partition(sort, PartitionState.Idle, emptyList(), Partition.FIRST_PAGE, 0)
            } else {
                Partition(sort, PartitionState.Loading, emptyList(), Partition.FIRST_PAGE, 0)
            }
        }
        partitions.value = newPartitions
        LOG.i("echo--sort-partitions: n=${newPartitions.size}")
        sortsLoaded.value = true
        newPartitions
            .filter { it.state == PartitionState.Loading }
            .forEach { p -> requestPartition(p, Partition.FIRST_PAGE) }
    }

    private fun loadRec(absXml: AbsSortXml?) {
        val raw = absXml?.videoList ?: emptyList()
        val videos = AvailabilityMemory.filterPlayable(raw)
        // 粗筛掉了多少要留痕:数字异常(=全被筛掉)说明关键词口径过宽,会误杀正常影片,
        // 这时能第一时间发现。详见 AvailabilityHeuristic 的「零误杀」约定。
        if (raw.isNotEmpty() && videos.isEmpty()) {
            LOG.i("echo-unavailable home-rec filtered-all n=" + raw.size + " sort=rec")
        }
        rec.value = if (videos.isEmpty()) Rec(PartitionState.Empty, videos) else Rec(PartitionState.Ready, videos)
    }

    private fun onRecResult(absXml: AbsSortXml?) {
        val key = loadingSourceKey ?: return
        if (absXml?.sourceKey != null && absXml.sourceKey != key) return
        loadRec(absXml)
    }

    private class LoaderResult(val stale: Boolean, val absXml: AbsXml?)

    private fun armWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(20_000)
            onHomeLoadTimeout()
        }
    }

    private fun requestPartition(current: Partition, page: Int) {
        val generation = loadGeneration
        val loader = loaders.getOrPut(current.sort.id) { PartitionLoader(current.sort) }
        scope.launch {
            loadSemaphore.withPermit {
                if (generation != loadGeneration || loader.released) return@withPermit
                armWatchdog()
                val result = suspendCancellableCoroutine<LoaderResult> { cont ->
                    loader.request(page) { r -> if (cont.isActive) cont.resume(r) }
                }
                if (!result.stale) {
                    applyPartitionResult(current.sort.id, page, result.absXml)
                }
            }
        }
    }

    private fun applyPartitionResult(sortId: String, page: Int, absXml: AbsXml?) {
        if (absXml == null && page == Partition.FIRST_PAGE) {
            if (listRetried.add(sortId)) {
                LOG.i("echo--list-retry: sort=$sortId pg=$page")
                val current = partitions.value.firstOrNull { it.sort.id == sortId } ?: return
                requestPartition(current, Partition.FIRST_PAGE)
            } else {
                LOG.i("echo--list-failed: sort=$sortId pg=$page")
                partitions.value = partitions.value.map { p ->
                    if (p.sort.id == sortId) p.copy(state = PartitionState.Error) else p
                }
            }
            return
        }
        val rawVideos = absXml?.movie?.videoList ?: emptyList()
        // 方案 B 第一层:粗筛掉"看起来没资源"的卡片(产品要求:没资源的片子不该出现在首页)。
        // 在 ViewModel 层过滤而不是 UI 层 —— 首页有三个渲染点(推荐位/分区/加载更多),
        // 放这里一次全覆盖,UI 拿到的永远是干净列表。
        val videos = AvailabilityMemory.filterPlayable(rawVideos)
        if (rawVideos.isNotEmpty() && videos.isEmpty()) {
            LOG.i("echo-unavailable home-list filtered-all n=" + rawVideos.size + " sort=$sortId")
        }
        LOG.i("echo--list-result: src=${loadingSourceKey} sort=$sortId pg=$page n=${videos.size}")
        val maxPage = absXml?.movie?.pagecount ?: 0
        partitions.value = partitions.value.map { p ->
            if (p.sort.id != sortId) {
                p
            } else if (videos.isEmpty() && page == Partition.FIRST_PAGE) {
                Partition(p.sort, PartitionState.Empty, emptyList(), Partition.FIRST_PAGE, maxPage)
            } else {
                val merged = if (page == 0) videos else p.videos + videos
                Partition(p.sort, PartitionState.Ready, merged, page + 1, maxPage)
            }
        }
    }

    fun loadMorePartition(partition: Partition) {
        if (partition.state != PartitionState.Ready || !partition.hasMore) return
        val loader = loaders[partition.sort.id] ?: return
        if (loader.busy) return
        requestPartition(partition, partition.nextPage)
    }

    /**
     * 详情页确认"这部片子当前真的没资源"后回调过来(见 `DetailViewModel.availabilitySink`),
     * 把首页已加载的推荐位与分区按新标记重算一次 —— 用户返回首页时那张海报已经不在了。
     *
     * <p>只动**已加载**的内存列表,不重新请求:被标记的片子下次刷新自然不会出现,
     * 这里只保证"当前这一屏"立刻变干净,代价是零网络请求。
     *
     * <p>幂等:重复触发只是把同样的条目再滤一遍。
     */
    fun refreshAvailability() {
        val marks = AvailabilityMemory.activeMarks()
        // ⚠️ 无条件打印,包括"标记表是空的"这种早退 —— 诊断日志的价值恰恰在于必然出现。
        // 之前 `if (purged > 0)` / `if (marks.isEmpty()) return` 两处静默,
        // 于是"过滤跑了但没滤掉"和"过滤没跑"在日志里无法区分。
        if (marks.isEmpty()) {
            LOG.i("echo-unavailable home-purge skip: marks empty rev=" + AvailabilityMemory.marksRevision)
            return
        }
        var purged = 0
        val curRec = rec.value
        val recLeft = curRec.videos.filterNot {
            val hit = AvailabilityHeuristic.mightBeUnavailable(it, marks)
            if (hit) purged++
            hit
        }
        if (recLeft.size != curRec.videos.size) {
            rec.value = if (recLeft.isEmpty()) {
                Rec(PartitionState.Empty, recLeft)
            } else {
                Rec(curRec.state, recLeft)
            }
        }
        val next = partitions.value.map { p ->
            val left = p.videos.filterNot { AvailabilityHeuristic.mightBeUnavailable(it, marks) }
            purged += p.videos.size - left.size
            if (left.size == p.videos.size) p else p.copy(videos = left)
        }
        partitions.value = next
        LOG.i(
            "echo-unavailable home-purge n=" + purged +
                " marks=" + marks.size +
                " rec=" + curRec.videos.size + "->" + recLeft.size +
                " parts=" + partitions.value.size +
                " rev=" + AvailabilityMemory.marksRevision
        )
    }

    fun applyFilter(partition: Partition, filterSelect: Map<String, String>) {
        partition.sort.filterSelect = HashMap(filterSelect)
        partitions.value = partitions.value.map {
            if (it.sort.id == partition.sort.id) {
                Partition(it.sort, PartitionState.Loading, emptyList(), Partition.FIRST_PAGE, 0)
            } else {
                it
            }
        }
        requestPartition(partition.copy(sort = partition.sort), Partition.FIRST_PAGE)
    }

    fun handleAction(video: Movie.Video) {
        actionViewModel.action(video.sourceKey, video.action)
    }

    fun refreshPartitions() {
        val vertical = HomeSettings.current() == HomeSettings.HomeLayout.Vertical
        val active = activeSortId
        val targets = if (vertical) {
            partitions.value.filter { it.sort.id == active }
        } else {
            partitions.value
        }
        partitions.value = partitions.value.map { p ->
            if (targets.any { it.sort.id == p.sort.id }) {
                Partition(p.sort, PartitionState.Loading, emptyList(), Partition.FIRST_PAGE, 0)
            } else {
                p
            }
        }
        targets.forEach { p -> requestPartition(p, Partition.FIRST_PAGE) }
    }

    private inner class PartitionLoader(val sort: MovieSort.SortData) {
        private val svm = SourceViewModel()
        @Volatile
        private var pending: ((LoaderResult) -> Unit)? = null

        @Volatile
        var busy: Boolean = false
            private set

        @Volatile
        var released: Boolean = false
            private set

        /**
         * 收集作用域随本 loader 生命周期:release() 取消它即摘掉观察者(等价旧 removeObserver)。
         *
         * ⚠️ 两个坑都在这一行:①`CoroutineScope(viewModelScope.coroutineContext)` 会**复用** VM 的
         * SupervisorJob,`cancel()` 就会把整个 viewModelScope 一起杀掉(而 `loadHome()` 每次换源都
         * release 旧 loader ⇒ 首页永久 loading);②context 里若没有 Dispatcher,`launch` 兜底用
         * `Dispatchers.Default`,而 `observeForever` 有主线程断言 ⇒ 直接抛。故显式 `SupervisorJob(parent)`
         * 造子 Job + `Dispatchers.Main.immediate`。
         */
        private val observeScope = CoroutineScope(
            SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Main.immediate
        )

        init {
            observeScope.launch {
                svm.listResult.observeAsFlow().collect { abs ->
                    val current = pending
                    pending = null
                    busy = false
                    current?.invoke(LoaderResult(stale = false, absXml = abs))
                }
            }
        }

        fun request(page: Int, onDone: (LoaderResult) -> Unit) {
            pending?.invoke(LoaderResult(stale = true, absXml = null))
            pending = onDone
            busy = true
            svm.getList(sort, page)
        }

        fun release() {
            released = true
            pending?.invoke(LoaderResult(stale = true, absXml = null))
            pending = null
            busy = false
            // 释放即弃用:observeScope 是一次性的(取消后不能再 launch),所以调用方必须**先把它从
            // `loaders` 表里摘掉/清表再 release** —— 否则后续 requestPartition 会 getOrPut 取回它,
            // pending 永远等不到回包、该分区永停 Loading。当前两个调用点(loadHome/onCleared)都是先 clear 再 release。
            observeScope.cancel()
        }
    }
}
