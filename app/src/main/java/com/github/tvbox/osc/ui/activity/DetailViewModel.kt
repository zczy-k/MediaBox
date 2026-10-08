package com.github.tvbox.osc.ui.activity

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.AvailabilityMemory
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.RoomDataManger
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.DeviceCapability
import com.github.tvbox.osc.player.LineQualitySelector
import com.github.tvbox.osc.player.PlaybackSession
import com.github.tvbox.osc.player.VideoQualityMemory
import com.github.tvbox.osc.player.VideoQualityPolicy
import com.github.tvbox.osc.player.VideoQualityProbe
import com.github.tvbox.osc.util.UA
import com.github.tvbox.osc.util.EpisodeTotals
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.HistoryWriter
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.SearchHelper
import com.github.tvbox.osc.util.SourceFailKind
import com.github.tvbox.osc.util.SourceHealthFilter
import com.github.tvbox.osc.util.SourceHealthMemory
import com.github.tvbox.osc.util.SourceIdentityMask
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

    /**
     * 换源候选的展示条目。
     *
     * <p>**刻意不带 name 字段** —— 产品要求"任何情况下用户都看不到有哪些源"。
     * 以前这里是 `SourceChip(key, ApiConfig.get().getSource(key)?.name ?: key)`,
     * 把真实站名直接塞进 StateFlow,详情页空态下方的候选行就是它渲染的。
     * 现在只保留 [key] 作为点击回调的凭据,标签文本一律匿名(见 [sourceLabelFor]).
     */
    data class SourceChip(val key: String)

    /**
     * 详情加载期间就能展示的已知信息(片名 / 海报)。
     *
     * <p>点击卡片时 `jumpToDetail(id, sourceKey, title, picture)` 已经把这两个值带进来了,
     * 而详情取数要等网络。加载态先把它渲染出来,等待期间就不是一片空白 —— 这是感知提速,
     * 不改变任何取数时机。
     */
    data class DetailHeader(val name: String = "", val picture: String = "")

    val pageState = MutableStateFlow<PageState>(PageState.Loading)
    val header = MutableStateFlow(DetailHeader())

    /**
     * 线路 flag → **实测**高度(像素);没有实测值的线路不会出现在这里。
     *
     * <p>只服务选集面板的线路标签显示(见 [LineLabelPolicy]),**不参与任何选线决策** ——
     * 决策走 `VideoQualityMemory.lookupAll` + `VideoQualityPolicy`。
     */
    val lineQualityHeights = MutableStateFlow<Map<String, Int>>(emptyMap())
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
    /** 异步补齐其余线路画质的小任务;换片/重开面板时取消,避免上一部的探测结果串到新内容 */
    private var qualityProbeJob: Job? = null
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

    /**
     * "当前源已确认取不到内容" —— **换源接管的触发信号,与 [pageState] 解耦**。
     *
     * <p>## 为什么必须把它从 [pageState] 里拆出来
     *
     * <p>v1.0.33 踩的正是这个坑,而且症状极具欺骗性(用户报"每个影片都没法播、
     * 卡在转圈圈"):修「一闪而过的暂无片源」时给 `enterEmpty` 加了守卫
     * `if (sourcesSearching.value) return`,让页面在搜索在途时停在 Loading。
     * 而 [shouldAutoTakeOver] 的判据是
     * `fallbackAutoSwitch || pageState.value is PageState.Empty`
     * —— **空态本身就是换源链的触发信号**。
     *
     * <p>于是两者成了死对头:守卫坚持"不到终态不进 Empty",换源判据要求
     * "必须先在 Empty 才让换源"。搜索回包时 [onSearchResultEvent] 里
     * `if (shouldAutoTakeOver()) loadNextFallbackCandidate()` 永远为假
     * ⇒ 候选站一个都不试⇒ 页面卡在 Loading 直到 8秒搜索超时
     * ⇒ 被 settleDeferredEmpty 收成空态 ⇒ **一部都播不了**。
     *
     * <p>真机证据(`echo-detail sync`,即详情成功到达):
     * 修复前 12:19~12:38 连续不断(`HN线路`/`线路一(点换线路)`/`FF线路` 都成功过);
     * 修复后 12:47 之后**一条都没有**。
     *
     * <p>## 为什么不能简单地把守卫放宽
     *
     * <p>放宽就等于把闪现问题带回来。空态同时承担了两个职责:
     * 给用户看的 UI 态、以及"换源链该启动了"的内部信号。
     * UI 态可以延后,内部信号不能延后 —— 所以必须拆成两个字段。
     */
    private var currentSourceConfirmedEmpty = false

    /**
     * 详情加载看门狗的当前代次;0 = 未布防。
     *
     * <p>与 [detailTimeoutScheduled] 那套"候选站 4s 超时"分开:那个只在换源链里布防,
     * 而正常路径此前**没有任何超时** —— 源不响应就永远停在 Loading。
     */
    private var detailWatchdogToken = 0
    private val detailWatchdog = Runnable { onDetailWatchdogFired() }

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
        publishHeader()
        loadDetail(target.vodId, target.sourceKey)
        LOG.i("echo-detail-open collect=$fromCollect key=$sourceKey id=$vodId")
        if (vodName.isNotEmpty()) startSourceSearch()
    }

    /** 换片时收掉上一部在途的聚合搜索,并让旧 token 立即失效(搜索回包仍靠 token 失配兜底) */
    private fun cancelInFlightContent() {
        searchJob?.cancel()
        searchJob = null
        val stopped = pendingSearchDone.keys.toList()
        pendingSearchDone.values.forEach { it.complete(Unit) }
        pendingSearchDone.clear()
        //⚠️ 必须逐源撤:搜索请求 tag 自 2026-10-08 起是**按源唯一**的
        // (SearchHelper.searchRequestTag),`cancelTag("search")` 已经打不中任何一条。
        // 只 complete 协程而不撤网络请求,等于"UI 认为结束了、请求还在抢带宽"。
        cancelSourceSearchRequests(stopped)
        cancelDetailTimeout()
        searchToken = SEARCH_SEQ.incrementAndGet()
    }

    /**
     * 撤掉聚合搜索里仍在途的源请求(2026-10-08)。
     *
     * <p>与 SearchViewModel 同一手法:OkGo 的 tag 取消是精确匹配,`cancelTag("search")`
     * 对 `search-<key>` 无效,因此按在途源逐个撤,再兜一层按 tag 扫默认客户端。
     */
    private fun cancelSourceSearchRequests(sourceKeys: Collection<String>) {
        sourceKeys.forEach { key ->
            val tag = SearchHelper.searchRequestTag(key)
            try {
                OkGo.getInstance().cancelTag(tag)
            } catch (ignored: Throwable) {
                LOG.d("DetailViewModel", "cancel fallback search failed key=" + key)
            }
            try {
                com.github.catvod.net.OkHttp.cancel(OkGoHelper.getDefaultClient(), tag)
            } catch (ignored: Throwable) {
                LOG.d("DetailViewModel", "cancel fallback call failed key=" + key)
            }
        }
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
        // ⚠️ 必须随片清空:这是"当前源没内容"的判定,带着上一部的结论进下一部
        // 会让新片一打开就被判成"该换源",进而触发一次不必要的接管。
        currentSourceConfirmedEmpty = false
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

    /** 把当前已知的片名/海报推给加载态 UI;凡改动 [vodName]/[vodPicture] 之后都要调一次 */
    private fun publishHeader() {
        val next = DetailHeader(vodName, vodPicture)
        if (header.value != next) header.value = next
    }

    /**
     * 刷新"每条线路的实测画质",供选集面板显示「线路N · 1080P」。
     *
     * <p>时机选在详情就绪与打开选集面板:实测值由播放层在起播后回写,用户第二次打开面板时
     * 就能看到真实档位;而放在这两个点上,不会随重组反复读记忆(那是一次 JSON 全量解析)。
     */
    private fun publishLineQualityHeights() {
        val info = vodInfo ?: return
        val siteOrder = info.seriesMap?.keys?.toList().orEmpty()
        // 单线路没有可切换对象、面板也不显示线路行:省掉这次记忆全量解析(常见情形)
        if (siteOrder.size <= 1) {
            if (lineQualityHeights.value.isNotEmpty()) lineQualityHeights.value = emptyMap()
            return
        }
        val heights = VideoQualityMemory.lookupAll(sourceKey, vodId, siteOrder)
            .associate { it.flag to it.height }
        // ⚠️ 这条日志是「标签为什么不显示」的唯一可观测点,别删。
        // 记忆按 "站点|片|flag" 取,任一环对不上都表现为"标签不出现",而外部完全看不出差别。
        // 写入侧(PlaybackController.probeLineQualityOnRealUrl)用 vod().sourceKey,
        // 读取侧用本类的 sourceKey —— **自动换源后这两个不是同一个值**,是最可疑的一环。
        // 无条件打印:之前加了"源名含热播影视"的守卫,结果真机跑起来一条都没打,
        // 白等一轮 —— 诊断日志不该有守卫,它的价值恰恰在于"必然出现"。
        LOG.i(
            "echo-line-heights site=" + sourceKey + " vod=" + vodId +
                " infoSite=" + info.sourceKey +
                " order=" + siteOrder + " raw=" + heights + " flags=" + info.seriesFlags
        )
        if (lineQualityHeights.value != heights) lineQualityHeights.value = heights
    }

    /**
     * 异步补齐**直连型线路**的实测画质,让「线路N · 1080P」不只出现在"用户恰好播过的那条"。
     *
     * <p>为什么必须有这个:实测画质只在你**真正播过**某条线路时才会写入(见
     * [MusicSessionDelegate.maybeRememberMeasuredQuality])。不补齐的话,绝大多数线路永远没有
     * 数据,标签就退化成纯序号 —— 用户看到的是一个没用的"线路1/2/3",而我们明明已经有能力
     * 在几百毫秒内测出真实分辨率。
     *
     * <p>**只碰直连型线路**([directUrlOf] 与 `PlayLoader.shouldDirectPlay` 同口径):
     * 爬虫型线路的地址要先跑 `playerContent` 才有,每条 1~3 秒且
     * [com.github.tvbox.osc.sourcedata.PlayLoader] **完全没有解析缓存**,主动去爬等于
     * 凭空多付 N 次解析 —— 与"更快开始播放"直接冲突。爬虫型线路改由
     * [com.github.tvbox.osc.player.ResolvedUrlQualityProbe] 在**解析反正要跑**的那一刻顺手测,
     * 总请求数不增加,且完全不占用起播时间。
     *
     * <p>所以两类线路的画质来源不同,口径也不同:
     * <ul>
     *   <li>直连型:打开面板几百毫秒内就有;</li>
     *   <li>爬虫型:**看过一次之后**才有,第二次打开这部片时全部带画质。</li>
     * </ul>
     * 这不是妥协 —— 没解析过的线路就是没数据,编一个出来只会让用户点进去发现不对。
     *
     * <p>不阻塞 UI:探测是网络请求(每条 ≤256KB、超时 [VideoQualityProbe.PROBE_TIMEOUT_MS]),
     * 放这里只更新 [lineQualityHeights],面板/详情区下次重组时自然带上新值。用户看到的是
     * "标签先是序号、几百毫秒后补上画质",而不是"打开面板卡一下"。
     *
     * <p>护栏只留两条(曾经有第三条"只在有记忆时才探",已删 —— 那是设计缺陷,见下):
     * <ul>
     *   <li>**只探没测过的**,已测过的直接跳过(不重复请求);</li>
     *   <li>只在 `shouldProbeOnFirstWatch` 档位下进行 —— "速度优先"档的用户明确不要这类额外请求。</li>
     * </ul>
     *
     * <p>**踩过的坑,别再加回来**:最早这里有个"仅当记忆里已有该片数据才探测"的锚点判断,
     * 理由是"纯新片探测浪费流量"。但那恰好把**第一次观看**这个最需要它的场景挡死了 ——
     * 新片记忆必然为空 ⇒ 永远不探 ⇒ 用户第一次看就只看到"线路1/2/3",正是这个功能要解决的问题。
     * 流量顾虑应该用**批量上限 + 只探未测 + 档位开关**控制,而不是用"有没有旧数据"来 gating。
     */
    private fun probeMissingLineQualities() {
        val info = vodInfo ?: return
        val siteOrder = info.seriesMap?.keys?.toList().orEmpty()
        if (siteOrder.size <= 1) return
        val mode = DeviceCapability.QualityMode.current()
        if (!mode.shouldProbeOnFirstWatch) return
        val remembered = VideoQualityMemory.lookupAll(sourceKey, vodId, siteOrder)
        val missing = siteOrder.filter { flag -> remembered.none { it.flag == flag } }
        if (missing.isEmpty()) return
        // 只探**直连型**线路(与 PlayLoader.shouldDirectPlay 同口径):爬虫型线路要先跑 getPlay
        // 解析才拿得到地址,每条 1~3 秒且同类 Spider 不能并发,代价与"更快开始播放"冲突。
        // 拿不到 URL 的线路由 probeVariantsParallel 静默跳过,不阻塞其余线路。
        val index = probeIndexOf(info)
        val targets = missing.filter { directUrlOf(info.seriesMap?.get(it), index) != null }.take(3)
        // ⚠️ 这条日志是排查「线路N 后面一直不显示分辨率」的唯一可观测点,别删。
        // 静默 return 的分支太多(单线路/档位关闭/全测过/全是爬虫型),没有日志时
        // 只能靠猜 —— v1.0.25 真机实测就卡在这:5 条线路全是"线路1..5"无画质,
        // 而本函数一行日志都不打,无法区分"没触发"与"触发了但目标为空"。
        // 决策树一次打印全部分支,以后再出现直接对号入座。
        LOG.i(
            "echo-line-probe site=" + sourceKey + " vod=" + vodId +
                " lines=" + siteOrder.size + " mode=" + mode.name +
                " remembered=" + remembered.size + " missing=" + missing.size +
                " direct=" + targets.size + " idx=" + index +
                " (empty-skip: lines<=1 | mode-off | all-remembered | no-direct-url)"
        )
        if (targets.isEmpty()) return
        val headers = probeHeaders(sourceKey)
        val probe = VideoQualityProbe()
        val siteKey = sourceKey
        val vod = vodId
        val list = info.seriesMap
        qualityProbeJob?.cancel()
        qualityProbeJob = viewModelScope.launch {
            val probed = withTimeoutOrNull(LineQualityProbeBudget.totalMs) {
                LineQualitySelector.probeVariantsParallel(
                    flags = targets,
                    resolve = { flag -> directUrlOf(list?.get(flag), index) },
                    probe = { url -> probe.probe(url, headers) },
                )
            }.orEmpty()
            probed.forEach { v ->
                if (v.flag.isEmpty()) return@forEach
                VideoQualityMemory.record(siteKey, vod, v)
            }
            // 会话可能已经换片/换源:那时刷新会把新内容的画质写进来
            if (siteKey != sourceKey || vod != vodId) return@launch
            publishLineQualityHeights()
        }
    }

    /** 探测预算:单条 800ms(见 VideoQualityProbe),留一倍余量给并发调度 */
    private object LineQualityProbeBudget {
        const val totalMs = 2000L
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
        // 面板要显示线路标签,打开这一刻刷新一次实测画质(起播后回写的值到这时才可能变)
        publishLineQualityHeights()
        // 面板是用户真正会盯着看的地方,在这里补齐其余线路画质(异步,只补没测过的)
        probeMissingLineQualities()
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
        // 换片/换源:在途探测不再适用(探测结果按 站点|片id|flag 存,不会串片,但白探一轮新片是浪费)
        qualityProbeJob?.cancel()
        qualityProbeJob = null
        lineQualityHeights.value = emptyMap()
        collected.value = RoomDataManger.isVodCollect(sourceKey, vodId)
        if (DetailResponseGuard.isUnloadableTarget(vodId, ApiConfig.get().getSource(sourceKey) == null)) {
            onDetailUnavailable()
            return
        }
        pageState.value = PageState.Loading
        armDetailWatchdog(key)
        sourceViewModel.getDetail(sourceKey, vodId, false, requestToken)
        // 详情照旧请求当前源;聚合搜索**并行**预热,不等它出结果。
        // 目的只有一个:把"当前源没有这部剧"的兜底从 20~45 秒压到秒级(见 [prewarmSourceSearch])
        prewarmSourceSearch()
    }

    /**
     * 详情请求发出后,延迟 [SOURCE_SEARCH_PREWARM_DELAY_MS] 预热聚合搜索。
     *
     * <p>**要解决的问题**:换源候选只能来自聚合搜索,而它原先**只在当前源失败之后**才启动
     * ([ensureSourceSearchRunning])。当前源失败要先等详情看门狗(20~45 秒,
     * 见 [DetailLoadWatchdog])才判定失败,于是用户盯着"寻找中"白等几十秒 ——
     * 这正是"打开有些影片总是先卡在这个界面"的成因:不是搜索慢,是**启动得太晚**。
     *
     * <p>**为什么延迟而不是立刻发**:当前源绝大多数情况是能取到的,立刻发等于每次点卡片
     * 都惊动上百个站,流量与并发都白扔。延迟一小会儿再发,命中"当前源能取到"时搜索早已超时收工、
     * 用户全程无感;只有真慢到超时的那些才刚好赶上兜底时机。
     *
     * <p>**为什么不改判定逻辑**:只改启动时机。[ensureSourceSearchRunning] 仍然保留 ——
     * 它是"延迟期间搜索已收工"或"标题彼时还没拿到"的兜底,两条路径都指向同一个
     * [startSourceSearch],而后者用 `sourcesSearching && searchTitle == title` 自去重,
     * 所以重复调用不会发出第二轮搜索。
     *
     * <p>换片/换源时 [prewarmSourceSearch] 开头就 `removeCallbacks(sourceSearchPrewarm)`,
     * 旧片的预热不会残留。刻意**不**借用 `cancelDetailTimeout()`(它走的是
     * `removeCallbacksAndMessages(null)`)—— 那是"连本 Handler 全部回调一起清"的粗粒度收口,
     * 预热这种良性任务没这个必要,顺带把它捎上只是增加耦合。
     */
    private fun prewarmSourceSearch() {
        mainHandler.removeCallbacks(sourceSearchPrewarm)
        val title = (if (vodInfo?.name.isNullOrEmpty()) vodName else vodInfo?.name).orEmpty().trim()
        if (title.isEmpty()) return
        mainHandler.postDelayed(sourceSearchPrewarm, SOURCE_SEARCH_PREWARM_DELAY_MS)
    }

    private val sourceSearchPrewarm = Runnable {
        // 详情已经拿到内容就不必再找替代源了:省掉这一整轮搜索
        if (pageState.value is PageState.Ready) {
            LOG.i("echo-source-prewarm skip reason=already-ready")
            return@Runnable
        }
        // ⚠️ 这里**可能什么都不打** —— 那不是"预热没生效",而是搜索早已被别的入口启动过了。
        // 实测(诊断包 v1.0.24 真机):首页/搜索点片进来走 [applyTarget],其 :240 行
        // `if (vodName.isNotEmpty()) startSourceSearch()` 是**无条件**的,聚合搜索在详情
        // 请求后 2~3ms 就已发出(pool=120)。等本 Runnable 到点时 [ensureSourceSearchRunning]
        // 的 `if (sourcesSearching.value) return` 守卫直接短路 —— 不打日志,也不重复搜索。
        // 所以判断预热是否生效**不能看这个前缀**,要看 [startSourceSearch] 的
        // `echo-source-search start` 时间戳相对 `echo-detail-watchdog-arm` 的差值。
        //
        // 本预热的真正价值在 [switchSource] 路径:它走 [loadDetail] 而**不**经 :240,
        // 那条路径上聚合搜索只由本 Runnable(或看门狗兜底)启动 —— 缺了它就会白等 20~45 秒。
        LOG.i("echo-source-prewarm fire")
        ensureSourceSearchRunning()
    }

    /**
     * 布防详情加载看门狗:到点仍停在 Loading 就收口(换源兜底 / 空态提示),不再无限转圈。
     *
     * <p>时限按站点自己声明的取数限时算(见 [DetailLoadWatchdog]),所以它只是"连站点限时都等完了"
     * 的兜底,不会把正常但偏慢的源掐断。
     */
    private fun armDetailWatchdog(key: String) {
        mainHandler.removeCallbacks(detailWatchdog)
        detailWatchdogToken = detailRequestToken
        val timeoutMs = DetailLoadWatchdog.timeoutMs(
            ApiConfig.get().getSource(key)?.playTimeoutSeconds ?: 0,
        )
        LOG.i("echo-detail-watchdog-arm token=$detailWatchdogToken timeout=${timeoutMs}ms key=$key")
        mainHandler.postDelayed(detailWatchdog, timeoutMs)
    }

    /** 撤防(收到本代任何回包、或内容换代时调用) */
    private fun disarmDetailWatchdog() {
        detailWatchdogToken = 0
        mainHandler.removeCallbacks(detailWatchdog)
    }

    private fun onDetailWatchdogFired() {
        val token = detailWatchdogToken
        detailWatchdogToken = 0
        // 回包已到 / 已换代 / 已不在 Loading:看门狗只兜"卡在加载"这一种情形
        if (token == 0 || token != detailRequestToken) return
        if (pageState.value !is PageState.Loading) return
        LOG.i("echo-detail-watchdog-fire token=$token key=$sourceKey id=$vodId")
        // 同 tag 的详情请求已在途:撤掉它,免得迟到回包把空态/兜底结果顶掉
        OkGo.getInstance().cancelTag("detail")
        onDetailLoadTimedOut()
    }

    /**
     * 详情加载超时的收口:与 [onDetailUnavailable] 同路(先换源兜底,无候选才留空态),
     * 只是文案点明是超时,而不是让用户以为"这部没有片源"。
     */
    private fun onDetailLoadTimedOut() {
        // 同上:看门狗超时 = 当前源这次没给出内容,置位让换源接管能继续往下走。
        currentSourceConfirmedEmpty = true
        if (fallbackActive) {
            fallbackLoadingCandidate = false
            loadNextFallbackCandidate()
            return
        }
        ensureSourceSearchRunning()
        if (!startFallbackIfNeeded(auto = true)) {
            if (!rollbackManualSwitch()) enterEmpty(str(R.string.detail_load_timeout))
        }
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
        // 同 handleEmptyDetail:当前源不可播/不在订阅,也是"当前源没内容"的确认信号。
        currentSourceConfirmedEmpty = true
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
        // 本代已有回包(哪怕内容是空):看门狗不再需要兜底
        disarmDetailWatchdog()
        val videoList = absXml?.movie?.videoList
        val detailToken = ++detailBuildToken
        if (videoList != null && videoList.isNotEmpty()) {
            if (fallbackLoadingCandidate) {
                fallbackLoadingCandidate = false
                cancelDetailTimeout()
            }
            // ⚠️ 必须清标志:当前源这次**取到了**内容。
            // 不清的话,后续任何一次聚合搜索回包都会因currentSourceConfirmedEmpty
            // 为真而触发换源接管 —— 用户明明已经看到详情了,却被后台悄悄切到别的站去。
            currentSourceConfirmedEmpty = false
            // 自动换源全程静默(用户要的是"无感播放"):不再提示"站点切换至X" ——
            // 那既暴露了当前用的是哪个站,对用户也是纯噪音。真失败时另有终局提示兜底。
            if (isSourceErrorMsg(absXml.msg)) {
                if (!rollbackManualSwitch(absXml.msg)) {
                    // 站点原始 err 可能带接口地址/站名:展示前脱敏
                    val masked = SourceIdentityMask.mask(absXml.msg)
                    toastEvent.value = masked
                    enterEmpty(masked)
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
                val siteOrder = info.seriesMap?.keys?.toList().orEmpty()
                // 不等待聚合搜索完成:只按此刻已发现的同名源计数。若只有当前源或线路,
                // 不读画质记忆、不发探测请求,直接使用历史线路(有效时)或站点默认线路起播。
                val allowQualitySelection = LineQualitySelector.shouldPreflightQuality(
                    knownMatchingSourceCount(recordKey),
                    siteOrder.size,
                )
                if (info.playFlag == null || info.seriesMap?.containsKey(info.playFlag) != true) {
                    info.playFlag = if (allowQualitySelection) {
                        val cap = App.getInstance()?.let { DeviceCapability.capHeight(it) } ?: 0
                        val remembered = VideoQualityMemory.lookupAll(recordKey, recordId, siteOrder)
                        LineQualitySelector.pickFromMemory(remembered, cap) ?: siteOrder.firstOrNull()
                    } else {
                        siteOrder.firstOrNull()
                    }
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
                publishLineQualityHeights()
                // 详情就绪即探一次未测线路:第一次观看也有画质标签(这里不做"有记忆才探"的 gating)
                probeMissingLineQualities()
                if (searchTitle.isEmpty() && !info.name.isNullOrEmpty()) {
                    searchTitle = info.name.trim()
                    startSourceSearch()
                }
                vodName = mVideo.name ?: vodName
                publishHeader()
                if (!playingList.isNullOrEmpty()) switchSnapshot = null
                pageState.value = PageState.Ready
                // 当前源已取到:聚合搜索预热没有存在意义了,直接撤掉(省一整轮上百站搜索)
                mainHandler.removeCallbacks(sourceSearchPrewarm)
                bumpRevision()
                // 仅在已有多个同名源且当前源有多条线路时预检;单源/单线路直接起播,不等待。
                // 传 detailBuildToken 快照:探测期间换了片的话,快照与当前值就不相等了。
                applyFirstWatchProbe(info, recordKey, recordId, detailBuildToken, allowQualitySelection)
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
        // ⚠️ 这里必须置位:当前源已确认取不到内容。换源接管的触发信号现在由
        // currentSourceConfirmedEmpty 承担,不再依赖 pageState 是否为 Empty
        // (原因见该字段 KDoc —— v1.0.33 就是因为守卫让页面停在 Loading、
        // 而 shouldAutoTakeOver 又要求 Empty 才接管,导致换源链整个断掉)。
        currentSourceConfirmedEmpty = true
        // 空详情一律留页(空态带换源列表),只有源侧真的报错才提示并退出 —— 源抖动不该表现为"闪退"
        if (isSourceErrorMsg(msg)) {
            if (rollbackManualSwitch(msg)) return
            if (fallbackToPreviousTarget(msg)) return
            LOG.i("echo-detail-finish reason=source-msg msg=$msg key=$sourceKey id=$vodId")
            resetEngineState(keepChips = false)
            // 站点原始 err 可能带接口地址/站名:展示前脱敏
            toastEvent.value = SourceIdentityMask.mask(msg)
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
        // 站点原始 err 可能带接口地址/站名:展示前脱敏
        if (!reason.isNullOrEmpty()) toastEvent.value = SourceIdentityMask.mask(reason)
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
        val candidatePool = ApiConfig.get().getSourceBeanList()
            .filter { it.isSearchable() && it.isQuickSearch() && (effectiveChecked == null || effectiveChecked.containsKey(it.key)) }
        // 被屏蔽的源不进自动换源候选池(防滥用封禁机制,P2):放在 take(上限) **之前**,
        // 免得坏源白占候选名额;整池被滤空时 fail-open 回退,不会让换源直接无候选。
        val sources = SourceHealthFilter.filter(candidatePool)
            .sortedBy { it.key != home.key }
            .take(fallbackPoolCap)
        // 诊断包靠这行判断"预热到底有没有触发、候选池多大" —— 真机日志被 ROM 屏蔽时,
        // 文件日志(仅 diag 包落盘)是唯一可观测点。前缀 echo-source- 已在 LOG 白名单里。
        LOG.i("echo-source-search start title=" + title + " pool=" + sources.size + " token=$myToken")
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
                // 聚合搜索全部结束 = 兜底链的结论已定论。
                // 若此时页面还停在加载态,说明 [enterEmpty] 曾因为"搜索在途"而延后过
                // (那是为了消掉"一闪而过的暂无片源",见 enterEmpty 的 KDoc)。
                // 这里必须补上收口,否则搜索一结束页面就永远卡在 Loading —— 
                // 延后空态的代价是把"何时宣告无资源"从"此刻"推迟到了"搜索结束时",
                // 收口责任也就跟着转移到了这里。
                settleDeferredEmpty()
            }
        }
    }

    /**
     * 聚合搜索结束后,补做一次被 [enterEmpty] 延后的空态判定。
     *
     * <p>只在"页面仍是 Loading"时动手:已经有详情(Ready)就绝不碰,避免把正常页面
     * 误判成空;已经在空态也不重复走 [markCurrentVodUnavailable] —— 那条链自带防抖,
     * 但没必要让同一部片在一个会话里被记第二次。
     */
    private fun settleDeferredEmpty() {
        if (pageState.value !is PageState.Loading) return
        // fallbackActive 为 true 时说明还在自动换源链上,收口交给 [loadNextFallbackCandidate];
        // 它会把候选取完再走 [finishFallbackWithoutResult],那时才该进空态。
        if (fallbackActive) return
        LOG.i("echo-detail-empty-settle key=$sourceKey id=$vodId")
        enterEmpty(str(R.string.player_play_failed_all))
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

    /** 当前源 + 已返回的同名影片候选源;不等待仍在进行的搜索,未知候选不增加起播延迟。 */
    private fun knownMatchingSourceCount(currentSourceKey: String): Int {
        val keys = synchronized(fallbackCandidates) {
            LinkedHashSet<String>().also { found ->
                fallbackCandidates.forEach { video ->
                    video.sourceKey?.takeIf { it.isNotBlank() }?.let(found::add)
                }
            }
        }
        if (currentSourceKey.isNotBlank()) keys.add(currentSourceKey)
        return keys.size
    }

    /**
     * 现在是否该由"聚合搜索候选"自动接管当前页面。
     *
     * <p><b>为什么不只看 `fallbackAutoSwitch`</b>:它是可变标记,而 [loadNextFallbackCandidate]
     * 在"候选为空且搜索未在跑"时会走 [finishFallbackWithoutResult] → [resetEngineState],
     * 把刚置上的 `fallbackAutoSwitch` 清掉。一旦清掉就**永久失去接管能力** ——
     * 外部表现正是"底部候选列表一直在刷新、却永远不自动切过去"。
     *
     * <p>补一条不依赖该标记的判据:**当前源已确认取不到内容**时,有候选就该换。
     * 这条判据由 [currentSourceConfirmedEmpty] 直接决定,不受任何标记清场影响。
     *
     * <p>⚠️ **它原来读的是 `pageState.value is PageState.Empty`,而那正是 v1.0.33 的回归根因**:
     * 空态既是给用户看的 UI 态,又是换源链的触发信号,一个字段扛两个职责。
     * v1.0.33 为了消灭"一闪而过的暂无片源"给 `enterEmpty` 加了守卫,页面于是永远停在
     * Loading、永远进不了 Empty ⇒ 这条判据永远为假 ⇒ 候选站一个都不试 ⇒
     * **每部片子都卡在转圈圈**(用户报"无法进入详情页、视频无法播放")。
     * 改成读独立的 [currentSourceConfirmedEmpty] 后,UI 态可以延后、内部信号照旧。
     *
     * <p>不会造成反复切换:`candidateKeys` 在 `resetEngineState(keepChips = true)` 里**不清**,
     * 已见过的候选进不了 `fresh`,`onSearchResultEvent` 不会为同一批候选重复触发。
     */
    private fun shouldAutoTakeOver(): Boolean =
        !fallbackLoadingCandidate && (fallbackAutoSwitch || currentSourceConfirmedEmpty)

    private fun publishSourceChips() {
        val candidates = synchronized(fallbackCandidates) { fallbackCandidates.toList() }
        sourceChips.value = candidates
            .filter { !usedSourceKeys.contains(it.sourceKey) && it.sourceKey != sourceKey }
            .map { video -> SourceChip(video.sourceKey.orEmpty()) }
            .distinctBy { it.key }
    }

    /**
     * 候选源的**匿名**展示名。**必须**只依赖列表下标,不能碰 `ApiConfig.getSource(key).name` ——
     * 那个名字就是要藏起来的身份信息。返回空串表示"这一行什么都不用显示"(调用方跳过该 chip)。
     */
    fun sourceLabelFor(index: Int): String =
        SourceIdentityMask.anonymousLabel(index, str(R.string.common_source_anonymous_prefix))

    /** 详情页候选源那一行的分区标题。同样不能带任何源身份信息。 */
    fun sourceSectionTitle(): String = str(R.string.detail_switch_source)

    fun candidateForKey(key: String): Movie.Video? =
        synchronized(fallbackCandidates) { fallbackCandidates.firstOrNull { it.sourceKey == key } }

    fun switchSource(video: Movie.Video) {
        stopPlaybackForSwitch()
        usedSourceKeys.add(video.sourceKey.orEmpty())
        vodName = video.name ?: vodName
        vodPicture = video.pic ?: vodPicture
        publishHeader()
        resetEngineState(keepChips = true)
        loadDetail(video.id.orEmpty(), video.sourceKey.orEmpty())
    }

    /**
     * 多来源已知且当前详情含多条线路时做**真实画质预检**,在当前详情线路中选出更合适的一档。
     *
     * <p>预算内补测未知线路,已记忆候选不重复探测。单源或单线路直接起播,不等待预检。
     *
     * <p>只探**直连型**线路:`VodSeries.url` 以 http 开头才探(与 `PlayLoader.shouldDirectPlay` 同口径)。
     * 需要爬虫 `playerContent` 才能拿到真地址的线路一律跳过 —— 那条路要 1~3 秒/条,不能挡起播。
     * 探不到就静默降级,绝不阻塞播放。
     *
     * <p><b>所以"画质优先"的真实覆盖面 = 直连型线路</b>。爬虫型线路的画质由另一条路径积累:
     * 起播时 [ResolvedUrlQualityProbe] 复用解析结果测一次,看过一次之后记忆里就有真值,
     * 下次打开这部片时 [VideoQualityMemory.lookupAll] 直接可用、零延迟参与本函数的选择。
     *
     * <p><b>探测失败不会"退化成按 flag 名猜"</b>:探测不成功的线路被
     * [LineQualitySelector.probeVariantsParallel] 的 `filterNotNull` 整条剔除,不会带着
     * 空分辨率进入 [VideoQualityPolicy.pickBest]。最终保持**站点原始顺序**起播,
     * 这正是期望行为 —— 按名字猜画质是错的(站点常把"4K"写在 540P 地址上)。
     */
    private suspend fun applyFirstWatchProbe(
        info: VodInfo,
        siteKey: String,
        vodId: String,
        token: Int,
        allowQualitySelection: Boolean,
    ) {
        // 单源/单线路由调用方直接起播,不读配置、不查记忆、不做网络请求。
        if (!allowQualitySelection) return
        // 「画质选项」三档分流(口径见 DeviceCapability.QualityMode):速度优先跳过全部起播前探测。
        // 实测记忆回写在 MusicSessionDelegate,不受档位影响 —— 速度优先下照常积累,
        // 用户切回自动/画质档后立即受益。
        val mode = DeviceCapability.QualityMode.current()
        if (!mode.shouldProbeOnFirstWatch) return
        val seriesMap = info.seriesMap ?: return
        val siteOrder = seriesMap.keys.toList()
        val app = App.getInstance() ?: return
        val cap = DeviceCapability.capHeight(app)
        val remembered = VideoQualityMemory.lookupAll(siteKey, vodId, siteOrder)
        val flagsToProbe = LineQualitySelector.candidatesToProbe(
            siteOrder,
            remembered,
            cap,
            mode.probeLines,
        )
        if (flagsToProbe.isEmpty()) return
        val currentFlag = info.playFlag
        val currentList = seriesMap[currentFlag]
        // 不能写 coerceIn(0, size - 1):size 为 0 时下界 0 > 上界 -1,coerceIn 会抛 IllegalArgumentException
        val index = if (currentList.isNullOrEmpty()) 0 else info.playIndex.coerceIn(0, currentList.size - 1)
        val headers = probeHeaders(siteKey)
        val probe = VideoQualityProbe()
        val probed = withTimeoutOrNull(mode.probeBudgetMs) {
            LineQualitySelector.probeVariantsParallel(
                flags = flagsToProbe,
                resolve = { flag -> directUrlOf(seriesMap[flag], index) },
                probe = { url -> probe.probe(url, headers) },
            )
        }
        // 三种结局都记日志:这是"画质优先到底有没有生效"的唯一可观测点。
        // 探不到(probed 为 null)时按站点原序起播 —— **不是**按 flag 名猜,
        // 见 VideoQualityPolicy 的类注释:站点把"4K"写在 540P 地址上是常事。
        if (probed == null) {
            LOG.i("echo-quality probe-timeout lines=" + flagsToProbe.size + " mode=" + mode.name)
            return
        }
        val pool = remembered + probed
        val chosen = VideoQualityPolicy.pickBest(pool, cap)?.flag
        LOG.i(
            "echo-quality decide mode=" + mode.name + " pool=" + pool.size +
                " probed=" + probed.size + " -> " + (chosen ?: "none") +
                " (was " + (currentFlag ?: "null") + ")"
        )
        if (chosen.isNullOrEmpty() || chosen == currentFlag) return
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

    /**
     * 探测用第几集:取当前播放集,越界时回落到 0。
     *
     * <p>探测的是"这一集的画质",所以必须跟当前集号;但各线路集数可能不同,
     * 越界那条由 [directUrlOf] 的 `getOrNull` 兜成 null 跳过,不会崩。
     */
    private fun probeIndexOf(info: VodInfo): Int {
        val current = info.seriesMap?.get(info.playFlag)
        return if (current.isNullOrEmpty()) 0 else info.playIndex.coerceIn(0, current.size - 1)
    }

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
        publishHeader()
        resetEngineState(keepChips = true)
        toastEvent.value =
            if (reason.isNullOrEmpty()) {
                str(R.string.detail_switch_failed)
            } else {
                // 站点的原始 err 常带接口地址或站名:这里必须脱敏,只留错误语义
                str(R.string.detail_switch_failed_reason, SourceIdentityMask.mask(reason))
            }
        pageState.value = PageState.Ready
        bumpRevision()
        requestPlay()
        return true
    }

    /**
     * 进入终态空态。
     *
     * <p>## ⚠️ 这里必须挡掉"结论未定"的调用
     *
     * <p>真实踩坑(v1.0.33 修的就是这个):用户报"每打开一个新卡片都会先闪一下
     *『暂无片源』,一闪而过"。真机实测 7 次开片 **7 次命中**,空态比正常详情早 206~336ms:
     * ```
     * 12:19:14.351 OPEN     打开卡片
     * 12:19:14.354 SEARCH   聚合搜索启动
     * 12:19:14.560 EMPTY    ← 206ms 后就进了空态(不该进)
     * 12:19:14.896 SYNC     ← 336ms 后正常详情才到,把空态顶掉
     * ```
     *
     * <p>成因是三段逻辑各自都合理、串起来却提前收口:
     * [handleEmptyDetail] → [startFallbackIfNeeded] → [loadNextFallbackCandidate]。
     * 此刻聚合搜索**刚启动、候选还没产出**,`loadNextFallbackCandidate` 返回 false;
     * 而首次打开时 [rollbackManualSwitch] 的 `switchSnapshot` 为 null、也返回 false;
     * 于是 `if (!rollbackManualSwitch()) enterEmpty()` 成立 —— 在"还在找"的时候
     * 就宣布"找不到"。
     *
     * <p>判据用 [sourcesSearching]:聚合搜索还在跑就说明**候选池未定论**,
     * 此时任何"没有候选"的结论都不成立。反过来搜索一结束(见 `startSourceSearch`
     * 尾部 `sourcesSearching.value = false` → [loadNextFallbackCandidate])就会真正
     * 收口到 [finishFallbackWithoutResult],那时进空态才是诚实的。
     */
    private fun enterEmpty(msg: String? = null) {
        if (sourcesSearching.value) {
            // ⚠️ 这条日志是「为什么页面没进空态」的唯一可观测点,别删。
            LOG.i("echo-detail-empty-deferred key=$sourceKey id=$vodId reason=search-in-flight msg=$msg")
            return
        }
        sendCommand(PlaybackCommand.ClearSourceSwitchTip)
        LOG.i("echo-detail-empty-state msg=$msg key=$sourceKey id=$vodId")
        pageState.value = PageState.Empty(msg)
        // 方案 B 第二层(点空回写):走到这里说明"当前源 + 聚合换源都没能拿到内容",
        // 是"这部片子当前真的没资源"的可靠信号 —— 记下来,下次搜索/首页不再显示它的海报。
        //
        // ⚠️ 刻意**只在这一步**记,不在 handleEmptyDetail 入口记:入口处换源链还没跑完,
        // 站点抖动返回的空会被误判成"永久没资源",好片就此消失。真正的空只有终态才算。
        markCurrentVodUnavailable()
    }

    /**
     * 把当前片标记为无资源并通知列表刷新。
     *
     * <p>标记失败不影响任何播放逻辑 —— 这是一条纯体验优化,不该有能力影响主流程。
     */
    private fun markCurrentVodUnavailable() {
        val site = sourceKey
        val id = vodId
        if (site.isEmpty() || id.isEmpty()) return
        viewModelScope.launch {
            try {
                val siteName = ApiConfig.get().getSource(site)?.name.orEmpty()
                val fresh = AvailabilityMemory.markUnavailable(site, id, siteName)
                LOG.i("echo-unavailable mark site=$site vod=$id accepted=$fresh")
                // 通知搜索页/首页把这条卡片摘掉。**广播**而非回调注入:
                // 列表页的 ViewModel 与本页面无引用关系,等详情页被回收后回调就断了,
                // 而"点空 → 返回列表看到海报还在"正是这条链路最需要生效的场景。
                // 两边本来都注册在 EventBus 上,多一个事件类型比维护反向依赖便宜。
                if (fresh) {
                    EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_VOD_UNAVAILABLE))
                }
                // 同页面内的手动钩子仍然可用(便于将来单点调试/定向刷新)
                availabilitySink?.invoke()
            } catch (t: Throwable) {
                LOG.d("AvailabilityMemory", "mark failed: " + t.message)
            }
        }
    }

    /**
     * 可选的"资源状态变化"回调,由需要即时刷新的宿主注入。
     *
     * <p>主路径已改为 EventBus 广播([RefreshEvent.TYPE_VOD_UNAVAILABLE]),这个字段只作为
     * 补充手段保留:它拿不到跨页面场景(宿主已销毁时字段为空),别指望它做主链路。
     */
    var availabilitySink: (() -> Unit)? = null

    /**
     * 线路耗尽后的换源入口(播放侧在"所有线路试完"或"质量看门狗判定持续卡顿"时调用)。
     *
     * <p>与 [onDetailUnavailable] / [handleEmptyDetail] 同因:候选只能来自聚合搜索,
     * 不先保证搜索在跑,[loadNextFallbackCandidate] 会在候选为空时直接收尾并清掉自动接管。
     */
    fun startFallbackAfterLinesExhausted(sourceLevelFailure: Boolean): Boolean {
        if (sourceLevelFailure) recordSourcePlayFailure()
        ensureSourceSearchRunning()
        return startFallbackIfNeeded(auto = true, fromLinesExhausted = true)
    }

    /**
     * 该源放不出来 ⇒ 记一次源级失败(防滥用封禁机制,P1 记录侧)。
     *
     * <p>调用方必须已经排除"纯网络原因"([sourceLevelFailure] 的来历见 DetailActivity):
     * 断网/被墙是所有源一起失败,算到某一个源头上会把好好的源全拉黑。
     *
     * <p>归到"影片"维度用当前片名(而不是"源|片id"):同一部片在不同源的 id 不同,
     * 按 id 计会把"同一部片在两个源都放不出来"当成 2 部,把"≥2 部不同影片"的门槛稀释掉。
     */
    private fun recordSourcePlayFailure() {
        val title = vodInfo?.name ?: vodName
        val banned = SourceHealthMemory.recordFail(SourceFailKind.PLAY_FAILED, sourceKey, title)
        // 触发封禁 ⇒ 广播一次,让首页立刻重算源清单/卡片可见性(见 HomeViewModel.refreshSourceBlock)
        if (banned) EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SOURCE_BLOCK_CHANGE))
    }

    private fun startFallbackIfNeeded(auto: Boolean, fromLinesExhausted: Boolean = false): Boolean {
        // 换源链自己带 4s 候选站超时(见 scheduleDetailTimeout),详情看门狗到此交班,免得两套表互相抢
        disarmDetailWatchdog()
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
            publishHeader()
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
        // 与候选站超时共用同一个 Handler:这把会一并摘掉详情看门狗,标记必须同步归零
        detailWatchdogToken = 0
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
        qualityProbeJob?.cancel()
        qualityProbeJob = null
        cancelSourceSearchRequests(pendingSearchDone.keys.toList())
        pendingSearchDone.clear()
        OkGo.getInstance().cancelTag("detail")
        // ⚠️ 保留老 tag 的撤销:搜索引擎自身已改为按源 tag,但仍可能有旧调用点/在途请求用它。
        // 打中不了也无副作用,不能因为"现在按源撤了"就把这条删掉 —— 删掉等于放弃兜底。
        OkGo.getInstance().cancelTag(SearchHelper.legacySearchRequestTag())
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
        // 用户手动切到的那条线,画质大概率还没有实测值(爬虫型线路只有解析过才知道地址)。
        // 这次切换正好解析它 —— 顺手测一次,用户下次看到这条线就有分辨率标签了。
        // 不做的话 10 条线要用户挨个点一遍才攒齐,标签功能等于形同虚设。
        probeLineOnDemand(flagName)
    }

    /**
     * 手动切线后按需补测该线路画质。
     *
     * <p>与 [probeMissingLineQualities] 的分工:那是"打开面板时探直连型",
     * 这里是"用户刚点过的那条,爬虫型也探"。
     *
     * <p>⚠️ 只探**刚切的那一条**,不批量探:用户手动切线说明他在意这条,
     * 替他决定"其他线也值得探测"既浪费又可能触发站点风控。
     *
     * <p>解析走 [PlayLoader] 原路径,复用其结果,不额外解析 —— 但注意
     * [PlayLoader] 无解析缓存,所以这里只在自己发起的解析完成后测一次,
     * 真正起播那次仍会照常解析(总请求数不增加,只是多一个 ≤256KB 的探测)。
     */
    private fun probeLineOnDemand(flagName: String) {
        val info = vodInfo ?: return
        val mode = DeviceCapability.QualityMode.current()
        if (!mode.shouldProbeOnFirstWatch) return
        val site = sourceKey
        val vod = vodId
        if (site.isEmpty() || vod.isEmpty()) return
        // 已有实测值就别再探:重复请求毫无意义(这条线的画质是稳定的)
        val existing = VideoQualityMemory.lookupAll(site, vod, listOf(flagName))
        if (existing.isNotEmpty()) return
        val index = probeIndexOf(info)
        val direct = directUrlOf(info.seriesMap?.get(flagName), index)
        qualityProbeJob?.cancel()
        qualityProbeJob = viewModelScope.launch {
            val url = direct ?: resolveUrlForProbe(flagName) ?: return@launch
            val probe = VideoQualityProbe()
            val measured = try {
                probe.probe(url, probeHeaders(site))
            } catch (t: Throwable) {
                null
            }
            if (measured == null || !measured.known) return@launch
            if (site != sourceKey || vod != vodId) return@launch
            VideoQualityMemory.record(site, vod, measured.copy(flag = flagName))
            publishLineQualityHeights()
        }
    }

    /**
     * 为画质探测解析一条线路的真实地址(爬虫型线路必须先跑 `playerContent`)。
     *
     * <p>返回 null 表示这条线路拿不到地址(站点未收录/解析失败)—— 调用方静默跳过,
     * 不阻塞播放。
     */
    private suspend fun resolveUrlForProbe(flagName: String): String? {
        return try {
            val list = vodInfo?.seriesMap?.get(flagName) ?: return null
            val series = list.getOrNull(probeIndexOf(vodInfo ?: return null)) ?: return null
            val raw = series.url?.trim().orEmpty()
            if (raw.isEmpty()) return null
            if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
            // 非直链:交给站点爬虫解析。本仓无解析缓存,这里只解析一次给探测用,
            // 起播路径会自行再解析一次(PlayLoader 无缓存,这是既有事实)。
            val bean = ApiConfig.get().getSource(sourceKey) ?: return null
            val spider = ApiConfig.get().getCSP(bean) ?: return null
            val json = spider.playerContent(flagName, raw, ApiConfig.get().getVipParseFlags())
            if (json.isNullOrEmpty()) return null
            parseResolvedUrl(json)
        } catch (t: Throwable) {
            null
        }
    }

    /** 从 `playerContent` 返回的 JSON 里取出真实播放地址。 */
    private fun parseResolvedUrl(json: String): String? {
        return try {
            val obj = org.json.JSONObject(json)
            val url = obj.optString("url", "")
            if (url.isNotEmpty()) url else null
        } catch (t: Throwable) {
            null
        }
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
        // 实测画质写进记忆了:重算一次「线路N · 1080P」。不发这个事件的话,
        // 标签本次会话永远刷不出来 —— 探测跑在独立作用域上,这是它唯一的回传途径。
        if (event.type == RefreshEvent.TYPE_LINE_QUALITY_MEASURED) {
            publishLineQualityHeights()
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
        // 内核实测画质(MusicSessionDelegate.maybeRememberMeasuredQuality)是在**起播之后**
        // 才拿到真实宽高并写进 VideoQualityMemory 的。
        // v1.0.45 起那条路径会自己发 TYPE_LINE_QUALITY_MEASURED,标签在 ~2 秒内就刷新 ——
        // 这里保留一次刷新只是**兜底**(例如事件在页面重建时错过),
        // 它由 PlaybackProgress 每集发一次的 TYPE_PLAYBACK_STARTED 触发,节流在 30 秒级,
        // 所以绝不能当成主路径:1.0.43 实测正是"分辨率 2 秒测到、标签 32 秒才更新"。
        publishLineQualityHeights()
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
        private const val DETAIL_FALLBACK_DETAIL_TIMEOUT_MS = 3000L
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
        /**
         * 详情发出后延迟多久预热聚合搜索(毫秒)。
         *
         * <p>取值权衡:必须**短于**详情看门狗的最短时限(20s,[DetailLoadWatchdog.MIN_TIMEOUT_MS]),
         * 否则"当前源没这部剧"时兜底仍要等看门狗先判失败,等于没优化;又要**足够长**,
         * 让"当前源正常返回"的绝大多数点击在预热触发前就已经 Ready,搜索压根不用发。
         * 1.5s 的依据:站内有本地命中缓存的详情多在几百毫秒内回,1.5s 已是宽松余量。
         */
        private const val SOURCE_SEARCH_PREWARM_DELAY_MS = 1500L

        // i18n: keep —— 源侧"没有数据"的哨兵值;误翻会把空结果判成源报错,详情页提示后自动关闭
        private const val SOURCE_EMPTY_MSG = "数据列表"

        internal fun isSourceErrorMsg(msg: String?): Boolean =
            !msg.isNullOrEmpty() && msg != SOURCE_EMPTY_MSG
    }
}
