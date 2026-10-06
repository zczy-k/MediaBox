package com.github.tvbox.osc.ui.activity

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.RoomDataManger
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.DeviceCapability
import com.github.tvbox.osc.player.LineQualitySelector
import com.github.tvbox.osc.player.PlaybackSession
import com.github.tvbox.osc.player.VideoQualityMemory
import com.github.tvbox.osc.player.VideoQualityProbe
import com.github.tvbox.osc.util.UA
import com.github.tvbox.osc.util.EpisodeTotals
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.HistoryWriter
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.SearchHelper
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.sourcedata.observeAsFlow
import com.lzy.okgo.OkGo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class DetailViewModel : ViewModel() {

    /** 资源文案:ViewModel 无 Context,走 LanguageManager(Application 的 base 切语言不会重挂) */
    private fun str(resId: Int, vararg args: Any): String {
        val app = App.getInstance() ?: return ""
        return LanguageManager.localized(app).getString(resId, *args)
    }

    sealed interface PageState {
        data object Loading : PageState
        data class Empty(val msg: String? = null) : PageState
        data object Ready : PageState
    }

    data class SourceChip(val key: String, val name: String)

    val pageState = MutableStateFlow<PageState>(PageState.Loading)
    val revision = MutableStateFlow(0)
    val fullScreen = MutableStateFlow(false)
    val rotating = MutableStateFlow(false)
    val playSignal = MutableStateFlow(0)
    val collected = MutableStateFlow(false)
    val qualityOptions = MutableStateFlow<List<String>>(emptyList())
    val qualitySelected = MutableStateFlow(0)
    val sourceChips = MutableStateFlow<List<SourceChip>>(emptyList())
    val sourcesSearching = MutableStateFlow(false)
    val relatedVideos = MutableStateFlow<List<Movie.Video>>(emptyList())
    val episodeSheet = MutableStateFlow(false)
    val toastEvent = MutableStateFlow<String?>(null)
    val finishEvent = MutableStateFlow(false)

    /**
     * 播放层下行指令(V2 起 VM 不再持 `PlayContainer` 引用)。用带缓冲的 Channel 而非 SharedFlow:
     * `initFromIntent` 里的首条 `applyTarget` 早于 `setContent`,缓冲才不丢且保持旧直调顺序。
     * 单消费者,勿加第二个;`StopForSourceSwitch` 无自守卫(安全性来自"发出点必是用户点击换源")。
     */
    private val playbackCommandChannel = Channel<PlaybackCommand>(Channel.BUFFERED)
    val playbackCommands: Flow<PlaybackCommand> = playbackCommandChannel.receiveAsFlow()

    var vodInfo: VodInfo? = null; private set
    var previewVodInfo: VodInfo? = null; private set
    var vodId = ""; private set
    var sourceKey = ""; private set
    var firstsourceKey = ""; private set

    private var manualLineSwitchPending = false

    private var vodName = ""
    private var vodPicture = ""
    private var fromCollect = false

    private val sourceViewModel = SourceViewModel()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val navStack = DetailNavStack()
    private var searchJob: Job? = null
    private var detailBuildToken = 0

    /**
     * 详情请求代次(V4),替代原「换 `SourceViewModel` 实例」的迟到回包隔离:每次发起新的内容请求
     * (换片/换源/重试)自增并随请求下传,回包代次不符即丢。**fallback 换站不自增** ——
     * 同一代内的候选站谁先回都算当前。
     */
    private var detailRequestToken = 0

    private val fallbackCandidates = ArrayList<Movie.Video>()
    private val candidateKeys = HashSet<String>()
    private val triedKeys = HashSet<String>()
    private val usedSourceKeys = HashSet<String>()
    /**
     * 换源候选池上界:聚合订阅可达数百站(实测某订阅 673 个 site),全量排队会把换源拖到超时;
     * 只取站点序前 N,其余候选靠搜索回包事件增量补进来。
     */
    private val fallbackPoolCap = 120
    private val semaphore = Semaphore(SOURCE_SEARCH_CONCURRENCY)
    private val pendingSearchDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val searchCaller = SourceViewModel()
    private var fallbackKeepCurrentDetail = false
    private var fallbackLoadingCandidate = false
    private var fallbackActive = false
    private var fallbackAutoSwitch = false
    private var fallbackEpisode: VodInfo.VodSeries? = null
    private var fallbackEpisodeIndex = -1
    private var detailTimeoutScheduled = false

    private class SwitchSnapshot(
        val vodInfo: VodInfo,
        val vodId: String,
        val sourceKey: String,
        val firstsourceKey: String,
        val vodName: String,
        val vodPicture: String,
    )

    private var switchSnapshot: SwitchSnapshot? = null

    private var searchToken = 0
    private var searchTitle = ""

    init {
        EventBus.getDefault().register(this)
        // 单实例 + 代次(D2/V4):不再换 SourceViewModel 实例,迟到回包由回包自带的代次丢弃
        viewModelScope.launch {
            sourceViewModel.detailResult.observeAsFlow().collect { data ->
                if (DetailResponseGuard.isCurrent(detailRequestToken, data?.detailToken)) onDetailResult(data)
            }
        }
    }

    fun initFromIntent(intent: Intent?) {
        if (vodId.isNotEmpty()) return
        val target = parseTarget(intent) ?: return
        navStack.push(target)
        applyTarget(target)
    }

    /** 复用实例进入新片(详情页推荐卡片):入栈并加载;重复点同一部不重载 */
    fun pushTargetFromIntent(intent: Intent?) {
        val target = parseTarget(intent) ?: return
        if (!navStack.push(target)) return
        applyTarget(target)
    }

    /** 返回上一部;栈上限为单部时恒为 false,调用方据此走退出页面 */
    fun backToPreviousTarget(): Boolean {
        val previous = navStack.pop() ?: return false
        applyTarget(previous)
        return true
    }

    private fun parseTarget(intent: Intent?): DetailNavStack.Target? {
        val bundle = intent?.extras ?: return null
        return DetailNavStack.Target(
            vodId = bundle.getString("id", "").orEmpty(),
            sourceKey = bundle.getString("sourceKey", "").orEmpty(),
            title = bundle.getString("title", "").orEmpty(),
            picture = bundle.getString("picture", "").orEmpty(),
            fromCollect = bundle.getBoolean("collect", false),
        )
    }

    private fun applyTarget(target: DetailNavStack.Target) {
        cancelInFlightContent()
        resetContentState()
        sendCommand(PlaybackCommand.StopForContentSwitch)
        sendCommand(PlaybackCommand.SetEpisodeSheetOpen(false))
        fromCollect = target.fromCollect
        vodName = target.title
        vodPicture = target.picture
        loadDetail(target.vodId, target.sourceKey)
        LOG.i("echo-detail-open collect=$fromCollect key=$sourceKey id=$vodId")
        if (vodName.isNotEmpty()) startSourceSearch()
    }

    /** 换片时收掉上一部在途的聚合搜索,并让旧 token 立即失效(搜索回包仍靠 token 失配兜底) */
    private fun cancelInFlightContent() {
        searchJob?.cancel()
        searchJob = null
        pendingSearchDone.values.forEach { it.complete(Unit) }
        pendingSearchDone.clear()
        cancelDetailTimeout()
        searchToken = SEARCH_SEQ.incrementAndGet()
    }

    /** 内容级状态随片走:不清会串味(推荐位/换源候选/清晰度/换源快照都属上一部) */
    private fun resetContentState() {
        vodInfo = null
        previewVodInfo = null
        switchSnapshot = null
        firstsourceKey = ""
        searchTitle = ""
        manualLineSwitchPending = false
        collected.value = false
        relatedVideos.value = emptyList()
        // 旧协程被取消后不会回写,不清会永远停在"搜索中"
        sourcesSearching.value = false
        qualityOptions.value = emptyList()
        qualitySelected.value = 0
        episodeSheet.value = false
        toastEvent.value = null
        finishEvent.value = false
        pageState.value = PageState.Loading
        fallbackEpisode = null
        fallbackEpisodeIndex = -1
        usedSourceKeys.clear()
        resetEngineState(keepChips = false)
    }

    /** 进/退全屏。事实当帧由 UI 传入(见 `DetailPlaybackFacts`);退全屏也走这里:`rotating` 要按目标方向重算 */
    fun onFullScreenToggleRequested(requested: Boolean, facts: DetailPlaybackFacts) {
        if (requested) {
            val reason = DetailFullScreenGate.refusalReason(
                pageState.value,
                loadingText = { str(R.string.detail_content_not_ready) },
                emptyText = { str(R.string.detail_empty_source) },
            )
            if (reason != null) {
                toastEvent.value = reason
                return
            }
        }
        val (full, rotating) = DetailPlaybackCommands.fullScreenState(requested, facts)
        fullScreen.value = full
        this.rotating.value = rotating
    }

    fun bumpRevision() {
        revision.value += 1
    }

    fun requestPlay() {
        playSignal.value += 1
    }

    private fun consumeManualLineSwitch(): Boolean {
        val pending = manualLineSwitchPending
        manualLineSwitchPending = false
        return pending
    }

    /** 面板开合同时投影给播放底栏(冻结自动收起,见 `PlayerUiState.overlayPanelOpen`) */
    fun showEpisodeSheet() {
        episodeSheet.value = true
        sendCommand(PlaybackCommand.SetEpisodeSheetOpen(true))
    }

    fun dismissEpisodeSheet() {
        episodeSheet.value = false
        sendCommand(PlaybackCommand.SetEpisodeSheetOpen(false))
    }

    fun clearToast() {
        toastEvent.value = null
    }

    fun consumeFinish() {
        finishEvent.value = false
    }

    private fun loadDetail(vid: String, key: String) {
        // 代次必须在任何早退之前开新的一代:否则"源不在订阅/id 不可播"这类换片只改了内容没换代,
        // 上一代的迟到回包会被守卫判成"当前",把旧片顶到新页上(V4 审查轮抓到的回归)
        val requestToken = nextDetailRequestToken()
        vodId = vid.orEmpty()
        sourceKey = key.orEmpty()
        firstsourceKey = sourceKey
        usedSourceKeys.add(firstsourceKey)
        collected.value = RoomDataManger.isVodCollect(sourceKey, vodId)
        if (DetailResponseGuard.isUnloadableTarget(vodId, ApiConfig.get().getSource(sourceKey) == null)) {
            onDetailUnavailable()
            return
        }
        pageState.value = PageState.Loading
        sourceViewModel.getDetail(sourceKey, vodId, false, requestToken)
    }

    /**
     * 开新的一代并返回它。只有"发起新的内容请求"才自增:换片、换源、重试(含 [loadDetail] 的早退分支);
     * fallback 候选站(见 [loadDetailInternal])沿用当前代次。
     */
    private fun nextDetailRequestToken(): Int = ++detailRequestToken

    fun retry() {
        if (vodId.isEmpty()) return
        loadDetail(vodId, sourceKey)
        if (searchTitle.isNotEmpty() && !sourcesSearching.value) startSourceSearch()
    }

    private fun onDetailUnavailable() {
        if (fallbackActive) {
            fallbackLoadingCandidate = false
            loadNextFallbackCandidate()
            return
        }
        ensureSourceSearchRunning()
        if (!startFallbackIfNeeded(auto = true)) {
            if (!rollbackManualSwitch()) enterEmpty()
        }
    }

    /**
     * 保证聚合搜索已在跑,再让 [startFallbackIfNeeded] 接管。
     *
     * <p>兜底候选只能来自聚合搜索,而它排在 loadDetail 之后启动。若不先补这一下,
     * [loadNextFallbackCandidate] 会在"候选为空且 sourcesSearching 为 false"时走
     * [finishFallbackWithoutResult] → [resetEngineState],把刚设好的 `fallbackActive` /
     * `fallbackAutoSwitch` 一并清掉 —— 自动接管当场失效,页面停在"暂无片源"。
     *
     * <p>标题取值必须与 [startFallbackIfNeeded] 完全一致:两处不同会让
     * [onSearchResultEvent] 的同名过滤(`it.name == searchTitle`)全部落空,候选永远进不了列表。
     */
    private fun ensureSourceSearchRunning() {
        if (sourcesSearching.value) return
        val title = (if (vodInfo?.name.isNullOrEmpty()) vodName else vodInfo?.name).orEmpty().trim()
        if (title.isEmpty()) return
        searchTitle = title
        startSourceSearch()
    }

    fun onDetailResult(absXml: AbsXml?) {
        if (fallbackActive && !fallbackLoadingCandidate) return
        if (absXml != null && !absXml.sourceKey.isNullOrEmpty() && absXml.sourceKey != sourceKey) return
        val videoList = absXml?.movie?.videoList
        val detailToken = ++detailBuildToken
        if (videoList != null && videoList.isNotEmpty()) {
            val wasFallback = fallbackLoadingCandidate
            if (fallbackLoadingCandidate) {
                fallbackLoadingCandidate = false
                cancelDetailTimeout()
            }
            if (wasFallback) {
                val fallbackSource = ApiConfig.get().getSource(sourceKey)
                toastEvent.value = str(R.string.detail_switch_site, fallbackSource?.name ?: sourceKey)
            }
            if (isSourceErrorMsg(absXml.msg)) {
                if (!rollbackManualSwitch(absXml.msg)) {
                    toastEvent.value = absXml.msg
                    enterEmpty(absXml.msg)
                }
                return
            }
            val mVideo = videoList[0]
            mVideo.id = vodId
            if (mVideo.name.isNullOrEmpty()) mVideo.name = vodName
            if (mVideo.name.isNullOrEmpty()) mVideo.name = "TVBox"
            if ((mVideo.pic == null || mVideo.pic.isEmpty()) && vodPicture.isNotEmpty()) {
                mVideo.pic = vodPicture
            }
            val info = VodInfo()
            info.setVideo(mVideo)
            info.sourceKey = mVideo.sourceKey
            sourceKey = mVideo.sourceKey ?: sourceKey

            // 无痕:旧记录连读都不读 —— 它只剩"看到第几集/哪条线路/该片播放配置"这些痕迹,读了等于没隐身
            val recordKey = sourceKey
            val recordId = vodId
            viewModelScope.launch {
                val record = withContext(Dispatchers.IO) {
                    if (HistoryHelper.isIncognito()) null else RoomDataManger.getVodInfo(recordKey, recordId)
                }
                if (detailToken != detailBuildToken || sourceKey != recordKey || vodId != recordId) return@launch
                if (record != null) {
                    info.playIndex = maxOf(record.playIndex, 0)
                    info.playFlag = record.playFlag
                    info.playerCfg = record.playerCfg
                    info.reverseSort = record.reverseSort
                } else {
                    info.playIndex = 0
                    info.playFlag = null
                    info.playerCfg = ""
                    info.reverseSort = false
                }
                if (info.reverseSort) info.reverse()
                if (info.playFlag == null || info.seriesMap?.containsKey(info.playFlag) != true) {
                    // 画质优选第一层(同步,零网络零延迟):先用**已记忆的实测分辨率**选,
                    // 而不是站点给的第一个 flag —— 后者常常是 480P。记忆缺失时保持站点原序。
                    val cap = App.getInstance()?.let { DeviceCapability.capHeight(it) } ?: 0
                    val siteOrder = info.seriesMap?.keys?.toList().orEmpty()
                    val remembered = VideoQualityMemory.lookupAll(recordKey, recordId, siteOrder)
                    info.playFlag = LineQualitySelector.pickFromMemory(remembered, cap)
                        ?: siteOrder.firstOrNull()
                }
                restoreFallbackEpisode(info)
                resetEngineState(keepChips = true)
                val playingList = info.seriesMap?.get(info.playFlag)
                if (!playingList.isNullOrEmpty()) {
                    info.playIndex = info.playIndex.coerceIn(0, playingList.size - 1)
                    for (flag in info.seriesFlags) {
                        flag.selected = flag.name == info.playFlag
                    }
                }
                vodInfo = info
                if (searchTitle.isEmpty() && !info.name.isNullOrEmpty()) {
                    searchTitle = info.name.trim()
                    startSourceSearch()
                }
                vodName = mVideo.name ?: vodName
                if (!playingList.isNullOrEmpty()) switchSnapshot = null
                pageState.value = PageState.Ready
                bumpRevision()
                // 首集(无记忆)时给一次短暂机会做**真实**画质探测。
                // 放在 pageState=Ready 之后 ⇒ UI 已经渲染,不会白屏;拿到达标就选最好的那档,
                // 拿不到就按记忆/站点原序起播(最坏多等 FIRST_WATCH_PROBE_BUDGET_MS)。
                // 传 detailBuildToken 快照:探测期间换了片的话,快照与当前值就不相等了。
                applyFirstWatchProbe(info, recordKey, recordId, detailBuildToken)
                requestPlay()
                if (playingList.isNullOrEmpty()) {
                    startFallbackIfNeeded(auto = true)
                }
            }
        } else {
            if (fallbackLoadingCandidate) {
                fallbackLoadingCandidate = false
                cancelDetailTimeout()
                loadNextFallbackCandidate()
                return
            }
            handleEmptyDetail(absXml)
        }
    }

    private fun handleEmptyDetail(data: AbsXml?) {
        val msg = data?.msg.orEmpty()
        // 空详情一律留页(空态带换源列表),只有源侧真的报错才提示并退出 —— 源抖动不该表现为"闪退"
        if (isSourceErrorMsg(msg)) {
            if (rollbackManualSwitch(msg)) return
            if (fallbackToPreviousTarget(msg)) return
            LOG.i("echo-detail-finish reason=source-msg msg=$msg key=$sourceKey id=$vodId")
            resetEngineState(keepChips = false)
            toastEvent.value = msg
            finishEvent.value = true
            return
        }
        if (fallbackActive) {
            fallbackLoadingCandidate = false
            loadNextFallbackCandidate()
        } else {
            // 与 onDetailUnavailable 同因同修:详情"请求成功但内容为空"这条路上原来没挂聚合搜索,
            // 于是 loadNextFallbackCandidate 在候选为空时直接收尾,把自动接管清掉 ——
            // 表现就是"详情空 → 停在暂无片源,不自动换源"(只有底部候选列表在动)。
            ensureSourceSearchRunning()
            if (!startFallbackIfNeeded(auto = true)) {
                if (!rollbackManualSwitch()) enterEmpty()
            }
        }
    }

    /** 这一部彻底取不到而栈里还有上一部:退回去并保留报错提示,别把整页关掉 */
    private fun fallbackToPreviousTarget(reason: String?): Boolean {
        val previous = navStack.pop() ?: return false
        applyTarget(previous)
        if (!reason.isNullOrEmpty()) toastEvent.value = reason
        return true
    }

    private fun startSourceSearch() {
        val title = searchTitle.ifEmpty { vodName.trim() }
        if (title.isEmpty()) return
        if (sourcesSearching.value && searchTitle == title) return
        searchTitle = title
        searchToken = SEARCH_SEQ.incrementAndGet()
        val myToken = searchToken
        val tokenStr = "detail_$myToken"
        val checked = SearchHelper.getSourcesForSearch()
        // 勾选表是按"源集合"(键为 API_URL)记的,而聚合订阅会把 API_URL 换成另一个含几百项 site 的配置:
        // 旧表里的 key 在新站表里基本对不上,直接拿去过滤会把候选池缩到 0 —— 表现为换源永远无可选项。
        val effectiveChecked = if (checked == null || SearchHelper.isSelectionStale(checked)) null else checked
        val home = ApiConfig.get().getHomeSourceBean()
        val sources = ApiConfig.get().getSourceBeanList()
            .filter { it.isSearchable() && it.isQuickSearch() && (effectiveChecked == null || effectiveChecked.containsKey(it.key)) }
            .sortedBy { it.key != home.key }
            .take(fallbackPoolCap)
        sourcesSearching.value = sources.isNotEmpty()
        relatedVideos.value = emptyList()
        if (sources.isEmpty()) return
        searchJob = viewModelScope.launch {
            coroutineScope {
                sources.map { bean ->
                    async {
                        semaphore.withPermit {
                            val done = CompletableDeferred<Unit>()
                            pendingSearchDone.put(bean.key, done)?.complete(Unit)
                            try {
                                withTimeoutOrNull(SOURCE_SEARCH_TIMEOUT_MS) {
                                    withContext(Dispatchers.IO) {
                                        searchCaller.getSearch(bean.key, title, tokenStr)
                                    }
                                    done.await()
                                }
                            } finally {
                                pendingSearchDone.remove(bean.key)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (tokenStr == currentTokenStr()) {
                sourcesSearching.value = false
                if (shouldAutoTakeOver()) loadNextFallbackCandidate()
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onSearchResultEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_SEARCH_RESULT) {
            val data = event.obj as? AbsXml ?: return
            if (data.searchToken != currentTokenStr()) return
            pendingSearchDone.remove(data.sourceKey)?.complete(Unit)
            val videos = data.movie?.videoList.orEmpty()
            val fresh = videos.filter {
                !it.id.isNullOrEmpty() && it.name?.trim() == searchTitle
                        && !usedSourceKeys.contains(it.sourceKey)
                        && it.sourceKey != sourceKey
            }.filter { candidateKeys.add(candidateKey(it)) }
            if (fresh.isNotEmpty()) {
                synchronized(fallbackCandidates) { fallbackCandidates.addAll(fresh) }
                publishSourceChips()
                if (shouldAutoTakeOver()) loadNextFallbackCandidate()
            }
            val related = videos.filter {
                !it.id.isNullOrEmpty() && it.name?.trim() != searchTitle
                        && !(it.sourceKey == sourceKey && it.id == vodId)
            }
            if (related.isNotEmpty()) {
                val seen = relatedVideos.value.mapTo(HashSet()) { candidateKey(it) }
                val deduped = related.filter { seen.add(candidateKey(it)) }
                if (deduped.isNotEmpty()) relatedVideos.value = relatedVideos.value + deduped
            }
        } else if (event.type == RefreshEvent.TYPE_PLAY_QUALITY) {
            updateQualityOptions(event.obj as? org.json.JSONObject)
        }
    }

    private fun currentTokenStr(): String = "detail_$searchToken"

    /**
     * 现在是否该由"聚合搜索候选"自动接管当前页面。
     *
     * <p><b>为什么不只看 `fallbackAutoSwitch`</b>:它是可变标记,而 [loadNextFallbackCandidate]
     * 在"候选为空且搜索未在跑"时会走 [finishFallbackWithoutResult] → [resetEngineState],
     * 把刚置上的 `fallbackAutoSwitch` 清掉。一旦清掉就**永久失去接管能力** ——
     * 外部表现正是"底部候选列表一直在刷新、却永远不自动切过去"。
     *
     * <p>补一条不依赖该标记的判据:**详情页停在空态 = 当前源取不到内容**,这时有候选就该换。
     * 这条判据由 `pageState` 直接决定,不受任何标记清场影响。
     *
     * <p>不会造成反复切换:`candidateKeys` 在 `resetEngineState(keepChips = true)` 里**不清**,
     * 已见过的候选进不了 `fresh`,`onSearchResultEvent` 不会为同一批候选重复触发。
     */
    private fun shouldAutoTakeOver(): Boolean =
        !fallbackLoadingCandidate && (fallbackAutoSwitch || pageState.value is PageState.Empty)

    private fun publishSourceChips() {
        val candidates = synchronized(fallbackCandidates) { fallbackCandidates.toList() }
        sourceChips.value = candidates
            .filter { !usedSourceKeys.contains(it.sourceKey) && it.sourceKey != sourceKey }
            .map { video ->
                val key = video.sourceKey.orEmpty()
                SourceChip(key, ApiConfig.get().getSource(key)?.name ?: key)
            }
            .distinctBy { it.key }
    }

    fun candidateForKey(key: String): Movie.Video? =
        synchronized(fallbackCandidates) { fallbackCandidates.firstOrNull { it.sourceKey == key } }

    fun switchSource(video: Movie.Video) {
        stopPlaybackForSwitch()
        usedSourceKeys.add(video.sourceKey.orEmpty())
        vodName = video.name ?: vodName
        vodPicture = video.pic ?: vodPicture
        resetEngineState(keepChips = true)
        loadDetail(video.id.orEmpty(), video.sourceKey.orEmpty())
    }

    /**
     * 首集无记忆时的**真实画质探测**,把 [VodInfo.playFlag] 换成实测最高的那一档。
     *
     * <p>为什么必须做:只靠记忆意味着"第一次播放按站点原序(常常是 480P)",那正是要避免的体验。
     * 这里在起播前给一次 ≤[FIRST_WATCH_PROBE_BUDGET_MS] 的窗口,期间 UI 已渲染,用户看不到白屏。
     *
     * <p>只探**直连型**线路:`VodSeries.url` 以 http 开头才探(与 `PlayLoader.shouldDirectPlay` 同口径)。
     * 需要爬虫 `playerContent` 才能拿到真地址的线路一律跳过 —— 那条路要 1~3 秒/条,不能挡起播。
     * 探不到就静默降级,绝不阻塞播放。
     */
    private suspend fun applyFirstWatchProbe(info: VodInfo, siteKey: String, vodId: String, token: Int) {
        // 「画质选项」三档分流(口径见 DeviceCapability.QualityMode):速度优先跳过全部起播前探测。
        // 实测记忆回写在 MusicSessionDelegate,不受档位影响 —— 速度优先下照常积累,
        // 用户切回自动/画质档后立即受益。
        val mode = DeviceCapability.QualityMode.current()
        if (!mode.shouldProbeOnFirstWatch) return
        val seriesMap = info.seriesMap ?: return
        val siteOrder = seriesMap.keys.toList()
        if (siteOrder.size <= 1) return
        val app = App.getInstance() ?: return
        val cap = DeviceCapability.capHeight(app)
        if (VideoQualityMemory.lookupAll(siteKey, vodId, siteOrder).isNotEmpty()) return
        val currentFlag = info.playFlag
        val currentList = seriesMap[currentFlag]
        // 不能写 coerceIn(0, size - 1):size 为 0 时下界 0 > 上界 -1,coerceIn 会抛 IllegalArgumentException
        val index = if (currentList.isNullOrEmpty()) 0 else info.playIndex.coerceIn(0, currentList.size - 1)
        val headers = probeHeaders(siteKey)
        val probe = VideoQualityProbe()
        val chosen = withTimeoutOrNull(mode.probeBudgetMs) {
            LineQualitySelector.pickWithProbeParallel(
                flags = LineQualitySelector.mergeCandidates(siteOrder, emptyList(), cap, mode.probeLines),
                deviceCapHeight = cap,
                resolve = { flag -> directUrlOf(seriesMap[flag], index) },
                probe = { url -> probe.probe(url, headers) },
            )
        } ?: return
        if (chosen == null || chosen == currentFlag) return
        // 等待期间可能换了片/进了换源链:那时再写 vodInfo 会把新内容顶掉
        if (token != detailBuildToken || sourceKey != siteKey || info.playFlag != currentFlag) return
        info.playFlag = chosen
        val list = seriesMap[chosen]
        if (!list.isNullOrEmpty()) info.playIndex = index.coerceIn(0, list.size - 1)
        for (flag in info.seriesFlags) flag.selected = flag.name == chosen
        vodInfo = info
        bumpRevision()
    }

    /** 该线路在该集上是否有可直接探测的直链(与 PlayLoader.shouldDirectPlay 同口径) */
    private fun directUrlOf(list: List<VodInfo.VodSeries>?, index: Int): String? =
        list?.getOrNull(index)?.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /** 探测要带的请求头:站点级 header 优先,再补一个 UA(部分 CDN 缺 UA 直接 403) */
    private fun probeHeaders(siteKey: String): Map<String, String> {
        val headers = HashMap<String, String>()
        ApiConfig.get().getSource(siteKey)?.header?.let { headers.putAll(it) }
        headers["User-Agent"] = UA.random()
        return headers
    }

    private fun stopPlaybackForSwitch() {
        val info = vodInfo ?: return
        if (switchSnapshot == null) {
            switchSnapshot = SwitchSnapshot(info, vodId, sourceKey, firstsourceKey, vodName, vodPicture)
        }
        // 换源全程静默:换源已自动化,提示既刷屏又泄露"正在换到哪个站"
        sendCommand(PlaybackCommand.StopForSourceSwitch(""))
    }

    private fun rollbackManualSwitch(reason: String? = null): Boolean {
        val snapshot = switchSnapshot ?: return false
        if (snapshot.vodInfo.seriesMap?.get(snapshot.vodInfo.playFlag).isNullOrEmpty()) return false
        switchSnapshot = null
        // 换代次:内容标识从"被弃源"换回"上一部",被弃源的在途回包必须就此作废。
        // 今天即使不换也拦得住(回包 sourceKey 是被弃源,而这里刚恢复成上一部的 key,第 322 行会拦),
        // 但那是"靠两个字段恰好不相等"的巧合 —— 内容改写就得换代,与 loadDetail 同一条协议。
        nextDetailRequestToken()
        vodInfo = snapshot.vodInfo
        vodId = snapshot.vodId
        sourceKey = snapshot.sourceKey
        firstsourceKey = snapshot.firstsourceKey
        vodName = snapshot.vodName
        vodPicture = snapshot.vodPicture
        resetEngineState(keepChips = true)
        toastEvent.value =
            if (reason.isNullOrEmpty()) {
                str(R.string.detail_switch_failed)
            } else {
                str(R.string.detail_switch_failed_reason, reason)
            }
        pageState.value = PageState.Ready
        bumpRevision()
        requestPlay()
        return true
    }

    private fun enterEmpty(msg: String? = null) {
        sendCommand(PlaybackCommand.ClearSourceSwitchTip)
        LOG.i("echo-detail-empty-state msg=$msg key=$sourceKey id=$vodId")
        pageState.value = PageState.Empty(msg)
    }

    /**
     * 线路耗尽后的换源入口(播放侧在"所有线路试完"或"质量看门狗判定持续卡顿"时调用)。
     *
     * <p>与 [onDetailUnavailable] / [handleEmptyDetail] 同因:候选只能来自聚合搜索,
     * 不先保证搜索在跑,[loadNextFallbackCandidate] 会在候选为空时直接收尾并清掉自动接管。
     */
    fun startFallbackAfterLinesExhausted(): Boolean {
        ensureSourceSearchRunning()
        return startFallbackIfNeeded(auto = true, fromLinesExhausted = true)
    }

    private fun startFallbackIfNeeded(auto: Boolean, fromLinesExhausted: Boolean = false): Boolean {
        val currentSource = ApiConfig.get().getSource(sourceKey)
        // 站点不在当前订阅(切源后残留的历史/收藏条目)时没有"当前源"可换,但同名片仍能靠聚合搜索接管
        if (currentSource != null && !currentSource.isChangeable()) return false
        // 已在换源链上又收到"线路耗尽" ⇒ 刚切换过去那个候选站也挂了,必须继续往下取候选。
        // 原来这里直接 return true 会让换源链只跳一跳,之后永久卡死在那个坏站上(既不换源也不再报错推进)。
        if (fallbackActive) {
            usedSourceKeys.add(sourceKey)
            fallbackLoadingCandidate = false
            return loadNextFallbackCandidate()
        }
        val title = (if (vodInfo?.name.isNullOrEmpty()) vodName else vodInfo?.name).orEmpty().trim()
        if (title.isEmpty()) return false
        fallbackKeepCurrentDetail = fromLinesExhausted && vodInfo != null && !vodInfo?.seriesMap.isNullOrEmpty()
        captureFallbackEpisode()
        searchTitle = title
        usedSourceKeys.add(sourceKey)
        fallbackActive = true
        fallbackAutoSwitch = auto
        triedKeys.add(candidateKey(sourceKey, vodId))
        return loadNextFallbackCandidate()
    }

    /**
     * 取下一个候选换源。
     *
     * @return true = 已取到候选并在加载中,或聚合搜索仍在跑(结论未定,别急着报"无有效源");
     *         false = 候选确实耗尽 ⇒ 调用方据此把"无路可走"交回播放层,由它统一提示
     */
    private fun loadNextFallbackCandidate(): Boolean {
        while (true) {
            val video = synchronized(fallbackCandidates) {
                if (fallbackCandidates.isEmpty()) null else fallbackCandidates.removeAt(0)
            } ?: break
            val cKey = candidateKey(video)
            if (usedSourceKeys.contains(video.sourceKey) || !triedKeys.add(cKey)) continue
            fallbackLoadingCandidate = true
            fallbackActive = true
            fallbackAutoSwitch = true
            usedSourceKeys.add(video.sourceKey.orEmpty())
            vodName = video.name ?: vodName
            vodPicture = video.pic ?: vodPicture
            publishSourceChips()
            scheduleDetailTimeout()
            // 传 field 而不是捕获一次:候选站与发起请求同属一代(这一代由 loadDetail 或 rollback 定下)
            loadDetailInternal(video.id.orEmpty(), video.sourceKey.orEmpty(), detailRequestToken)
            return true
        }
        publishSourceChips()
        if (!sourcesSearching.value) {
            finishFallbackWithoutResult()
            return false
        }
        return true
    }

    /**
     * fallback 换候选站:沿用**当前这一代**(同一代内多个候选,谁先回都算当前)。
     *
     * token 由调用方显式传入而不是在这里读字段:若将来有人在这中间插入换代(例如把 rollback 也接进 fallback),
     * 读字段会把"发起时那一代"悄悄改掉,而显式传参会在编译期逼调用方表态。
     */
    private fun loadDetailInternal(vid: String, key: String, requestToken: Int) {
        vodId = vid
        sourceKey = key
        firstsourceKey = key
        collected.value = RoomDataManger.isVodCollect(sourceKey, vodId)
        sourceViewModel.getDetail(sourceKey, vodId, true, requestToken)
    }

    private fun finishFallbackWithoutResult() {
        val keep = fallbackKeepCurrentDetail
        resetEngineState(keepChips = true)
        if (!keep && rollbackManualSwitch()) return
        if (!keep && pageState.value != PageState.Ready) {
            // 候选与搜索都已尽:页面留在空态,文案必须让用户看懂是"没有可用的源",而不是页面加载失败
            enterEmpty(str(R.string.player_play_failed_all))
        }
    }

    private fun scheduleDetailTimeout() {
        if (detailTimeoutScheduled) return
        detailTimeoutScheduled = true
        mainHandler.postDelayed({
            detailTimeoutScheduled = false
            if (fallbackLoadingCandidate) {
                fallbackLoadingCandidate = false
                OkGo.getInstance().cancelTag("detail")
                loadNextFallbackCandidate()
            }
        }, DETAIL_FALLBACK_DETAIL_TIMEOUT_MS)
    }

    private fun cancelDetailTimeout() {
        detailTimeoutScheduled = false
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun captureFallbackEpisode() {
        val info = vodInfo
        fallbackEpisode = null
        fallbackEpisodeIndex = -1
        if (info?.seriesMap == null || info.playFlag.isNullOrEmpty()) return
        val list = info.seriesMap?.get(info.playFlag) ?: return
        if (list.isEmpty()) return
        fallbackEpisodeIndex = info.playIndex.coerceIn(0, list.size - 1)
        fallbackEpisode = list[fallbackEpisodeIndex]
    }

    private fun restoreFallbackEpisode(info: VodInfo) {
        val episode = fallbackEpisode
        if (episode == null || fallbackEpisodeIndex < 0 || info.seriesMap == null) return
        val preferredFlag = info.playFlag
        val preferredList = info.seriesMap?.get(preferredFlag)
        var matched = findMatchingEpisodeIndex(episode, preferredList)
        if (matched >= 0) {
            info.playIndex = matched
            return
        }
        for (flag in info.seriesFlags) {
            if (flag.name.isNullOrEmpty() || flag.name == preferredFlag) continue
            matched = findMatchingEpisodeIndex(episode, info.seriesMap?.get(flag.name))
            if (matched >= 0) {
                info.playFlag = flag.name
                info.playIndex = matched
                return
            }
        }
        if (preferredList != null && preferredList.isNotEmpty()) {
            info.playIndex = fallbackEpisodeIndex.coerceIn(0, preferredList.size - 1)
        }
    }

    private fun resetEngineState(keepChips: Boolean) {
        fallbackActive = false
        fallbackAutoSwitch = false
        fallbackKeepCurrentDetail = false
        fallbackLoadingCandidate = false
        detailTimeoutScheduled = false
        cancelDetailTimeout()
        triedKeys.clear()
        if (!keepChips) {
            synchronized(fallbackCandidates) { fallbackCandidates.clear() }
            candidateKeys.clear()
        }
        publishSourceChips()
    }

    fun destroyEngine() {
        cancelDetailTimeout()
        OkGo.getInstance().cancelTag("detail")
        OkGo.getInstance().cancelTag("search")
    }

    private fun candidateKey(video: Movie.Video): String =
        (video.sourceKey ?: "") + "|" + (video.id ?: "")

    private fun candidateKey(key: String, id: String): String = "$key|$id"

    fun onEpisodeClick(position: Int) {
        val info = vodInfo ?: return
        val list = info.seriesMap?.get(info.playFlag) ?: return
        if (position < 0 || position >= list.size || position == info.playIndex) return
        info.playIndex = position
        list.forEachIndexed { index, series -> series.selected = index == position }
        bumpRevision()
        requestPlay()
    }

    fun onFlagClick(flagName: String) {
        val info = vodInfo ?: return
        if (info.playFlag == flagName) return
        val oldList = info.seriesMap?.get(info.playFlag)
        val currentIndex = info.playIndex.coerceAtLeast(0)
        val currentSeries = oldList?.getOrNull(currentIndex)
        info.playFlag = flagName
        val newList = info.seriesMap?.get(flagName)
        if (newList != null && newList.isNotEmpty()) {
            info.playIndex = findSameEpisodeIndex(currentSeries, newList, currentIndex)
            newList.forEachIndexed { index, series -> series.selected = index == info.playIndex }
        }
        info.seriesFlags.forEach { it.selected = it.name == flagName }
        manualLineSwitchPending = true
        bumpRevision()
        requestPlay()
    }

    fun toggleReverse() {
        val info = vodInfo ?: return
        val list = info.seriesMap?.get(info.playFlag) ?: return
        if (list.size <= 1) return
        info.reverseSort = !info.reverseSort
        info.reverse()
        info.playIndex = (list.size - 1) - info.playIndex
        bumpRevision()
    }

    fun toggleCollect() {
        val info = vodInfo ?: return
        if (collected.value) {
            RoomDataManger.deleteVodCollect(sourceKey, info)
            toastEvent.value = str(R.string.toast_removed_from_collect)
        } else {
            RoomDataManger.insertVodCollect(sourceKey, info)
            toastEvent.value = str(R.string.toast_added_to_collect)
        }
        collected.value = !collected.value
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_COLLECT_REFRESH))
    }

    private fun updateQualityOptions(result: org.json.JSONObject?) {
        val options = ArrayList<String>()
        try {
            val value = result?.opt("url")
            val urls = when (value) {
                is org.json.JSONArray -> value
                is String -> org.json.JSONArray(value)
                else -> null
            }
            if (urls != null) {
                var i = 0
                while (i + 1 < urls.length()) {
                    options.add(urls.optString(i))
                    i += 2
                }
            }
        } catch (ignored: Throwable) {
            LOG.d("DetailViewModel", "quality url list parse failed, keep empty options")
        }
        if (options == qualityOptions.value) return
        qualityOptions.value = options
        qualitySelected.value = 0
    }

    /** 已选中项再点 = 进全屏;换一项则下发指令,选中态等容器确认回写(能否切取决于控制器清晰度表) */
    fun onQualityClick(position: Int, facts: DetailPlaybackFacts) {
        if (position == qualitySelected.value) {
            onFullScreenToggleRequested(true, facts)
            return
        }
        sendCommand(PlaybackCommand.SelectQuality(position))
    }

    /** 容器侧确认清晰度切换已受理:选中态落地的时刻(旧实现读 `selectQuality` 的同步返回值) */
    fun onQualitySelectionAccepted(position: Int) {
        qualitySelected.value = position
    }

    /** 通道不 close():`close()` 会让仍在收集的页面拿 `ClosedReceiveChannelException`,而"组合先销毁"只是时序巧合 */
    private fun sendCommand(command: PlaybackCommand) {
        playbackCommandChannel.trySend(command)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_PLAYBACK_STARTED) {
            onPlaybackStarted()
            return
        }
        if (event.type != RefreshEvent.TYPE_REFRESH) return
        val info = vodInfo ?: return
        when (val obj = event.obj) {
            is VodInfo -> syncPlayingVodInfo(obj)
            is Int -> {
                val list = info.seriesMap?.get(info.playFlag) ?: return
                list.forEachIndexed { index, series -> series.selected = index == obj }
                info.playIndex = obj
                insertVod()
                bumpRevision()
            }
            is org.json.JSONObject -> {
                info.playerCfg = obj.toString()
                insertVod()
                bumpRevision()
            }
        }
    }

    /** 播放器真的播起来才落库;校验在播内容与本页一致(含集/线路:音乐页接管后改的是同一份 session) */
    private fun onPlaybackStarted() {
        val info = vodInfo ?: return
        val playing = App.getInstance().vodInfo ?: return
        if (playing.id != info.id || playing.sourceKey != info.sourceKey) return
        if (playing.playFlag != info.playFlag || playing.playIndex != info.playIndex) return
        insertVod()
    }

    private fun syncPlayingVodInfo(playing: VodInfo) {
        val info = vodInfo ?: return
        // EventBus 是全局的:换片后旧片内核的迟到广播同样会打进来,内容对不上就丢
        if (playing.id != info.id || playing.sourceKey != info.sourceKey) return
        val newFlag = playing.playFlag
        if (newFlag.isNullOrEmpty() || info.seriesMap?.containsKey(newFlag) != true) return
        val newList = info.seriesMap?.get(newFlag) ?: return
        if (newList.isEmpty()) return
        val playingList = playing.seriesMap?.get(newFlag)
        val playingSeries = playingList?.getOrNull(playing.playIndex.coerceIn(0, playingList.size - 1))
        val newIndex = findSameEpisodeIndex(playingSeries, newList, playing.playIndex)
        info.playFlag = newFlag
        info.playIndex = newIndex
        if (playing.playerCfg != null) info.playerCfg = playing.playerCfg
        info.seriesFlags.forEach { it.selected = it.name == newFlag }
        info.seriesMap?.values?.forEach { list -> list.forEach { it.selected = false } }
        newList[newIndex].selected = true
        // "真看过"落库要过观看门槛,短看即退会让库里的集号停在上一集(卡片与续播都跟着错),故同步集号即落库
        insertVod()
        bumpRevision()
        LOG.i("echo-detail sync -> $newFlag/$newIndex")
    }

    private fun insertVod() {
        val info = vodInfo ?: return
        refreshPlayNote(info)
        // 集数快照随"真看过"落库:只浏览详情页不再写,历史卡片才不会出现没看过的片
        EpisodeTotals.putFromVod(info)
        HistoryWriter.write(firstsourceKey, info)
    }

    /** 集名要随会话进播放器(字幕搜索默认词读它),不能只在落库时才刷 */
    private fun refreshPlayNote(info: VodInfo) {
        try {
            info.playNote = info.seriesMap?.get(info.playFlag)?.get(info.playIndex)?.name ?: ""
        } catch (_: Throwable) {
            info.playNote = ""
        }
    }

    fun preparePlaySession(): PlaybackSession? {
        val info = vodInfo ?: return null
        val list = info.seriesMap?.get(info.playFlag) ?: return null
        if (list.isEmpty()) return null
        refreshPlayNote(info)
        val preview = previewVodInfo ?: VodInfo()
        preview.id = info.id
        preview.name = info.name
        preview.pic = info.pic
        preview.sourceKey = info.sourceKey
        preview.playNote = info.playNote
        preview.seriesFlags = info.seriesFlags
        preview.seriesMap = info.seriesMap
        preview.playerCfg = info.playerCfg
        preview.playFlag = info.playFlag
        preview.playIndex = info.playIndex
        previewVodInfo = preview
        App.getInstance().setVodInfo(preview)
        return PlaybackSession(preview, sourceKey, consumeManualLineSwitch())
    }

    private fun findSameEpisodeIndex(current: VodInfo.VodSeries?, target: List<VodInfo.VodSeries>, fallback: Int): Int {
        if (target.isEmpty()) return 0
        if (target.size == 1) return 0
        if (current == null || current.name.isNullOrEmpty()) {
            return fallback.coerceIn(0, target.size - 1)
        }
        val currentEpisode = extractEpisodeNumber(current.name)
        var matched = -1
        var best = 0
        target.forEachIndexed { i, series ->
            val score = episodeMatchScore(current.name, currentEpisode, series.name)
            if (score > best) {
                best = score
                matched = i
            }
        }
        return if (matched >= 0) matched else fallback.coerceIn(0, target.size - 1)
    }

    private fun findMatchingEpisodeIndex(current: VodInfo.VodSeries?, target: List<VodInfo.VodSeries>?): Int {
        if (target.isNullOrEmpty()) return -1
        if (target.size == 1) return 0
        if (current == null || current.name.isNullOrEmpty()) return -1
        val currentEpisode = extractEpisodeNumber(current.name)
        var matched = -1
        var best = 0
        target.forEachIndexed { i, series ->
            val score = episodeMatchScore(current.name, currentEpisode, series.name)
            if (score > best) {
                best = score
                matched = i
            }
        }
        return matched
    }

    private fun episodeMatchScore(currentName: String?, currentEpisode: Int, targetName: String?): Int {
        if (currentName.isNullOrEmpty() || targetName.isNullOrEmpty()) return 0
        if (targetName.equals(currentName, ignoreCase = true)) return 100
        if (currentEpisode >= 0 && extractEpisodeNumber(targetName) == currentEpisode) return 80
        val currentLower = currentName.lowercase(Locale.ROOT)
        val targetLower = targetName.lowercase(Locale.ROOT)
        if (currentEpisode < 0 && currentName.length >= 2 && targetLower.contains(currentLower)) return 70
        if (currentEpisode < 0 && targetName.length >= 2 && currentLower.contains(targetLower)) return 60
        return 0
    }

    private fun extractEpisodeNumber(name: String?): Int {
        if (name.isNullOrEmpty()) return -1
        return try {
            var text = name.replace(Regex("\\[.*?]|\\(.*?\\)"), "")
            text = text.replace(Regex("\\b(19|20)\\d{2}\\b"), "")
            text = text.lowercase(Locale.ROOT).replace(Regex("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4"), "")
            // i18n: keep —— 从源侧片名/集名里抽集数,关键词是数据规则
        val matcher = Regex("(?i)(?:ep|第|e|[\\-\\.\\s])\\s?(\\d{1,4})").find(text)
            if (matcher != null) {
                matcher.groupValues[1].toInt()
            } else {
                val number = text.replace(Regex("\\D+"), "")
                if (number.isNotEmpty()) number.toInt() else -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    override fun onCleared() {
        // 收集器不手工摘:onCleared 返回后框架才取消 viewModelScope,桥接器的 awaitClose 随之摘观察者
        EventBus.getDefault().unregister(this)
        destroyEngine()
        super.onCleared()
    }

    companion object {
        private val SEARCH_SEQ = java.util.concurrent.atomic.AtomicInteger(0)

        /** 候选站详情取超时:候选站本身慢就赶紧轮到下一个,别让用户盯着等 */
        private const val DETAIL_FALLBACK_DETAIL_TIMEOUT_MS = 4000L
        /**
         * 首集无记忆时的真实画质探测预算(毫秒)。放在 `pageState=Ready` 之后执行,UI 已渲染,
         * 不会白屏;超时就按记忆/站点原序起播,探测是锦上添花,绝不能拖住起播。
         */
        private const val FIRST_WATCH_PROBE_BUDGET_MS = 700L
        /** 探测线路数上限:每条一次 ≤256KB 请求,3 条最坏 768KB */
        private const val FIRST_WATCH_PROBE_LINES = 3
        /** 单个候选站的同名搜索超时。原 30s:聚合订阅动辄数百站,单站卡住会拖垮整轮候选收集 */
        private const val SOURCE_SEARCH_TIMEOUT_MS = 8_000L
        /** 候选收集并发度。原 6 太保守,聚合订阅下一批批轮很慢;提到 12 让候选更快到齐 */
        private const val SOURCE_SEARCH_CONCURRENCY = 12

        // i18n: keep —— 源侧"没有数据"的哨兵值;误翻会把空结果判成源报错,详情页提示后自动关闭
        private const val SOURCE_EMPTY_MSG = "数据列表"

        internal fun isSourceErrorMsg(msg: String?): Boolean =
            !msg.isNullOrEmpty() && msg != SOURCE_EMPTY_MSG
    }
}
