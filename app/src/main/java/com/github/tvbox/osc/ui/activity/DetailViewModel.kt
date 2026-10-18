package com.github.tvbox.osc.ui.activity

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.AvailabilityMemory
import com.github.tvbox.osc.util.CompletenessMemory
import com.github.tvbox.osc.util.DetailNoListMemory
import com.github.tvbox.osc.util.SearchSettings
import com.github.tvbox.osc.util.SearchResultCache
import com.github.tvbox.osc.util.NoHitSourceCache
import com.github.tvbox.osc.util.NoHitSourcePolicy
import com.github.tvbox.osc.util.PlayTitleMatch
import com.github.tvbox.osc.util.SourceCompletenessPolicy
import com.github.tvbox.osc.util.QualityLabelPolicy
import com.github.tvbox.osc.util.EpisodeMatcher
import com.github.tvbox.osc.util.EpisodeNormalizer
import com.github.tvbox.osc.util.EpisodeSetMath
import com.github.tvbox.osc.util.EpisodeType
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.RoomDataManger
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.CrossSourceHintPolicy
import com.github.tvbox.osc.player.DeviceCapability
import com.github.tvbox.osc.player.LineQualitySelector
import com.github.tvbox.osc.player.LineHealthMemory
import com.github.tvbox.osc.player.MiniProbeBudget
import com.github.tvbox.osc.player.MiniProbeGate
import com.github.tvbox.osc.player.PlaybackFailureMemory
import com.github.tvbox.osc.player.PlaybackSession
import com.github.tvbox.osc.player.QualityGovernor
import com.github.tvbox.osc.player.SourceQualityScore
import com.github.tvbox.osc.player.VideoQualityMemory
import com.github.tvbox.osc.player.VideoQualityPolicy
import com.github.tvbox.osc.player.VideoQualityProbe
import com.github.tvbox.osc.player.ProbeRoundMemory
import com.github.tvbox.osc.player.ProbeRoundPolicy
import com.github.tvbox.osc.util.EpisodeTotals
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.HistoryWriter
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.SearchHelper
import com.github.tvbox.osc.util.SourceHealthFilter
import com.github.tvbox.osc.util.SourceHealthMemory
import com.github.tvbox.osc.util.SourceWeightMemory
import com.github.tvbox.osc.util.SourceWeightPolicy
import com.github.tvbox.osc.util.TrailerPolicy
import com.github.tvbox.osc.util.SourceIdentityMask
import com.github.tvbox.osc.util.TitleMatcher
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.sourcedata.SourceRuntimeState
import com.github.tvbox.osc.sourcedata.MiniProbeReader
import com.github.tvbox.osc.sourcedata.observeAsFlow
import com.lzy.okgo.OkGo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
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
     *
     * @param score 源级评分([SourceQualityScore.score],0..100);**null = 无历史数据**。
     *              仅用于**展示**(界面显"源N · 75分"或"源N · 暂无评分"),**不**参与排序键之外的决策。
     *              见 source-line-scoring-design-2026-10-14.md 出路 A。
     * @param suspect 这一场是否已被试过又放不出来（true ⇒ 标签前挂 🟡）。
     *              隐藏态不会出现在这里（视图层已在 [publishSourceChips] 的出口滤掉）。
     *              号（"源N"）刻意**不放进这个数据类**：它只由 [sourceLabelFor] 从台账按 key 取，
     *              两处各存一份就会有一天不一致。
     */
    data class SourceChip(
        val key: String,
        val score: Double? = null,
        val suspect: Boolean = false,
    )

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

    /**
     * **用户主动换源**后、该轮搜索仍在跑的时间窗(2026-10-14 用户反馈 #2 修正)。
     *
     * <p>用于「换源」按钮的置灰判据 —— **只**防"用户自己连点两次换源",
     * **不**因"进详情页的自动搜索在跑"而禁用(那会让按钮一进页面就灰,逻辑不通,用户反馈)。
     * 在 [onSwitchSourceClick] 置 true,在该轮搜索结束([sourcesSearching] 回落 false)时清 false。
     */
    val manualSwitching = MutableStateFlow(false)

    /**
     * 候选源列表的**当前页**(2026-10-14 详情页「换源」分页设计,0 起)。
     *
     * <p>每页 [CandidatePager.PAGE_SIZE] 个;`sourceChips` 里存的是**当前页的切片**
     * (而非全量),由 [publishSourceChips] 按页切出。翻页走 [nextChipPage]/[prevChipPage]。
     */
    val chipPage = MutableStateFlow(0)

    /**
     * 用户是否**主动发起过换源**([onSwitchSourceClick])。
     *
     * <p>作用:换源是"用户明确要找别的源"⇒ 搜索跑全量,**不再因起播成功而冻结**
     * (冻结会像现在这样,候选没积累完就停,用户看不到源列表)。
     * 内容边界(换片/换源/退出)复位。
     */
    private var manualSwitchActive = false

    /**
     * 聚合搜索进度(S6 分批递进,2026-10-09):只带"已问到第几个 / 本轮范围内共多少个"。
     * 修复前无候选时用户对着转圈最长 ~59s 零反馈(120 源 × 单源 8s 超时 ÷ 并发 12),
     * 搜索本身不变,只是把"系统在动"这件事告诉用户;搜索结束/换代/链收口时置 null。
     *
     * <p>**发现数不在这里带**:它必须与下面那一行源列表同一个口径,而列表在视图层还会
     * 滤掉正在播的源和隐藏的源。以前把它塞进进度对象、再由进度对象喂 UI,两处口径就分过叉
     * (真机 v1.1.45 顶部说"4 个"、下面只有 3 个)。现在 UI 直接问 [totalCandidateCount]。
     */
    data class SearchProgress(val done: Int, val total: Int)
    val searchProgress = MutableStateFlow<SearchProgress?>(null)
    /**
     * 这一场是否**已为这部片发起过**聚合搜索。措辞判据要用它区分"还没搜"与"搜过了、确实没有"
     * (见 [SourceSearchStatus.line])—— 两者都表现为"列表为空、不在搜",不给这个位,
     * 搜完 0 个的那一行会一直催用户"点击搜索其他片源",让他一遍遍点。
     *
     * <p>只随换片复位(换源不复位:他还在同一部片里,搜过就是搜过)。
     */
    val sourceSearchStarted = MutableStateFlow(false)
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

    /** 探测负结果冷却(会话级,键=线路 flag):探测跑通但拿不到尺寸/解析失败的线路不落记忆,
     *  若不冷却会被 plentiful→probe-request 每 30s 反复空探(2026-10-10 真机观察,单次虽小但纯浪费) */
    private val probeNegativeAt = HashMap<String, Long>()
    private var probeNegativeScope = ""
    /**
     * **起播前/面板**那条探测路径的负结果冷却,**与 [probeNegativeAt] 分表**(2026-10-10 真机教训)。
     *
     * <p>两条路径的**探测口径不同**:
     * <ul>
     *   <li>本表所属路径(预算 2s/15s)探的是 `series.url` —— 而真机坐实它常常**不是播放地址**;
     *       探测失败因此**不构成"这条线路不可用"的证据**。</li>
     *   <li>[probeNegativeAt] 所属的起播后 sweep 走 [resolveTargetForProbe],与播放同源(先问爬虫),
     *       它的失败才是可信证据。</li>
     * </ul>
     * 两者曾共用一张表,真机后果直接可见:起播前对镜像地址的一次 404 把那条线路拉黑 10 分钟,
     * **导致 sweep 里唯一"本可同源探到"的线路被静默跳过**(本轮 missing 由 6 掉到 5)。
     */
    private val probeDirectNegativeAt = HashMap<String, Long>()
    /** 探测穷尽闩(按片):全是爬虫型线路(无可探直连)时,补探测对本片永远无产出,
     *  置闩后忽略后续 probe-request(换片自动复位)—— 否则 plentiful 每 30s 空转一轮 */
    private var probeExhausted = false

    /**
     * 起播后"源内有界扫描"的**进度**(键 = 站点|片id + 已测成功的线路名集合)。
     *
     * <p>为什么不是"扫过/没扫过"的布尔:扫描可能在中途被播放压力打断(见
     * [sweepSourceLinesForQuality] 的让路判据)。用布尔的话,中断即等于"这轮扫过了",
     * 这部片在本会话里**永远不会再扫**,画质标签永久缺失。记进度则可以从断点续扫。
     *
     * <p>只记**测成功的**线路:失败(超时/解析不出/读不到尺寸)的线路下次仍值得重试,
     * 它们的重复次数由 [ProbeRoundPolicy] 另行兜住,不在这里管。
     */
    private var sourceSweepKey = ""
    private val sourceSweepDoneFlags = HashSet<String>()

    /** 当前片当前源是否已收敛(sweep 已出 verdict、不会再自动升档)。跨源提示只在收敛后出场。 */
    private var sourceConverged = false


    /** 跨源提示的一次性闩(键 = 站点|片id):用户关掉或点过之后,本会话同片不再弹。 */
    private var crossHintDismissedKey = ""

    /**
     * 跨源提示:对端有实测记忆、且明显优于当前源时给出的一行。
     *
     * <p>只存 [sourceKey]/[video] 凭据与实测 S;标签由 UI 按下标匿名生成,绝不带站名。
     */
    data class CrossSourceHint(val sourceKey: String, val label: String)

    val crossSourceHint = MutableStateFlow<CrossSourceHint?>(null)

    /**
     * 用户点"去看看"或"关掉"时调用:**本片**本会话不再提示。
     *
     * <p>闩按片(searchTitle)而非按源:换源后新源就是当前源,若闩只绑源 key,刚换过去的片
     * 会被旧源的记忆"弹回来",形成抖动。按片闩掉后整部片本会话安静。
     */
    fun dismissCrossSourceHint() {
        crossHintDismissedKey = searchTitle.ifEmpty { vodName }.ifEmpty { vodId }
        crossSourceHint.value = null
    }

    /**
     * 重算跨源提示。纯本地查询(记忆表 + 候选列表),零网络、零线程池。
     *
     * <p>仅在源内已收敛([sourceConverged])后才可能出场;正在换源链上或本片已闩掉则清空。
     */
    private fun refreshCrossSourceHint() {
        val info = vodInfo
        val titleKey = searchTitle.ifEmpty { vodName }.ifEmpty { vodId }
        if (info == null || !sourceConverged || crossHintDismissedKey == titleKey ||
            fallbackActive || fallbackLoadingCandidate
        ) {
            crossSourceHint.value = null
            return
        }
        val mode = DeviceCapability.effectiveMode()
        val qualityFirst = mode == DeviceCapability.QualityMode.QUALITY_FIRST
        val currentFlag = info.playFlag.orEmpty()
        val current = VideoQualityMemory.lookupAll(sourceKey, vodId, listOf(currentFlag)).firstOrNull()
        // 在候选里找"实测最清晰"的一条;候选按 sourceKey 逐个查询,保持零网络
        // 候选可达几十条:一次批量查询(单次全表解析),避免逐源 lookupBest 的 N 次 JSON 解析
        //
        // ⚠️ 过滤口径必须与 publishSourceChips **完全一致**(排除当前源 + 已用过的源)。
        // 否则会出现"提示命中了已用源"的情形:那个源不在 chips 里 ⇒ UI 的
        // `hintIndex >= 0` 判定为假 ⇒ 提示渲染不出来;而 crossSourceHint 又是非 null ⇒
        // 调用点 `if (crossSourceHint.value == null) runMiniProbe...` 永远不成立 ⇒
        // **迷你探测被一个看不见的提示彻底堵死**。
        val candidates = synchronized(fallbackCandidates) { fallbackCandidates.toList() }
            .mapNotNull { video ->
                val k = video.sourceKey.orEmpty()
                val id = video.id.orEmpty()
                if (k.isEmpty() || id.isEmpty()) null else k to id
            }
            // 与 chips 同一口径(2026-10-15):视图层只排除**当前源**,已切过的源仍在列表里、也能切回,
            // 所以提示也可以指向它们。多排除一类只会让提示变哑(上面注释里那个 bug 就是这么来的)。
            .filter { (k, _) -> k != sourceKey }
            .distinct()
        val bestByTitle = VideoQualityMemory.lookupBestBatch(candidates)
        var bestHint: CrossSourceHint? = null
        var bestS = -1
        var bestVariant: VideoQualityPolicy.Variant? = null
        candidates.forEach { (candKey, candId) ->
            val cand = bestByTitle["$candKey|$candId"] ?: return@forEach
            val s = VideoQualityPolicy.sharpness(cand)
            if (s > bestS) {
                bestS = s
                bestVariant = cand
                bestHint = CrossSourceHint(candKey, LineLabelPolicy.qualitySuffix(cand.height))
            }
        }
        val show = CrossSourceHintPolicy.shouldHint(
            current = current,
            candidate = bestVariant,
            qualityFirst = qualityFirst,
            sourceConverged = true,
        )
        crossSourceHint.value = if (show) bestHint else null
    }

    private var vodName = ""
    /**
     * 用户**点进来那一部**的片名，整场固定。
     *
     * <p>为什么不能拿 vodName 或 searchTitle 当基准：两个都会被换源改掉 ——
     * `switchSource`/换源链写 `vodName = video.name`，详情就绪又写 `searchTitle = info.name`。
     * 于是"这条源给的是不是同一部片"就没有参照物了（真机 v1.1.49：《希望》切到《希望一次过》
     * 之后，页面自己变成了那部片，系统从此认为一切正常）。
     */
    private var entryTitle = ""
    private var vodPicture = ""
    private var fromCollect = false

    private val sourceViewModel = SourceViewModel()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val navStack = DetailNavStack()
    private var searchJob: Job? = null
    /** 异步补齐其余线路画质的小任务;换片/重开面板时取消,避免上一部的探测结果串到新内容 */
    private var qualityProbeJob: Job? = null
    /** 面板按需探测的独立 Job：面板一关就撤，不留在后台跑网络 */
    private var panelProbeJob: Job? = null
    /**
     * 一次起播尝试只贡献**一条**失败事实：线路耗尽、详情超时、质量看门狗可能都指向同一次失败，
     * 记两条就会把一次抖动凑成"两条不同来源的证据"而误隐藏一个源。随 resetEngineState 复位。
     */
    private var failureCountedForAttempt = false

    /** 这一场里面板已经试过的线路（含失败的）。爬虫每条 1~3 秒，反复开面板不该反复重烧 */
    private val panelProbedFlags = HashSet<String>()
    /**
     * 本场是否已出过首帧 —— 面板探测**只在首帧之后**跑。
     *
     * <p>它必须串行(同类 Spider 不能并发)、每条 1~3 秒，放在起播在途时跑就是拿用户的
     * 首帧时长去换几个标签，与本仓"起播快第一优先"直接冲突。
     */
    private var playbackStartedHere = false
    private var detailBuildToken = 0

    /**
     * 详情请求代次(V4),替代原「换 `SourceViewModel` 实例」的迟到回包隔离:每次发起新的内容请求
     * (换片/换源/重试)自增并随请求下传,回包代次不符即丢。**fallback 换站不自增** ——
     * 同一代内的候选站谁先回都算当前。
     */
    private var detailRequestToken = 0

    private val fallbackCandidates = ArrayList<Movie.Video>()

    /**
     * 本轮换源搜索的**同名命中**(源 key → 列表),用于回存 [SearchResultCache](2026-10-13)。
     *
     * <p>只收同名条目:缓存是给"这部片的换源候选"用的,塞进同名无关条目会让下次进入灌进错误候选。
     * 只在主线程读写(事件回调与 viewModelScope 协程都在 Main),无需并发容器。
     */
    private val fallbackSearchHits = HashMap<String, List<Movie.Video>>()

    /**
     * 本轮**答过话、却一个同名条目都没给**的源(2026-10-15 零命中短路)。
     *
     * <p>与 [fallbackSearchHits] 共用同一套合并写/边界兑现时机(见 [scheduleFallbackHitsSave]):
     * 逐源落盘会让主线程反复序列化整张表,而"不落盘"就等于这次搜过白搜。
     * 超时/异常/解析崩**不进这张表** —— 那是源的耐心问题,不是它有没有(见 [NoHitSourcePolicy])。
     */
    private val pendingNoHitSources = ArrayList<String>()

    // P-完整性方案(2026-10-09):当前片的权威参照系与起播/纠偏状态
    // completenessTitleKey/OwnCount 由 recordCompleteness 在详情就绪时写入
    private var completenessTitleKey = ""
    private var completenessOwnSet: Set<Int> = emptySet()
    /** 起播跳过当前源(记忆已知严重不全)后等待换源链结果;链收口无果回退起播当前源 */
    private var pendingCompletenessSkip = false
    /** 播放中纠偏每部片只触发一次,防止候选事件反复引发切换 */
    private var completenessCorrected = false
    private val candidateKeys = HashSet<String>()
    /**
     * 换源列表的**身份台账**(号绑源、不绑位置)。追加口只有 [appendCandidates],
     * 清表只有 [resetEngineState] 的换片分支 —— 别处不许自己动,否则号与表会分叉。
     */
    private val sourceBoard = SourceBoardLedger()
    /**
     * 这块面板**代表哪部片**——只为诊断日志留着出场片名用。
     *
     * <p>为什么不让日志现场取 searchTitle:换片时 [resetContentState] 先把 searchTitle/vodName 清空了,
     * `cleared` 那条打出来的永远是空串(真机 v1.1.47 四条全是 `(pre-title)`),
     * 于是"同一部片有没有被重复清表"这个唯一要回答的问题仍然答不上来。
     */
    private var boardTitle = ""
    private val triedKeys = HashSet<String>()
    private val usedSourceKeys = HashSet<String>()

    /**
     * **当前搜源档号**(2026-10-17 用户定案的分档口径)。从 [ExpandPoolPolicy.FIRST_WAVE] 起,
     * 每档"全部收口"后**只有用户点过「继续找源」才自增**(自动链永远只跑第 1 档)。
     * 内容边界(换片)复位为 1。
     */
    private var poolRound = ExpandPoolPolicy.FIRST_WAVE

    /**
     * **用户排队要再搜几档**(2026-10-17)。点一次「继续找源」= 加一档:
     * 剩下的档由他一档一档点着推进,不是一次点满全池 —— 那是"替他决定还要等多久"。
     */
    private var manualWavesQueued = 0

    /** 还有下一档可搜 ⇒ 海报态那一行下面出「继续找源」按钮(自动那档跑完且没搜尽时为 true) */
    val canContinueSourceSearch = MutableStateFlow(false)

    /**
     * 本场搜索的可用源总数 + **已经问过的源数**(2026-10-17)。
     *
     * <p>必须是独立的快照流,不能等页面去读 `searchProgress` —— 收口那一刻 searchProgress 会被清空,
     * 而"问过多少个"恰恰是收口那一行话要用的数字(没有它,用户只看到一句"此影片暂无法播放")。
     */
    val sourcePoolTotal = MutableStateFlow(0)
    val sourceSearchAsked = MutableStateFlow(0)

    /**
     * **本场搜索要不要走零命中短路 —— 只在本片第一次算池子时定一次,之后每档沿用**(2026-10-17)。
     *
     * <p>不这么做会让"档"的分母在档间漂移:自动那档跳过"已知没这部片"的源 ⇒ 池子 273,
     * 手动那档按老口径不跳 ⇒ 池子 315。于是第 1 档是 273 的一半(137),第 2 档却按 315 的
     * 2/3(210)去切 —— 真机 v1.1.67 12:38 那一次正是这个形状。分档要成立,整场只能用同一个池子。
     * null = 本片还没算过第一档。
     */
    private var noHitShortCircuit: Boolean? = null

    /**
     * **本场分档计划的分母**(= 本片第一次算池子时的"排序后全池"大小,整场定死,2026-10-17)。
     *
     * <p>不能拿"跳过已知没有之后剩下的数量"当分母:那份名单会在第一档里边搜边长
     * (真机 13:21 那次:第 1 档 total=315 切 1/3=105,点一下「继续找源」后名单长出 54 个,
     * 第 2 档的池子只剩 261 ⇒ 直接按"全量"把第 2、3 档合成一档跑完,他要的"两次手动点击"变成一次)。
     * 短路只该决定**这一档实际投出哪些源**,不该挪档的边界。0 = 本场还没算过。
     */
    private var sessionPlanTotal = 0

    private fun shortCircuitThisSession(userInitiated: Boolean): Boolean {
        val decided = noHitShortCircuit
            ?: NoHitSourcePolicy.allowShortCircuit(userInitiated, manualSwitchActive)
                .also { noHitShortCircuit = it }
        return decided
    }

    /**
     * **本次搜索已投出过的源**(2026-10-13 增量扩池)。跨扩池轮次**保留**,
     * 用于"扩池时只投新增源" —— 这是"扩池后**继续搜**、不重头搜"的核心:
     * 每波只把 [searchedSourceKeys] 里没有的源投出去,已搜过的**绝不重发**。
     * 换片时清空([startSourceSearch] 中 title 变化处)。
     */
    private val searchedSourceKeys = HashSet<String>()

    /**
     * 本场**判定为超时**的源数([SourceSearchStatus.emptyReason] 的唯一入参来源)。
     *
     * <p>只服务一件事:换源链收口进空态时决定说"这部片没有"还是"多数源没答上"。
     * 真机《潜伏6》收口时问过 58 个源、其中 49 个超时,却统一报"此影片暂无法播放" ——
     * 那句话把"网络/源站问题"说成了"片子不存在",用户该做的事完全不同。
     *
     * <p>随 [searchedSourceKeys] 一起在换片时复位([startSourceSearch] 的 title 变化处)。
     */
    private var timedOutSourceCount = 0

    /**
     * 本轮已经写过**降权证据**的源(2026-10-14):同一轮内每源只记一次。
     *
     * <p>为什么必须去重:同一源可能因重试轮/慢速回包被判定多次,不去重就会把"一次没答话"
     * 扣成好几次权重。口径与搜索页 SearchViewModel.failRecordedForToken 一致。
     * 每开新一轮搜索时清空([startSourceSearch])—— 新一轮里的再次沉默是新的一次证据。
     */
    private val evidenceRecordedKeys = HashSet<String>()

    /**
     * 本轮**已经交出过候选**的源(2026-10-14,变体补发的判据)。
     *
     * <p>只对"答了话却一条候选都没给"的源补发查询变体 —— 有命中的源不再多问一次,
     * 请求量才不会随变体数线性翻倍。
     */
    private val sourcesWithHit = HashSet<String>()

    /**
     * 本轮应搜的源数(按 [ExpandPoolPolicy] 逐轮扩大:1/3 → 1/2 → 2/3 → 全量)。
     *
     * <p>2026-10-14 移除「自动扩池」开关与"固定上界"退路:那条退路存在的唯一理由是"我怕全量搜太慢",
     * 而现在①手动换源本来就一次投满搜索范围、②并发会按超时占比自适应退让,
     * 固定截断只会让冷门片**永远搜不到源**(源集合 300+ 时前 120 个之外的源从不参与换源)。
     */
    private fun roundPoolSize(total: Int): Int = ExpandPoolPolicy.poolSizeForWave(total, poolRound)
    /**
     * 详情换源链的**当前并发度**(2026-10-14 起自适应,不再写死 12)。
     *
     * <p>0 = 本页还没算过。每轮搜索开始时按用户「搜索线程数」折算出上限
     * (详情页要给正在缓冲的播放流让路 ⇒ 打对折并封顶 16),波与波之间按上一波的超时占比增减。
     * 跨轮保留:上一波判出的弱网不该因为换了一次源就又被抹平。
     */
    private var detailConcurrency = 0
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

    /**
     * **加载体验预算**:页面停在 Loading 超过这个时长,就必须给用户一个确定画面 ——
     * 要么起播已找到的候选,要么明说"暂无片源"。绝不允许无限转圈。
     *
     * <p>它**独立于网络与看门狗**:看门狗(20~45s)按"站点限时"算,比聚合搜索还短,
     * 且 [onDetailResult] 只要收到本代回包就撤防 —— 哪怕回包是解析失败的空详情
     * (真机实测:js_douban 缺 list 字段那次,看门狗布防 2 次从未触发)。
     * 预算计时器则**不因回包而撤**,只在离开 Loading 时自然失效(到点后先查 pageState)。
     *
     * <p>取 15s 的依据:详情侧并发上限 16(默认线程 32 打对折,见
     * [SearchBatchPolicy.detailConcurrencyCap])、单源超时 8s ⇒ 首屏一批 24 源最坏 ≈ 16s。
     * 预算取 15s **略小于**最坏值,是刻意取舍:宁可早 1 秒给出确定画面,
     * 也不让用户对着转圈把最坏情况等满。
     */
    private val loadingBudget = Runnable { onLoadingBudgetExpired() }

    /**
     * 候选站详情超时(3s):换源链里当前候选迟迟不回包时,跳到下一个候选。
     *
     * <p>**必须是具名字段,不能写成匿名 lambda**。它和 [detailWatchdog]、[loadingBudget]
     * 共用同一个 [mainHandler],而 [cancelDetailTimeout] 要能**精确地只撤自己布防的任务** ——
     * 匿名 lambda 拿不到引用,只能退化成 `removeCallbacksAndMessages(null)`,
     * 那会把同一 Handler 上别人的任务一并清掉(历史上就是这样误撤了加载预算)。
     */
    private val candidateDetailTimeout = Runnable {
        detailTimeoutScheduled = false
        if (fallbackLoadingCandidate) {
            fallbackLoadingCandidate = false
            OkGo.getInstance().cancelTag("detail")
            loadNextFallbackCandidate()
        }
    }

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

    /**
     * 本轮聚合搜索是否已被"起播成功"**冻结**(2026-10-13,P1):冻结后不再投新源,
     * 但**在途的照跑**(它们的结果仍计入候选与台账,不丢证据)。
     *
     * <p>判据见 [SearchBatchPolicy.shouldFreezeOnPlaybackStarted],兑现见 [freezeFallbackSearch]。
     * 每轮搜索开头复位 —— 下一次需要候选时(例如这条源后来放不出来)必须能重新搜。
     */
    private var searchFrozen = false

    /**
     * 命中回存的**合并写**句柄(2026-10-13):命中是逐源到达的,而落盘要序列化整张命中表,
     * 不能每来一条就写一次(120 源 × 整表 JSON,主线程扛不住)。
     *
     * <p>合并窗口内仍会丢失"最后一次写入之后才到的命中",所以**边界必须显式 flush**:
     * 换片/退出页面/起播冻结/搜索收工(见 [flushFallbackHitsSave] 的各调用点)。
     */
    private var hitsSaveJob: Job? = null

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
        sendCommand(PlaybackCommand.StopForContentSwitch(target.sourceKey + "|" + target.vodId))
        sendCommand(PlaybackCommand.SetEpisodeSheetOpen(false))
        fromCollect = target.fromCollect
        vodName = target.title
        entryTitle = target.title
        vodPicture = target.picture
        publishHeader()
        loadDetail(target.vodId, target.sourceKey)
        LOG.i("echo-detail-open collect=$fromCollect key=$sourceKey id=$vodId")
        // ⚠️ 这里**不发**聚合搜索(2026-10-12 改,2026-10-14 定案)。
        //
        // 原实现是 `if (vodName.isNotEmpty()) startSourceSearch()` —— 即"点开卡片的同时
        // 立刻用 12 并发去搜几百个源"。后果(真机实测):
        //   - 详情取数(用户真正在等的那一个请求)要跟几百个搜索请求抢线程与带宽;
        //   - 日志里能看到 `echo-srcban pool kept=350/360` 与大量 SEARCH_TIMEOUT 刷屏,
        //     全部发生在详情还没出来的时候;
        //   - 用户感受就是"点卡片进去变慢了"。
        //
        // 现行判据:**能起播就不自动搜全站**。当前源交出内容 ⇒ 直接起播,一个搜索请求都不发;
        // 只有当前源交不出内容时才按需启动([ensureSourceSearchRunning] → [startSourceSearch]),
        // 触发点共四处:
        //   - 详情空/不可用:handleEmptyDetail
        //   - 详情看门狗超时:onDetailLoadTimedOut
        //   - 详情就绪但 playingList 为空 / 记忆判定当前源严重不全:startFallbackIfNeeded
        //   - 用户主动换源:onSwitchSourceClick → startSourceSearch(userInitiated = true)
    }

    /** 换片时收掉上一部在途的聚合搜索,并让旧 token 立即失效(搜索回包仍靠 token 失配兜底) */
    private fun cancelInFlightContent() {
        // ⚠️ 必须先回存:下面就要换片,searchTitle 随后会被 resetContentState 清空 ——
        // 那一刻之后再落盘,键就取不到了(命中白白丢掉,正是"同一部片再次进入依旧慢"的成因)。
        flushFallbackHitsSave()
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
        manualSwitching.value = false
        // 换片:上一部排过的"再搜一档"与「继续找源」按钮都不带给新片(每部都从第 1 档起)
        manualWavesQueued = 0
        canContinueSourceSearch.value = false
        sourcePoolTotal.value = 0
        sourceSearchAsked.value = 0
        qualityOptions.value = emptyList()
        qualitySelected.value = 0
        episodeSheet.value = false
        toastEvent.value = null
        finishEvent.value = false
        pageState.value = PageState.Loading
        // 每次进入 Loading 都重新起算体验预算:换片/换源是新的等待,旧的到点不该算在这次头上
        armLoadingBudget()
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
        // 流量节省**不再跳过补探测**(2026-10-14 语义修正):
        // 旧理由是"升档已被门控关死 ⇒ 探测白花钱",但现在省流量只拒"切到更高码率"这一刻,
        // 探测本身单条 ≤256KB、且产出的是跨会话的实测记忆(用户哪天关掉开关即刻受益)。
        // 成本仍要收:省流量时只补一条最可能收益的直连线路。
        val saver = DeviceCapability.trafficSaverOn()
        val mode = DeviceCapability.effectiveMode()
        if (!mode.shouldProbeOnFirstWatch) return
        val remembered = VideoQualityMemory.lookupAll(sourceKey, vodId, siteOrder)
        // 负冷却按片隔离:换片清空(不同片同名 flag 的坏地址互不相干)
        val scopeKey = "$sourceKey|$vodId"
        if (probeNegativeScope != scopeKey) {
            probeNegativeScope = scopeKey
            probeNegativeAt.clear()
            probeDirectNegativeAt.clear()
            probeExhausted = false
        }
        if (probeExhausted) {
            LOG.i("echo-line-probe skip: exhausted this vod (no probe-able lines)")
            return
        }
        val nowMs = System.currentTimeMillis()
        val missing = siteOrder.filter { flag ->
            remembered.none { it.flag == flag } && !probeDirectCooldownActive(flag, nowMs)
        }
        if (missing.isEmpty()) return
        // 只探**直连型**线路(与 PlayLoader.shouldDirectPlay 同口径):爬虫型线路要先跑 getPlay
        // 解析才拿得到地址,每条 1~3 秒且同类 Spider 不能并发,代价与"更快开始播放"冲突。
        // 拿不到 URL 的线路由 probeVariantsParallel 静默跳过,不阻塞其余线路。
        val index = probeIndexOf(info)
        val targets = missing.filter { directUrlOf(info.seriesMap?.get(it), index) != null }
            .take(if (saver) 1 else 3)
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
                // ⚠️ 这句只能**在 targets 真的为空时**说:它原先是无条件拼在行尾的,于是真机里
                // 出现过 `direct=2 ... (empty ⇒ reason=no-direct-url)` 这种自相矛盾的行
                // (v1.1.47 虎牙两条线路都是直连型、探测照样跑),读日志的人会当成"没目标"而误判方向。
                // 走得到这一行却为空,唯一可能就是 no-direct-url —— 其余分支(lines<=1 / mode-off /
                // all-remembered)都在本行**之前**已经 return(2026-10-10 修正并列文案的初衷不变)。
                if (targets.isEmpty()) " (empty ⇒ reason=no-direct-url)" else ""
        )
        if (targets.isEmpty()) {
            // 全是爬虫型线路:本管线永远探不了(解析要走 getPlay,1~3s/条且 Spider 不能并发)。
            // 置闩停止响应 probe-request;爬虫线路的实测靠"看过一次"的内核上报积累(既有路径)。
            probeExhausted = true
            LOG.i("echo-line-probe exhausted: all missing lines are crawler-type, latch on")
            return
        }
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
                    // 把自己允许的总时间告诉探测本身:它据此决定还要不要换身份再试一档。
                    // 不说就会拿默认值去排重试,然后被上面这个 2 秒 withTimeout 整条取消 ——
                    // 连第一次已经拿到的结果都丢(v1.1.35 踩的就是这类"预算各说各话")。
                    probe = { url ->
                        probe.probe(url, headers, LineQualityProbeBudget.totalMs)
                    },
                )
            }.orEmpty()
            var knownNew = 0
            probed.forEach { v ->
                if (v.flag.isEmpty()) return@forEach
                if (!v.known) {
                    // 探测请求跑通但拿不到尺寸(坏地址/错误 JSON):进**本路径专属**的冷却,不落记忆污染。
                    // ⚠️ 刻意**不**写 probeNegativeAt(同源 sweep 的表):本路径探的是 series.url,
                    // 失败不构成"线路不可用"的证据 —— 共表会连带把 sweep 的同源探测一起拉黑。
                    probeDirectNegativeAt[v.flag] = System.currentTimeMillis()
                    return@forEach
                }
                probeDirectNegativeAt.remove(v.flag)
                VideoQualityMemory.record(siteKey, vod, v)
                knownNew++
            }
            // 压根没拿到结果(resolve 失败)的目标同样进**本路径专属**的冷却
            targets.forEach { flag ->
                if (probed.none { it.flag == flag }) probeDirectNegativeAt[flag] = System.currentTimeMillis()
            }
            if (knownNew > 0) LOG.i("echo-line-probe new-known=" + knownNew + " (negative=${probeDirectNegativeAt.size})")
            // 会话可能已经换片/换源:那时刷新会把新内容的画质写进来
            if (siteKey != sourceKey || vod != vodId) return@launch
            publishLineQualityHeights()
        }
    }

    /** 探测预算:单条 800ms(见 VideoQualityProbe),留一倍余量给并发调度 */
    private object LineQualityProbeBudget {
        const val totalMs = 2000L

        /** 负结果冷却:探不到尺寸/解析失败的线路,10 分钟内不再重探 */
        const val negativeCooldownMs = 10L * 60 * 1000
    }

    private fun probeNegativeCooldownActive(flag: String, nowMs: Long): Boolean {
        val at = probeNegativeAt[flag] ?: return false
        return nowMs - at < LineQualityProbeBudget.negativeCooldownMs
    }

    /** [probeNegativeAt] 的对应版本,但查的是"起播前/面板"那条 **series.url 口径**的冷却表。 */
    private fun probeDirectCooldownActive(flag: String, nowMs: Long): Boolean {
        val at = probeDirectNegativeAt[flag] ?: return false
        return nowMs - at < LineQualityProbeBudget.negativeCooldownMs
    }

    /**
     * 起播后"源内有界扫描"的预算(见《选线机制设计》附录 G)。
     *
     * <p>为什么必须有封顶:扫描**必然探不完** —— 源池 300+、爬虫解析 1~3s/条且同类 Spider
     * 不能并发(SPIDER_POOL 只有 3 线程)、还要给播放/预载让路。所以口径是
     * "预算内见到的最优就切",不是"探完再切"(附录 C)。
     */
    /**
     * 源内扫描的预算。
     *
     * <p>⚠️ **刻意只有"时间"这一个预算，没有"最多探 N 条"的条数上限。**
     *
     * <p>条数上限无论填 2 还是 8，都是拍脑袋的魔法数字 —— 线路数一旦超过它就会被**静默漏掉**。
     * 真机 23:24 就是这么炸的：该片 9 条线路里 7 条是 crawler-type，旧 `deepProbeMax = 2`
     * 直接砍掉 5 条，用户当场质问"为什么剩下的都不探"。
     *
     * <p>改成时间预算后：**预算内尽可能多探，探不完的留到下次**（负冷却不写、记忆照留，
     * 下一轮继续）。条数由预算自然决定，不再写死。实测深探失败是**毫秒级立即返回**，
     * 只有真正慢的解析才吃时间，所以放开条数并不会真把预算烧满。
     */
    /**
     * **打开线路面板时**按需探测面板上那些线路的实测画质（2026-10-16 用户定案）。
     *
     * <p>## 补的是什么洞
     * 真机《镖人》：一个源 9 条线路，**只有正在播的那条有清晰度**。原因是其余线路的
     * `series.url` 不是播放地址（要爬虫现算），而面板/线路行的轻探只走直链这条路 ——
     * 日志里那句 `direct=3`（9 条里只有 3 条有直链可探）就是它。
     * 想给爬虫型线路也拿到真实分辨率，唯一办法是让探测走播放同一条解析路（`playerContent`），
     * 代价是**每条 1~3 秒、同类 Spider 不能并发** —— 与"起播快第一优先"直接冲突。
     * 所以用户拍板：这个钱只在**他打开线路面板、正盯着这些线路看**的时刻花。
     *
     * <p>## 四条把成本按住的边界（缺一条就会变成"后台偷偷狂解析"）
     * ① **首帧之后**才跑（[playbackStartedHere]）；② 只在这一次面板打开时触发，
     * 关掉就 cancel（见 [dismissEpisodeSheet]）；③ 一次最多 [PANEL_PROBE_LINES] 条、**串行**；
     * ④ 每条线路**这一场只试一次**（[panelProbedFlags]），失败也不重开 —— 与源级"只观测不重罚"同一口径。
     *
     * <p>解析走 [resolveTargetForProbe]：**与播放同一条路**，探到的地址就是播的地址
     * （否则又是"探测打 A 站、播放打 B 站"那种假失败）。
     */
    private fun probeCrawlerLinesForLinePanel() {
        val info = vodInfo ?: return
        if (!playbackStartedHere) return            // 起播在途：一律不抢这份带宽
        val siteOrder = info.seriesMap?.keys?.toList().orEmpty()
        if (siteOrder.size <= 1) return             // 单线路没有可切换对象
        if (!DeviceCapability.effectiveMode().shouldProbeOnFirstWatch) return
        val remembered = VideoQualityMemory.lookupAll(sourceKey, vodId, siteOrder)
        val targets = ArrayList<String>()
        for (flag in siteOrder) {
            if (targets.size >= PANEL_PROBE_LINES) break
            if (remembered.any { it.flag == flag }) continue
            if (probeNegativeCooldownActive(flag, System.currentTimeMillis())) continue
            if (!panelProbedFlags.add(flag)) continue
            targets.add(flag)
        }
        if (targets.isEmpty()) return
        LOG.i(
            "echo-line-probe panel start site=" + sourceKey + " vod=" + vodId +
                " lines=" + targets.size + " of=" + siteOrder.size +
                " remembered=" + remembered.size
        )
        panelProbeJob?.cancel()
        val siteKey = sourceKey
        val vod = vodId
        panelProbeJob = viewModelScope.launch(Dispatchers.IO) {
            val probe = VideoQualityProbe()
            val headers = probeHeaders(siteKey)
            for (flag in targets) {
                if (!isActive) return@launch
                val (target, reason) = resolveTargetForProbe(flag)
                if (target == null) {
                    // 解析给不出地址：与"探不到分辨率"是两件事，分开记（否则下次排查会以为探测失败）
                    LOG.i("echo-line-probe panel no-url flag=" + flag + " reason=" + reason)
                    continue
                }
                val via = withTimeoutOrNull(SourceSweepBudget.deepProbeTimeoutMs) {
                    probe.probe(target.url, mergeProbeHeaders(headers, target.headers))
                }
                if (via != null && via.known) {
                    VideoQualityMemory.record(siteKey, vod, via.copy(flag = flag))
                    LOG.i(
                        "echo-line-probe panel ok flag=" + flag +
                            " size=" + via.width + "x" + via.height + " via=" + target.via
                    )
                    launch(Dispatchers.Main) { publishLineQualityHeights() }
                } else {
                    LOG.i("echo-line-probe panel miss flag=" + flag + " via=" + target.via)
                }
            }
        }
    }

    private object SourceSweepBudget {
        /** 唯一预算：本轮扫描的总时长（后台串行，不占前台） */
        const val totalMs = 45_000L

        /** 单条深探预算(解析 1~3s + 探测 ≤0.8s,留一倍余量) */
        const val deepProbeTimeoutMs = 6_000L
    }

    /**
     * 起播后的**源内有界扫描**:把当前源的线路实测一遍,统一比较后**一次**切到最优。
     *
     * <p>为什么需要它(既有两条路径都补不上这个洞):
     * <ul>
     *   <li>起播前探测([applyFirstWatchProbe])只给 1~2s、只探直连型 ⇒ 冷记忆时几乎必留缺口;</li>
     *   <li>补探测([probeMissingLineQualities])只"写记忆",**从不比较、从不切换** ——
     *       升档要等看门狗下一个富余周期,真机上往往错过(用户已经换片了)。</li>
     * </ul>
     *
     * <p>**不阻塞首帧**:只在**首帧渲染之后**跑([onPlaybackFirstFrame],由
     * `TYPE_PLAYBACK_FIRST_FRAME` 驱动),每片每会话按线路累计一次(见 [sourceSweepDoneFlags])。
     * 切入时间也不早于 [QualityGovernor.MIN_REMEDY_DELAY_MS] —— 刚看到画面就黑屏一次最刺眼。
     *
     * <p>⚠️ 尚未实现"播放卡顿时暂停扫描":需要把看门狗的劣质信号广播到 VM
     * (现有两条探测路径同样没有,属同类欠账)。
     */
    private fun sweepSourceLinesForQuality() {
        val info = vodInfo ?: return
        val siteKey = sourceKey
        val vod = vodId
        if (siteKey.isEmpty() || vod.isEmpty()) return
        val sweepKey = "$siteKey|$vod"
        // 换片/换源 ⇒ 进度作废(键不同,顺手把上一部的集合清掉,免得越攒越大)
        if (sourceSweepKey != sweepKey) {
            sourceSweepKey = sweepKey
            sourceSweepDoneFlags.clear()
        }
        // 速度优先:用户明确不要这类额外请求(与起播前探测同一口径)
        val mode = DeviceCapability.effectiveMode()
        if (!mode.shouldProbeOnFirstWatch) return
        // 流量节省**不再整段跳过**:该管的是"要不要切到更费流量的档"(那才是持续大开销),
        // 不是"要不要知道每条线路是多少分辨率"(单条 ≤256KB、一次性)。
        // 探测照常、切换由 QualityGovernor 的 trafficSaver 判据拒 —— 与"实测记忆回写不受档位
        // 影响"这条既有口径一致:省流量开着也仍在积累,用户哪天关掉立即受益。代价仍要收:省流量时只探标称最高的两条。
        val trafficSaver = DeviceCapability.trafficSaverOn()
        val seriesMap = info.seriesMap ?: return
        val siteOrder = seriesMap.keys.toList()
        if (siteOrder.size <= 1) return
        val app = App.getInstance() ?: return
        val cap = DeviceCapability.capHeight(app)
        val currentFlag = info.playFlag ?: return
        val remembered = VideoQualityMemory.lookupAll(siteKey, vod, siteOrder)
        // 达标锚(手机与 TV **同锚** = 1080P 级):不再按设备打折 ——
        // 手机锚曾用 1280,导致 1280×534 这类"宽而扁"的低清片被判"已达锚",
        // 自动画质档于是按"达锚即停"不补救,用户必须手动切画质优先才动(2026-10-10 真机教训)
        val anchor = VideoQualityPolicy.ANCHOR_WIDTH
        // 2026-10-14 定案:「流畅前提下尽量更高」⇒ 达锚**不再等于停止扫描**(旧此处直接 return,
        // 于是 1080P 之上永远看不见 4K)。达锚只改变**探测范围**:只探标称档高于当前线的线路,
        // 同档或更低的不花这份预算。真切与否仍由 θ/ΔS + 4K 天花板 + 设备上限 + 线路健康决定。
        val currentBefore = remembered.firstOrNull { it.flag == currentFlag }
        val meetsAnchorNow = VideoQualityPolicy.meetsAnchor(currentBefore, anchor) &&
            mode != DeviceCapability.QualityMode.QUALITY_FIRST
        val currentLabelPriority = QualityLabelPolicy.priorityFromLabel(currentFlag)
        val nowMs = System.currentTimeMillis()
        // 候选 = 无实测记忆 且 不在负冷却中。
        //
        // **不再有任何条数上限**（旧代码这里 `.take(maxLines)`、深探处 `.take(deepProbeMax)`
        // —— 两个魔法数字合起来把 7 条砍成 2 条）。唯一约束是 SourceSweepBudget.totalMs。
        //
        // 顺序改为**按标签先验降序**（4K/蓝光 这类先探）：预算有限时先花在最可能有收益的线路上，
        // 而不是按站点原序从头取 —— 那样很可能先把预算烧在一堆标清上。
        // ⚠️ 标签先验在这里**只决定"先探谁"**，绝不参与"是否切换"的判定（判定只用实测 S）。
        val probed = siteOrder
            .filter { flag ->
                remembered.none { it.flag == flag } && !probeNegativeCooldownActive(flag, nowMs)
            }
            .sortedByDescending { QualityLabelPolicy.priorityFromLabel(it) }
        // 两道"只收窄探测范围、不收窄结论"的收口:
        // ① 当前已达 1080P 锚(非画质优先)**且标签有语义** ⇒ 只探标称更高的那些 —— 同档探出来也不会切
        //    (θ/ΔS 挡着),白花预算。⚠️ 标签无语义时(站点常写"线路1..5",priority 全为 0)
        //    一律**不收窄**:一收窄就永远拿不到更高档,旧的"达锚即停"只是换个形式回来(2026-10-14 自查)。
        // ② 流量节省 ⇒ 最多探两条最可能收益的(切档仍由 QualityGovernor 拒绝,这里只图把画质记忆填上)。
        val narrowed = if (meetsAnchorNow && currentLabelPriority > 0) {
            probed.filter { QualityLabelPolicy.priorityFromLabel(it) > currentLabelPriority }
        } else {
            probed
        }
        val missing = if (trafficSaver) narrowed.take(2) else narrowed
        if (missing.isEmpty()) {
            LOG.i("echo-quality sweep skip: nothing missing")
            return
        }
        // 断点续扫:本会话已经**测成功**的线路不再重测。
        // 若这一批全都在已完成集合里 ⇒ 本轮无事可做(不是"永久不再扫",下一集/换线后集合可能变化)。
        val remaining = missing.filter { it !in sourceSweepDoneFlags }
        if (remaining.isEmpty()) {
            LOG.i("echo-quality sweep skip: all-probed n=${missing.size} site=$siteKey vod=$vod")
            return
        }
        // 重复探测的上限(2026-10-15 用户定案):当前源所有线路探一遍之后,最多再重复两遍,
        // 三遍都没测出新东西就认"这条片这个源确实探不到",不再每次进详情页重烧一遍带宽。
        // 真机触发点:《狮子王》那个源 4 条线路有 3 条永远测不出(其中一条是 AES 加密分片列表,
        // 分辨率按 HLS 规范根本不写在子列表里),而每一遍都把三条重新排进计划。
        // 判据与存储见 player/ProbeRoundPolicy;测到任何东西就清零,所以"探得动"的源不受影响。
        val doneRounds = ProbeRoundMemory.rounds(siteKey, vod)
        if (ProbeRoundPolicy.shouldStop(doneRounds)) {
            LOG.i("echo-quality sweep skip: probe-rounds=" + doneRounds + " site=" + siteKey + " vod=" + vod)
            return
        }
        // ⚠️ 这里**不写**"扫过了"的标记。标记的语义是"进度"(见 [sourceSweepDoneFlags]):
        // 每测成功一条就加一个 flag,全部完成才等价于"扫完了"。
        // 旧实现在**循环开始前**就把布尔标记写上,于是中途被压力/换片打断时,
        // 这部片在本会话里永远不会再扫 —— 用户只是恰好卡了一下,画质标签就永久缺失。
        // 不再按 currentFlag 取集号:统一解析路径自己定位集号(resolveTargetForProbe → probeIndexOf),
        // 因为探测地址与"当前线路"无关(旧代码用当前线路的 index 去取别条线路的 series.url,本身就是近似)
        val headers = probeHeaders(siteKey)
        val probe = VideoQualityProbe()
        val token = detailBuildToken
        LOG.i(
            "echo-quality sweep start missing=" + missing.size +
                " remaining=" + remaining.size + " lines=" + siteOrder.size +
                " cap=" + cap + " mode=" + mode.name +
                " anchorSkipLower=" + meetsAnchorNow + " saver=" + trafficSaver
        )
        qualityProbeJob?.cancel()
        qualityProbeJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            // **统一路径:逐条「解析(爬虫优先,与播放同源) → 探测」**。
            //
            // 为什么不再分"直链型/爬虫型":真机坐实 `series.url` **常常不是**播放地址 ——
            // 同一条线的播放地址由爬虫现算(带签名/防盗链 header),而 `series.url` 只是站点那套镜像。
            // "是 http 就直探"于是探到了**另一个地址**上,必然失败(真机:同一线路,
            // 播放拿到 ixigua 的 mp4、探测打的是 fengbao 的 m3u8 → 404)。
            // 解析与播放同路,探测才有意义。
            //
            // 成本由 SourceSweepBudget.totalMs(45s)统一兜底:预算耗尽即停,并记下还剩几条没跑。
            // 条数**不设上限**(旧的 maxLines/deepProbeMax 两个魔法数字已删)。
            LOG.i("echo-quality deep-probe plan=" + remaining.size + " (budget-bound, no quota)")
            val deepProbed = ArrayList<VideoQualityPolicy.Variant>()
            var viaSpider = 0
            var viaDirect = 0
            for (i in remaining.indices) {
                val flag = remaining[i]
                if (System.currentTimeMillis() - startedAt > SourceSweepBudget.totalMs) {
                    LOG.i("echo-quality deep-probe budget-out skipped=" + (remaining.size - i))
                    break
                }
                // ⚠️ "解析失败" 与 "探测失败" 必须分开记。
                //
                // 旧代码把两者合成一个 null,于是"这条线路为什么没被测到"**完全不可观测** ——
                // 真机排障就卡在这里:7 条全是 crawler-type、probed=0,
                // 却分不清是爬虫没解析出地址、解析/探测超时、还是拿到了地址读不出分辨率。
                var why = ""
                var via = ""
                var completed = false
                val measured = withTimeoutOrNull(SourceSweepBudget.deepProbeTimeoutMs) {
                    val (target, reason) = resolveTargetForProbe(flag)
                    if (target == null) {
                        why = reason
                        null
                    } else {
                        via = target.via
                        probe.probe(target.url, mergeProbeHeaders(headers, target.headers))
                            .also { completed = true }
                    }
                }
                if (measured == null || !measured.known) {
                    val kind = when {
                        // 拿不到地址:**带具体原因**(spider-missing / spider-empty / spider-no-url /
                        // series-empty / index-oob / exception:*)—— 旧实现一律记 no-url,
                        // 真机无法区分"爬虫没加载"与"爬虫确实给不出",排障只能猜。
                        why.isNotEmpty() -> "no-url(" + why + ")"
                        // ⚠️ 必须用 completed 区分,不能只看 measured == null:
                        // probe()「跑完了但读不出尺寸」**也**返回 null,旧写法把它误记成 timeout ——
                        // 真机实测(v1.0.55):MG超清/QY超清 明明 3ms 就返回了 probe-no-size,却记成 timeout,
                        // 与前面的 probe-no-size 行自相矛盾,读日志的人会被带偏。
                        !completed -> "timeout"            // 解析 + 探测整体超时(没跑完)
                        else -> "no-size"                  // 跑完了但读不出分辨率
                    }
                    LOG.i("echo-quality deep-probe " + kind + " flag=" + flag)
                    probeNegativeAt[flag] = System.currentTimeMillis()
                    continue
                }
                // 这一条**测成功**了 ⇒ 记进进度集合。中断(压力/换片/预算)后重进时不再重测它,
                // 于是"被打断"只损失当次进度,不会让整片永远停在开头几条线路上。
                sourceSweepDoneFlags.add(flag)
                if (via == "direct") viaDirect++ else viaSpider++
                LOG.i(
                    "echo-quality deep-probe ok flag=" + flag +
                        " size=" + measured.width + "x" + measured.height + " via=" + via
                )
                deepProbed.add(measured.copy(flag = flag))
            }
            LOG.i("echo-quality deep-probe via spider=" + viaSpider + " direct=" + viaDirect)
            // 这一遍的账:测到任何东西就清零,一条都没测出来才累计一轮(见 [ProbeRoundPolicy])。
            // 记在"会话是否还有效"之前 —— 哪怕他已经换片,这一遍带宽确实花掉了,结论照样该攒。
            ProbeRoundMemory.finishRound(siteKey, vod, deepProbed.size)
            val allProbed = deepProbed
            // 落记忆:负结果进冷却、不落记忆(与补探测同一口径,避免 0×0 污染)
            allProbed.forEach { v ->
                if (v.flag.isEmpty()) return@forEach
                if (!v.known) {
                    probeNegativeAt[v.flag] = System.currentTimeMillis()
                    return@forEach
                }
                probeNegativeAt.remove(v.flag)
                VideoQualityMemory.record(siteKey, vod, v)
            }
            // 不再做"循环收尾统一拉黑":失败在循环内已**逐条**进负冷却;
            // 而被 `totalMs` 预算跳过的线路**压根没被尝试**,收尾拉黑它们等于
            // "因为没时间探,所以下次也别探了" —— 旧实现对 crawlerTargets 就有这个毛病。
            // 会话可能已经换片/换源:那时这次扫描的对象已失效
            if (siteKey != sourceKey || vod != vodId || token != detailBuildToken) return@launch
            if (allProbed.isNotEmpty()) publishLineQualityHeights()
            // 起播后不久不切:等够 MIN_REMEDY_DELAY_MS 再动手
            val waitMs = QualityGovernor.MIN_REMEDY_DELAY_MS - (System.currentTimeMillis() - startedAt)
            if (waitMs > 0) delay(waitMs)
            if (siteKey != sourceKey || vod != vodId || token != detailBuildToken) return@launch
            // ③ 统一比较:候选 = 记忆(含等待期间内核补写的当前线) + 本次实测;**一次**比较选定
            val freshRemembered = VideoQualityMemory.lookupAll(siteKey, vod, siteOrder)
            val pool = freshRemembered.filter { it.known } + allProbed
            val best = VideoQualityPolicy.pickBest(pool, cap)
            val currentNow = freshRemembered.firstOrNull { it.flag == currentFlag }
            var reject = QualityGovernor.rejectReasonForRemedy(mode, currentNow, best, cap, trafficSaver)
            // 与起播前 [applyFirstWatchProbe] 同一道闸:实测最优 ≠ 能用。一条刚连续失败两次的线路,
            // 探测出的分辨率再高,切过去大概率还是失败,而这里的代价是一次黑屏重起播。
            // 只在"目标可疑且当前不可疑"时拦 —— 当前也可疑时,换一条仍可能更好。
            // 旧实现漏了这一条 ⇒ 重试阶梯刚逃离的坏线,几秒后被画质扫描切回去(2026-10-14 审查发现)。
            if (reject == null && best != null &&
                LineHealthMemory.isSuspect(siteKey, vod, best.flag) &&
                !LineHealthMemory.isSuspect(siteKey, vod, currentFlag)
            ) {
                reject = "target-suspect"
            }
            LOG.i(
                "echo-quality sweep lines=" + missing.size +
                    " via=spider" + viaSpider + "/direct" + viaDirect +
                    " probed=" + allProbed.size +
                    " curS=" + (currentNow?.let { VideoQualityPolicy.sharpness(it) } ?: -1) +
                    " bestS=" + (best?.let { VideoQualityPolicy.sharpness(it) } ?: -1) +
                    " target=" + (best?.flag ?: "none") +
                    " verdict=" + (reject ?: "switch")
            )
            // 源内收敛:verdict 非 switch ⇒ 本源不会再自动升档 ⇒ 允许跨源提示出场
            if (best == null || reject != null) {
                sourceConverged = true
                refreshCrossSourceHint()
                // 提示要有数据才出得来:对端没记忆时按闸门去探一次(默认不发请求,见其 KDoc)
                if (crossSourceHint.value == null) runMiniProbeForCrossSource()
                return@launch
            }
            if (best.flag.isEmpty() || best.flag == currentFlag) {
                sourceConverged = true
                refreshCrossSourceHint()
                if (crossSourceHint.value == null) runMiniProbeForCrossSource()
                return@launch
            }
            switchLineForQuality(best.flag)
        }
    }

    /**
     * 扫描选出的目标线:切过去(换线不换集),与 [onFlagClick] 同一套"集名匹配"逻辑。
     *
     * <p>与 [onFlagClick] 的差别只有来源:这条不是用户点的。但仍**沿用** `manualLineSwitchPending`,
     * 因为那个标记实际承担两件我们都需要的语义:
     * ①让 `PlayContainer` 走 `rememberProgressForSwitch()`(否则换线后从 0 开始,用户丢进度);
     * ②取流成功前不被自动换线覆盖(取流成功后 `PlaybackFetch` 会自行清除,自动兜底能力随即恢复)。
     */
    private fun switchLineForQuality(flagName: String) {
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
        // 爬虫型线路只能在这里补(见函数注释):直连轻探拿不到它们的地址,而面板是
        // 唯一"用户已经在看这些线路"的时刻
        probeCrawlerLinesForLinePanel()
        episodeSheet.value = true
        sendCommand(PlaybackCommand.SetEpisodeSheetOpen(true))
    }

    fun dismissEpisodeSheet() {
        episodeSheet.value = false
        // 面板关了就没人在看这些标签了,继续探只是白占带宽
        panelProbeJob?.cancel()
        panelProbeJob = null
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
        // 换片/换源:源内收敛结论与跨源提示都属于上一个会话,必须清零,否则会串到新片
        sourceConverged = false
        crossSourceHint.value = null
        miniProbeJob?.cancel()
        miniProbeJob = null
        miniProbeDoneTitles.clear()
        miniProbeNegativeAt.clear()
        collected.value = RoomDataManger.isVodCollect(sourceKey, vodId)
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (DetailResponseGuard.isUnloadableTarget(
                vodId,
                sourceBean == null,
                // 索引型源(indexs=1)声明了"只走搜索不进详情":它的详情回包注定没有正片数据,
                // 发出去就是白等一轮再看聚合搜索兜底。直接早退走聚合搜索,省掉这 20~45 秒。
                //
                // S2 升级判据(2026-10-09):配置没声明但**实测行为就是索引型**的源
                // (回包是合法 JSON 却缺 list,30 天内 ≥2 次,见 DetailNoListPolicy)
                // 也按索引型对待 —— js_douban 这类"漏声明"的源不再白等一轮详情超时。
                indexSource = sourceBean?.isIndexSource() == true || DetailNoListMemory.isIndexLike(sourceKey),
            )
        ) {
            onDetailUnavailable()
            return
        }
        pageState.value = PageState.Loading
        armLoadingBudget()
        armDetailWatchdog(key)
        sourceViewModel.getDetail(sourceKey, vodId, false, requestToken)
        // 这里**不发**聚合搜索:能起播就不自动搜全站(2026-10-14 定案)。
        // 只有当前源交不出内容时,才由失败路径按需启动 —— 入口清单见 [applyTarget]。
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

    /** 起算(或重新起算)加载体验预算。只 arm 不需要专门 disarm:到点后先查 pageState,不在 Loading 就无害空转 */
    private fun armLoadingBudget() {
        mainHandler.removeCallbacks(loadingBudget)
        mainHandler.postDelayed(loadingBudget, LOADING_BUDGET_MS)
    }

    /**
     * 预算到点:页面还停在 Loading 就必须给确定画面,绝不允许无限转圈。
     *
     * <p>三种情况:
     * <ol>
     *   <li>已有候选(哪怕只是预告片)⇒ 交给换源链起播它 —— 有内容先给内容;</li>
     *   <li>没有任何候选 ⇒ 强制进空态。⚠️ force 收口**不会**把这部片记成"无资源"
     *       (见 [enterEmpty]),因为聚合搜索可能还在跑,慢源不该被判死刑;</li>
     *   <li>已离开 Loading(Ready/Empty)⇒ 无害空转。</li>
     * </ol>
     */
    private fun onLoadingBudgetExpired() {
        if (pageState.value !is PageState.Loading) return
        val candidates = synchronized(fallbackCandidates) { fallbackCandidates.size }
        LOG.i(
            "echo-detail-budget-expired candidates=$candidates searching=${sourcesSearching.value}" +
                " fallbackActive=$fallbackActive key=$sourceKey id=$vodId"
        )
        if (candidates > 0) {
            fallbackLoadingCandidate = false
            loadNextFallbackCandidate()
            return
        }
        // ⚠️ 换源链还在跑(fallbackActive)时**绝不**提前宣布"暂无片源"(2026-10-09 真机实测修正)。
        //
        // 15s 预算只是"当前源"的体验预算,不是给整条换源链判死刑的 —— 实测 js_douban
        // 预算到点时(candidates=0 searching=true fallbackActive=true),候选正在加载,
        // 旧判据直接 force Empty,把还有 39 秒才到终态的链提前掐死在"暂无片源"上;
        // 而链自己的终态("已轮询完所有片源与线路",见 [finishFallbackWithoutResult])
        // 本来就会正确收口。这里让路即可:链会走到终态,或候选到了起播。
        if (fallbackActive) {
            LOG.i("echo-detail-budget-expired defer-to-fallback key=$sourceKey id=$vodId")
            return
        }
        enterEmpty(str(R.string.detail_empty_source), force = true)
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
        markCurrentSourceGaveNothing("detail-timeout")
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
     * 开新的一代并返回它。只有"发起新的内容请求"才自增:换片、换源、重搜(含 [loadDetail] 的早退分支);
     * fallback 候选站(见 [loadDetailInternal])沿用当前代次。
     */
    private fun nextDetailRequestToken(): Int = ++detailRequestToken

    private fun onDetailUnavailable() {
        // 同 handleEmptyDetail:当前源不可播/不在订阅,也是"当前源没内容"的确认信号。
        currentSourceConfirmedEmpty = true
        markCurrentSourceGaveNothing("detail-unavailable")
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
            // 详情取到了内容 ⇒ "该源详情缺 list"的记忆作废(源在正常服务详情,别再跳过它)。
            // 这是画像唯一的"恢复"通道:此刻 sourceKey 还是请求时的 key(重赋值在后面),
            // 清的正是这份回包归属的源。空详情不清 —— 空详情正是证据本身。
            DetailNoListMemory.clear(sourceKey)
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
            // 预告片不是正片:先剔掉再看"这个源到底有没有这部片"(产品口径,2026-10-09 拍板)。
            // 只剩预告片 ⇒ 视同"没有这部片",走空详情路径继续换源找正片 ——
            // 不这么做的话,预告片会被当成正片起播 2 秒然后失败,换源链又反复选中同一个源,死循环。
            if (!TrailerPolicy.stripTrailers(mVideo)) {
                LOG.i("echo-detail-trailer-only key=$sourceKey id=$vodId name=${mVideo.name}")
                handleEmptyDetail(absXml)
                return
            }
            mVideo.id = vodId
            if (mVideo.name.isNullOrEmpty()) mVideo.name = vodName
            if (mVideo.name.isNullOrEmpty()) mVideo.name = "TVBox"
            if ((mVideo.pic == null || mVideo.pic.isEmpty()) && vodPicture.isNotEmpty()) {
                mVideo.pic = vodPicture
            }
            // 「这是不是用户点进来的那部片」(四层成功判据的第三层)。
            // 不同名就**不进播放链**:一旦进播放态,现有判据会把它当"播成功"——
            // 记一次源级 PLAY_SUCCESS、撤销这条源已有的 🟡、而且再也不会有任何失败信号
            // (真机 v1.1.49:《希望》切到《希望一次过》,详情正常、首帧正常、零报错,
            //  于是既不换源也不标黄,用户看到的就是一张"播成功的另一部片")。
            // 处置形状与"详情不可用/空详情"完全一致(同一套接管链),只是归因不同、日志不同。
            // 判据本身保守:只有"同前缀但后面接的是片名正文"(另一部作品)才判不同,
            // 别名/译名/繁简写法一律 UNKNOWN=放行 —— 错杀一条源比放过一部错片更糟。
            if (PlayTitleMatch.isDifferentFilm(mVideo.name, entryTitle)) {
                LOG.i(
                    "echo-play-verify layer=title other-film key=$sourceKey id=$vodId" +
                        // ⚠️ 别把两边都截成 30 字就完事 —— 真机就是这么被我读错过的:
                        // 一对只差结尾几个字的名字截断后**逐字相同**,却报 other-film,
                        // 看起来像"判据在瞎判"。带上长度与片号才分得清"截断"还是"真不同"。
                        " got=\"" + mVideo.name.orEmpty().take(30) + "\"(len=" + mVideo.name.orEmpty().length + ")" +
                        " want=\"" + entryTitle.take(30) + "\"(len=" + entryTitle.length + ")" +
                        " code=" + PlayTitleMatch.codePair(mVideo.name, entryTitle)
                )
                currentSourceConfirmedEmpty = true
                markCurrentSourceGaveNothing("other-film")
                if (fallbackActive) {
                    fallbackLoadingCandidate = false
                    cancelDetailTimeout()
                    loadNextFallbackCandidate()
                    return
                }
                ensureSourceSearchRunning()
                if (!startFallbackIfNeeded(auto = true)) {
                    if (!rollbackManualSwitch()) enterEmpty()
                }
                return
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
                        LineQualitySelector.pickFromMemory(remembered, cap)
                            // P-质量维度:实测记忆缺失时,按"标签档→覆盖当前集→可数集数"选线
                            ?: bestFlagByLabelAndCompleteness(info)
                            ?: siteOrder.firstOrNull()
                    } else {
                        bestFlagByLabelAndCompleteness(info) ?: siteOrder.firstOrNull()
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
                // 把**当前这个源自己**补进换源候选表(用户 2026-10-15 指出的缺口,见函数注释)
                ensureCurrentSourceCandidate(mVideo)
                recordCompleteness(info)
                publishLineQualityHeights()
                // 详情就绪即探一次未测线路:第一次观看也有画质标签(这里不做"有记忆才探"的 gating)
                probeMissingLineQualities()
                // 2026-10-14 用户定案:**能起播就不自动搜全站**。
                // 原实现"详情就绪即 startSourceSearch()"会让每次进详情页都狂搜全站 322 源
                // (实测 3~3.5 分钟),换源按钮被拖灰、进度又显示不出,用户体验极差。
                // 现在:当前源取到内容 ⇒ 直接起播,**不**主动搜全站;只有当前源放不出来
                // (playingList 为空,见下方 startFallbackIfNeeded)或起播失败时,才按需启动搜索。
                // searchTitle 仍先定格,便于后续 [onSwitchSourceClick]/[ensureSourceSearchRunning] 复用。
                if (searchTitle.isEmpty() && !info.name.isNullOrEmpty()) {
                    searchTitle = info.name.trim()
                }
                vodName = mVideo.name ?: vodName
                publishHeader()
                if (!playingList.isNullOrEmpty()) switchSnapshot = null
                // P-完整性起播决策:记忆已知当前源"严重不全"(如 1/50)⇒ 不起播它,留在 Loading
                // 走换源链(候选已按完整度排序,最全候选先试);链收口无果时回退起播当前源
                // (见 finishFallbackWithoutResult 的 pendingCompletenessSkip 分支)。
                // 权威未知 / 非"严重不全"档:照常秒开。
                // ⚠️ 起播后聚合搜索会被 [freezeFallbackSearch] 冻结(只保留在途回包),
                // **不是**"在播放中并行"—— 后台搜索只存在于详情失败/用户换源这两条路上。
                val confirmedSet = CompletenessMemory.confirmedSet(completenessTitleKey)
                if (SourceCompletenessPolicy.tier(completenessOwnSet, confirmedSet) ==
                    SourceCompletenessPolicy.Tier.BACKUP
                ) {
                    pendingCompletenessSkip = true
                    LOG.i(
                        "echo-completeness skip-origin own=${completenessOwnSet.size}" +
                            " auth=${confirmedSet.size}"
                    )
                    startFallbackIfNeeded(auto = true)
                    return@launch
                }
                pageState.value = PageState.Ready
                bumpRevision()
                // 仅在已有多个同名源且当前源有多条线路时预检;单源/单线路直接起播,不等待。
                // 传 detailBuildToken 快照:探测期间换了片的话,快照与当前值就不相等了。
                applyFirstWatchProbe(info, recordKey, recordId, detailBuildToken, allowQualitySelection)
                requestPlay()
                if (playingList.isNullOrEmpty()) {
                    startFallbackIfNeeded(auto = true)
                } else {
                    // 缓存秒灌(2026-10-17):当前源能播 ⇒ 先把**上次搜到的备用源**灌进列表。
                    //
                    // 为什么挂在这里:[seedCandidatesFromCache] 此前只有 [startSourceSearch] 里
                    // 一个调用点,于是"详情能播、不搜"这条路上它永远不执行 —— 缓存明明在硬盘上,
                    // 却没人去读,用户看到的列表照样是空的。
                    //
                    // ⚠️ 2026-10-18 修正:它内部读 MMKV + 解析整个缓存桶,**必须走 IO**
                    // (该函数已改成挂起,内部 withContext(Dispatchers.IO))。
                    // 早先的注释说"放在 requestPlay() 之后就能让播放优先"是**错的** ——
                    // playSignal 由 Compose 在主线程消费,同一派发内 playCurrent() 只能等本协程块
                    // 结束,灌种照样挡在起播前面。现在真的不挡了。
                    //
                    // 零网络:只读本地缓存。失效由 [SearchResultCache] 的 24h TTL 兜底,
                    // 可疑源由 publishSourceChips 里的 sourceBoard 标记兜底。
                    // 文案/按钮不用改:SourceSearchStatus.line 的 `found > 0` 分支优先于
                    // searchedOnce,列表有源就报"已找到 N 个来源"、按钮写「搜源」。
                    seedCandidatesFromCache(searchTitle)
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
        // 空详情(非站点报错)= 这个源这一场没交付出内容,与超时/不可播同一条事实口径。
        markCurrentSourceGaveNothing("detail-empty")
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

    private fun startSourceSearch(userInitiated: Boolean = false) {
        val title = searchTitle.ifEmpty { vodName.trim() }
        if (title.isEmpty()) return
        if (sourcesSearching.value && searchTitle == title) return
        // ⚠️ 回存必须在**改写 searchTitle 之前**:命中表马上会被清空,而落盘的键取自
        // searchTitle —— 晚一步就写错键(把上一部的命中挂到这一部名下)。
        flushFallbackHitsSave()
        // 换片 ⇒ 扩池轮次复位(2026-10-13):"上一部扩到第 3 轮"不能带给新片(每部片都从 1/3 起搜)。
        // 判据用 searchTitle 是否变化:扩池是同片(两值相等)⇒ 不复位、且**不清已搜源**(增量续搜);
        // 换片 ⇒ 复位轮次 + 清"已搜源"。
        if (searchTitle != title) {
            poolRound = ExpandPoolPolicy.FIRST_WAVE
            manualWavesQueued = 0
            canContinueSourceSearch.value = false
            noHitShortCircuit = null
            sessionPlanTotal = 0
            searchedSourceKeys.clear()
            // 换片 ⇒ 超时计数归零(它是空态文案的判据,必须与"这一场的已问源"同步生命周期)
            timedOutSourceCount = 0
            // 换片:上一部的零命中名单在函数开头的 flushFallbackHitsSave() 里已按**旧片名**落盘,
            // 这里清空才不会把 A 片的"没有"记到 B 片头上
            pendingNoHitSources.clear()
        }
        searchTitle = title
        // 新一轮搜索 ⇒ 解冻(上一轮可能因为起播成功被冻结过;那条源后来放不出来时还得靠它)
        searchFrozen = false
        searchToken = SEARCH_SEQ.incrementAndGet()
        // 新一轮 ⇒ 失败证据去重表清空:同一源在新一轮里再次没答话是**新的一次证据**,该再扣一次
        // (与搜索页 failRecordedForToken 的"每轮一次"口径一致 —— 那边是按 token,这里按轮次天然等价)
        evidenceRecordedKeys.clear()
        sourcesWithHit.clear()
        val myToken = searchToken
        val tokenStr = "detail_$myToken"
        val checked = SearchHelper.getSourcesForSearch()
        // 勾选表是按"源集合"(键为 API_URL)记的,而聚合订阅会把 API_URL 换成另一个含几百项 site 的配置:
        // 旧表里的 key 在新站表里基本对不上,直接拿去过滤会把候选池缩到 0 —— 表现为换源永远无可选项。
        val effectiveChecked = if (checked == null || SearchHelper.isSelectionStale(checked)) null else checked
        val home = ApiConfig.get().getHomeSourceBean()
        val candidatePool = ApiConfig.get().getSourceBeanList()
            // 2026-10-14 用户定案"在意找得全":不再硬性排除**非快搜源**。
            // 旧口径 `&& it.isQuickSearch()` 让那类源(要现算播放地址的 spider/慢源)**永远不参与换源**,
            // 用户表现为"这部片明明有源,换源列表里就是没有"。
            // 排除它们当初只为保换源首轮速度 —— 这件事改由排序分层解决(见下面 quickSearchOf:
            // 首轮 1/3 仍然全是快搜源,慢源从第二轮起补齐),既找得全也不牺牲首轮。
            .filter { it.isSearchable() && (effectiveChecked == null || effectiveChecked.containsKey(it.key)) }
        // 被屏蔽的源不进自动换源候选池(防滥用封禁机制,P2)。
        //
        // 降权(2026-10-13):口径为**连续权重**(SourceWeightMemory,与搜索页同一台账)。
        //
        // ⚠️ 2026-10-13(动态扩池):候选池大小从"固定截断"改为
        // **按轮次比例动态扩池**([ExpandPoolPolicy],用户指定 1/3→1/2→2/3→全量);
        // 那个固定上界与「自动扩池」开关已于 2026-10-14 一并移除。
        // 顺序调整为 **先排序、后按本轮大小截断** —— 这是动态扩池的**正确语义**:
        // 取"权重最高的前 N 个"(热门/健康源优先),冷门/低权重源由后续轮次补齐。
        // 若反过来(先 take 后排序),则低权重源会被 N 直接砍掉、永不被搜(降权退化成屏蔽)。
        val weightKeys = candidatePool.map { it.key }
        val weightScores = SourceWeightMemory.scoresOf(weightKeys)
        val orderedPool = SearchBatchPolicy.orderByWeight(
            SourceHealthFilter.filter(candidatePool).sortedBy { it.key != home.key },
            homeKey = home.key,
            weightOf = { weightScores[it] ?: SourceWeightPolicy.NEUTRAL },
            hasWeightOf = { weightScores.containsKey(it) },
            // 快搜源整层排在前:首轮 1/3 与慢源无关 ⇒ 纳入慢源不拖慢第一次出候选
            quickSearchOf = { it.isQuickSearch() },
        )
        // 2026-10-14 用户定案:**「换源」点击后就要并行找到所有符合本片名的源**。
        // 旧口径下手动换源仍走 1/3→1/2→2/3 三道**波间屏障**(每波要 awaitAll 全部收口才投下一波),
        // 慢源各占额度 ⇒ 全量结果要 3~3.5 分钟才凑齐,用户以为"换源没反应"。
        // 手动 ⇒ 一次把搜索范围内的源全部投出(并发由自适应信号量限流,取消的只是批次屏障);
        // 自动兜底路径不变(它要的是"快出第一个候选",扩池阶梯继续保留)。
        //
        // 零命中短路(2026-10-15)。上一轮里"这个源**答过话**、也确实没给出这部片"是有的,
        // 而这条事实此前什么都没留下 —— 于是重进同一部片要把这几百个源各烧一次 8 秒超时额度
        // (真机《狮子王》两分钟:55 答话 / 51 空响应 / 28 超时,给出这部剧的源是个位数)。
        //
        // ⚠️ 两条边界(判据见 [NoHitSourcePolicy]):
        //   ① 只有"确实答过话"才入账,超时/异常/解析崩绝不进 —— 那是源的耐心问题,不是它有没有;
        //   ② 用户主动点「换源」或手动那一轮**一律穿透**,他的意图就是"还要继续找",
        //      用缓存少给几个源只会让他认为功能坏了。
        //
        // ⚠️ 第三条(2026-10-18 新增):**跳过比例过大时整轮放弃短路**。
        // 真机《潜伏6》:源池 317、已知零命中 259 ⇒ 只问 58 个(18%),而那是部热门片,
        // 理论上很多源都该有。短路把机会先掐掉,且掐得越多、名单越长、下轮剩余越少
        // —— "越搜越少"的自我强化。此时它否定的已不是"重复劳动",而是"这部片有没有"这个结论。
        val shortCircuitAllowed = shortCircuitThisSession(userInitiated)
        val rawKnownAbsent =
            if (shortCircuitAllowed) {
                NoHitSourceCache.knownAbsent(SearchSettings.normalizedTitle(title))
            } else {
                emptySet()
            }
        val bypassShortCircuit =
            NoHitSourcePolicy.shouldBypassShortCircuit(rawKnownAbsent.size, orderedPool.size)
        val knownAbsent = if (bypassShortCircuit) emptySet() else rawKnownAbsent
        if (bypassShortCircuit) {
            LOG.i(
                "echo-source-search known-absent-bypass n=" + rawKnownAbsent.size +
                    " total=" + orderedPool.size +
                    " ratio=" + (rawKnownAbsent.size * 100 / orderedPool.size.coerceAtLeast(1)) + "%" +
                    " title=" + title + " token=$myToken"
            )
        }
        val askablePool = if (knownAbsent.isEmpty()) orderedPool else orderedPool.filter { it.key !in knownAbsent }
        if (knownAbsent.isNotEmpty()) {
            LOG.i(
                "echo-source-search known-absent-skip n=" + knownAbsent.size +
                    " remain=" + askablePool.size + " total=" + orderedPool.size +
                    " title=" + title + " token=$myToken"
            )
        }
        // 搜索范围就是**过滤后的全部可用源**:原先那个"固定截断到 120 个"的退路已随「自动扩池」开关
        // 一起移除 —— 源集合 300+ 时截断会让范围外的源**永远不参与换源**,而它想省的时间
        // 现在已经由"并发按超时占比自适应退让"去省了。
        //
        // 分档的分母 = 本场定死的"排序后全池"(见 [sessionPlanTotal]);
        // 零命中短路只从**这一档实际投出的源**里扣,不挪档的边界。
        // ⚠️ 分母**不能**改成 `askablePool.size`(试过,已回退):那份通过名单会在第一档里边搜边长,
        // 按它重算会让后面几档的边界跟着漂,最终把第 2、3 档并成一档跑完
        // —— 用户要的"点两次推进两档"变成一次([sessionPlanTotal] 的 KDoc 有真机现场)。
        // 账目对不上(缺陷 F)要修的是**档宽与已投源的比较方式**,不是这个分母。
        if (sessionPlanTotal == 0) sessionPlanTotal = orderedPool.size
        val effectiveTotal = sessionPlanTotal
        // 每档问多少由分档计划定,**不再区分"手动就一次投满全池"**(2026-10-17):
        // 手动点一次 = 往前推一档,剩下的档留着让他继续点 —— 等待时长由他决定。
        sourcePoolTotal.value = effectiveTotal
        sourceSearchAsked.value = searchedSourceKeys.size
        val roundSize = roundPoolSize(effectiveTotal)
        val sources = askablePool.take(roundSize)
        // 这一档正在跑 ⇒ 页面上不该同时出现「继续找源」(点了也没意义,档内还在收口)
        canContinueSourceSearch.value = false
        // 诊断包靠这行判断"预热到底有没有触发、候选池多大" —— 真机日志被 ROM 屏蔽时,
        // 文件日志(仅 diag 包落盘)是唯一可观测点。前缀 echo-source- 已在 LOG 白名单里。
        // round/pool/total 三者同看,才能确认"动态扩池"真的在逐轮扩大。
        LOG.i(
            "echo-source-search start title=" + title + " pool=" + sources.size +
                " total=" + effectiveTotal + " round=" + poolRound +
                " weight-known=" + weightScores.size + " token=$myToken" +
                " manual=$manualSwitchActive"
        )
        // 措辞判据:从这一刻起,这一场"搜过"了 —— 搜完仍 0 个时那一行要说"暂时没找到其他源",
        // 不能再退回"点击搜索其他片源"(那条只属于还没搜过的时候)。放在空转防护之前置位:
        // 走到那个分支说明上一轮真的搜过,更该算"搜过"。
        sourceSearchStarted.value = true
        // 「换源」空转防护(2026-10-14):同一部片已把搜索范围内的源投过一遍 ⇒ 不再"假启动"一轮。
        // 旧实现会置 sourcesSearching=true + 进度 (0,total),波次循环发现没有新源可投立刻 break 收口,
        // 于是用户看到"进度条一闪就没了、列表一条没多" —— 分不清"已经搜完"还是"点坏了"。
        // 现在:直接给出终态提示(仅用户主动点换源时提示),并解除"不冻结"闩锁,让冻结保护回归。
        val unsearchedCount = askablePool.take(effectiveTotal).count { it.key !in searchedSourceKeys }
        if (unsearchedCount == 0) {
            LOG.i(
                "echo-source-search no-new-source total=" + effectiveTotal +
                    " searched=" + searchedSourceKeys.size + " manual=$userInitiated token=$myToken"
            )
            sourcesSearching.value = false
            manualSwitching.value = false
            manualSwitchActive = false
            // 可问的源已经问完了:即便按分档计划"还有下一档",那一档里也没东西可投,按钮不该再挂着
            canContinueSourceSearch.value = false
            searchProgress.value = null
            publishSourceChips()
            if (userInitiated) {
                // ⚠️ 数字必须是**真正问过的源数**,不是 [effectiveTotal](全池 300+)。
                // 走到这里说明"这一档可问的源都问过了",而那些因零命中被跳过的源
                // 根本没发过请求 —— 报全池数会让用户以为"300 多个源全搜完了"。
                toastEvent.value = str(R.string.search_all_sources_done, searchedSourceKeys.size)
            }
            settleDeferredEmpty()
            return
        }
        // ⚠️ 必须先取消旧搜索 job(2026-10-09 真机数据修正):分批后旧 job 要跑完全部批才自然结束,
        // 快速换片时 30 个会话里 27 个在 semaphore(12) 队列里陪跑 —— 它们的回包本就被
        // currentTokenStr() 守卫丢弃,留在队列里纯粹白占并发、拖慢新搜索。cancel 后排队中的
        // async 在挂起点被取消、许可即刻释放(在途网络请求不可中断,但结果会被丢弃,无害)。
        // 批间 abort-stale 检查保留:兜"旧 job 已进批内在途"的情况。
        searchJob?.cancel()
        sourcesSearching.value = sources.isNotEmpty()
        relatedVideos.value = emptyList()
        // 进度只带"已问到第几个 / 一共多少个"。**分母刻意不再进 UI**(措辞判据见 SourceSearchStatus):
        // 自动那轮只投 1/3 池、命中即 all-done,写 `108/323` 是个不打算兑现的承诺,用户读成"卡住了"。
        // 分子用**已投源数**而不是 0(2026-10-14):searchedSourceKeys 是同片跨轮累计的,
        // 写 0 会让第二轮起搜时进度掉回 0、下一次上报又跳到 109,看着像倒退。
        searchProgress.value = SearchProgress(
            searchedSourceKeys.size.coerceAtMost(effectiveTotal),
            effectiveTotal,
        )
        // 历史命中先灌一批候选(2026-10-13)。
        //
        // ⚠️ 2026-10-18 修正:灌种现在是**异步**的(内部走 IO,见 [seedCandidatesFromCache]),
        // 所以"必须在调用方取候选之前完成"这条旧约束**不再成立** —— 灌种完成时,
        // 调用方可能已经跑过 `loadNextFallbackCandidate()` 并因候选为空而收口了。
        //
        // 因此灌种完成时会自己补一次接管检查(见该函数末尾的 `shouldAutoTakeOver`)。
        // 这比"依赖调用顺序"更稳:无论灌种先完成还是后完成,候选进了表就一定会被消费,
        // 不会出现"缓存里有货、链却因为取候选那一刻表还空着而提前判死"。
        fallbackSearchHits.clear()
        seedCandidatesFromCache(title)
        if (sources.isEmpty()) return
        searchJob = viewModelScope.launch {
            val doneCount = java.util.concurrent.atomic.AtomicInteger()
            // ⚡ 2026-10-13 增量扩池重构:把"投一波源→等收口→判扩池→再投更多源"做成**单 job 内的波次循环**,
            // 而不是"扩池就重启 startSourceSearch"。
            //
            // ## 为什么必须增量(用户反馈)
            // 旧实现扩池时 `startSourceSearch()` 重启:换 token + 从头 `sources.mapIndexed` 全量重投
            // ⇒ 已搜过的 120 个源被**再搜一遍**,纯粹浪费(用户:"前面搜索过的就不用搜索了")。
            //
            // ## 增量怎么做
            // 波次循环里每波只投 [searchedSourceKeys] 里**没有的**源(take 全量视图的前 N 个、
            // 滤掉已投的)。扩池只是"把 N 加大",下一波自然只多出"新增区间"那批源 ——
            // **同 token、不 cancel、不重投**,进度累加。这是"扩池后继续搜"的正解。
            //
            // ## 并发:自适应,不再写死
            // 每波内 `async` 排队,慢源等满超时才释放额度;波间串行(上一波 awaitAll 完才判扩池)。
            // 并发度每波开始时用**当前值新建信号量**(信号量本身不能改容量,与搜索页换线程数时
            // 重建 Semaphore 同一做法),波结束后按上一波的超时占比上调/下调 —— 见 [detailConcurrency]。
            val concurrencyCap = SearchBatchPolicy.detailConcurrencyCap(
                KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT)
            )
            if (detailConcurrency <= 0) {
                detailConcurrency = concurrencyCap
            } else {
                detailConcurrency = detailConcurrency.coerceIn(
                    SearchBatchPolicy.DETAIL_CONCURRENCY_MIN, concurrencyCap
                )
            }
            // 变体补发的**整轮预算**(2026-10-14):冷门片时"每个源都零候选"成立 ⇒ 不设上限就是
            // 322 源 × 4 变体 ≈ 上千次请求,全量耗时直接翻几倍(真机《奈义日记》那次就是这种局面:
            // 322 个源全部答话、0 命中)。预算用完后其余源只问原词一次 ⇒ 变体收益集中在前几十个源,
            // 因为片名写法差异通常在权重最高的那批源里就能补回来。
            val variantAsks = java.util.concurrent.atomic.AtomicInteger()
            val poolView = askablePool.take(effectiveTotal)  // 本轮有效范围(按权重降序);每波只 take 前 N 个并滤掉已投
            // 查询变体(原词 + 去分隔符 + 去年份 + 去季/版本后缀 + 简繁互转),用于"该源零候选时补发一次"
            val searchVariants = SearchSettings.queryVariants(title)
            var waveSize = roundSize    // 当前波宽度(首轮 = 外层算好的 roundSize)
            val total = poolView.size
            while (true) {
                // ⚠️ 2026-10-14 进度口径修正(用户定稿:"逐条真实 + 走满 322"):
                // 旧实现用 `filter { searchedSourceKeys.add(it.key) }` **在波投前一次性把整波
                // (首轮 108 个)全标记为已投** ⇒ 波前那一次 publishSearchProgress 让分子瞬间 0→108,
                // 用户看到"一进来就 108/322、前面根本没搜"。
                // 现改为:**波前只做去重选取**(用局部判定,不写全局表),对
                // [searchedSourceKeys] 的 add 移到每个源**真正投出**(进入 withPermit)时逐条执行,
                // 并逐条 publishSearchProgress ⇒ 分子一条一条 +1(受信号量 12 限流分批),
                // 且分母仍是全量 total,最终**走满 322**。这同时满足"逐条真实"与"走满"。
                val wave = poolView.take(waveSize).filter { it.key !in searchedSourceKeys }
                val waveSizeBefore = doneCount.get()
                // 并发度**每波重新生效**:Semaphore 没有"改容量"的接口,所以按当前值新建
                // (旧波的许可随旧波 awaitAll 结束一起消失,不会有两套额度并存)。
                // 超时计数必须开在 if 外 —— 波后的自适应判据与日志都要读它。
                val waveSemaphore = Semaphore(detailConcurrency)
                val timeoutsThisWave = java.util.concurrent.atomic.AtomicInteger()
                // 记录本波投出前的候选数 —— 波后与它对减得到"本轮新命中"(扩池判据只用增量,不用累计)。
                val candidatesBeforeWave = synchronized(fallbackCandidates) { fallbackCandidates.size }
                if (wave.isNotEmpty()) {
                    coroutineScope {
                        wave.mapIndexed { index, bean ->
                            async {
                                if (!searchActive(tokenStr)) return@async
                                waveSemaphore.withPermit {
                                    if (!searchActive(tokenStr)) return@withPermit
                                    // 逐条入账 + 逐条上报:这是"已投源数"分子增长的真实时点。
                                    // 放在 withPermit 内(真正获得并发额度、即将发请求)而非波投前,
                                    // 使进度从 0 逐条爬升,不再"波投即满"。
                                    if (searchedSourceKeys.add(bean.key)) publishSearchProgress()
                                    // 优先级最高的前 N 个源用快探超时:它们是为"首屏候选"下注的那批,
                                    // 让额度更快回收 ⇒ 后续源更早被投出去。结果晚到仍会经事件通道计入(不丢证据)。
                                    val sourceTimeoutMs = SearchBatchPolicy.detailSourceTimeoutMs(
                                        index,
                                        SearchBatchPolicy.DETAIL_PROBE_SIZE,
                                        SearchBatchPolicy.DETAIL_PROBE_TIMEOUT_MS,
                                        SOURCE_SEARCH_TIMEOUT_MS,
                                    )
                                    // 一次提问 = 发请求 + 等这个源的回包事件(或本次超时)。
                                    // 抽成局部函数是为了让**查询变体补发**能复用同一套超时/清理/取消语义。
                                    val askOnce: suspend (String) -> Boolean = { word ->
                                        val done = CompletableDeferred<Unit>()
                                        pendingSearchDone.put(bean.key, done)?.complete(Unit)
                                        try {
                                            withTimeoutOrNull(sourceTimeoutMs) {
                                                withContext(Dispatchers.IO) {
                                                    searchCaller.getSearch(bean.key, word, tokenStr)
                                                }
                                                done.await()
                                            } != null
                                        } catch (ce: CancellationException) {
                                            // **协程取消不是"源没答话"**(2026-10-14 真机日志抓到误判):
                                            // 换片/换源/新一轮都会 cancel 上一轮 job,在途 async 抛的是
                                            // JobCancellationException —— 我方主动撤请求,与源质量无关。
                                            // 必须原样抛回:吞掉它会破坏结构化并发(job 撤不下来、额度不回收)。
                                            throw ce
                                        } catch (th: Throwable) {
                                            // 到这里才是"源自己出错"(Spider 抛异常 / HTTP 异常 / 解析崩)。
                                            // 单源异常绝不能掀掉整波:async 里未捕获的异常会取消整个
                                            // coroutineScope,一个坏 Spider 就能让剩下的几百个源一起收摊。
                                            LOG.i(
                                                "echo-source-search threw key-len=" + bean.key.length +
                                                    " err=" + th.javaClass.simpleName
                                            )
                                            if (searchActive(tokenStr)) {
                                                recordDetailSearchTimeout(
                                                    bean.key,
                                                    SourceWeightPolicy.EvidenceKind.SEARCH_FAILED,
                                                )
                                            }
                                            false
                                        } finally {
                                            // ⚠️ 2026-10-18:**必须按"值"删,不能按 key 删**。
                                            // 换片/换源会 cancel 旧 Job,而 cancellation 的 finally 与
                                            // 新 Job 的 `put` 之间没有先后保证 —— 旧 Job 的 finally 若晚一步,
                                            // `remove(bean.key)` 会把**新 Job 刚登记的那个 deferred 删掉**。
                                            // 后果:新轮这个源的回包到达时 `remove(...)?.complete()` 拿到 null,
                                            // `done.await()` 一直等不到完成 ⇒ 明明回了包却被判超时。
                                            // 按 (key, value) 删除时,只有"删的正是自己登记的那个"才生效。
                                            pendingSearchDone.remove(bean.key, done)
                                        }
                                    }
                                    val answered = askOnce(title)
                                    // **答了话却一个候选都没出 → 换查询变体再问一次**(去年份 / 去季与版本后缀 /
                                    // 简繁互转 / 紧凑形)。此前详情页只发原词一次,而站方写片名没有规范:
                                    // 「功夫女足(2026)」「夢華錄」这类源用原词问就是"没有",表现为明明有源搜不到。
                                    // 只在"这个源一个候选都没给"时补发 ⇒ 有命中的源不会被重复问,请求量不会翻倍。
                                    var variantIndex = 1
                                    if (answered) {
                                        while (variantIndex < searchVariants.size &&
                                            bean.key !in sourcesWithHit && searchActive(tokenStr)
                                        ) {
                                            if (variantAsks.incrementAndGet() >
                                                SearchBatchPolicy.DETAIL_VARIANT_REASK_BUDGET
                                            ) {
                                                break
                                            }
                                            // 补发失败就停:后面还有变体也不值得继续花预算。
                                            // ⚠️ 这个失败**只**结束循环,不写任何源证据 ——
                                            // 原词已答话的源不该因为"变体没回"被记超时(见下方说明)。
                                            if (!askOnce(searchVariants[variantIndex])) break
                                            variantIndex++
                                        }
                                    }
                                    // ⚠️ 2026-10-18:判据从"没答话**或**变体补发超时"收窄为"**没答话**"。
                                    //
                                    // 旧判据把"变体补发超时"也算成源超时,于是"原词答了话(+20 已在事件通道记上)、
                                    // 只是后来的查询变体补发超时"的源,会被再记一次 `SEARCH_TIMEOUT`(−25)。
                                    // 后果有两层:
                                    //   ① 权重台账自相矛盾 —— 同一个源在同一波里先 +20 后 −25,净 −5,
                                    //      而它实际上是好源(原词就问到了),排序被反向教育;
                                    //   ② 自适应并发被带偏 —— `timeoutsThisWave` 把变体超时也算进去,
                                    //      超时占比被系统性抬高,`conc=16->8` 那次腰斩就有这份虚数。
                                    //
                                    // 现在:只有"原词就没答话"(`!answered`)才算源超时。
                                    // 变体补发的失败只影响**这一次补发**(不会有副作用),不再改源证据。
                                    if (!answered) {
                                        // **源没答话也要留证据**(2026-10-14 审查修正):此前详情页只记"收到回包",
                                        // 超时/异常一行证据都不写,而扣分只在搜索页发生 ⇒ 从不手动搜索的用户,
                                        // 权重台账对换源链恒为空,慢源每轮都占着首轮名额。
                                        // ⚠️ 只写**权重台账**,不写 SourceHealthMemory.recordFail:那是封禁证据,
                                        // 必须守住"换源链不生产封禁证据"这条不变量(见 recordFallbackSourceAnswered)。
                                        // ⚠️ 也要过 searchActive:本轮已被冻结/作废时的超时不算源的错。
                                        if (searchActive(tokenStr)) {
                                            timeoutsThisWave.incrementAndGet()
                                            recordDetailSearchTimeout(
                                                bean.key,
                                                SourceWeightPolicy.EvidenceKind.SEARCH_TIMEOUT,
                                            )
                                        }
                                    }
                                    // 收口只记 doneCount(诊断用),**不再改进度分子** —— 分子已在投出时逐条入账
                                    if (searchActive(tokenStr)) doneCount.incrementAndGet()
                                }
                            }
                        }.awaitAll()
                    }
                }
                val hitCount = synchronized(fallbackCandidates) { fallbackCandidates.size }
                // 本轮(本波)**新搜到**的候选数。fallbackCandidates 是累计表(含 cache-seed 灌入的
                // 历史命中),拿它的绝对数当"本轮命中"会把日志读成"命中了所以没往下问"。
                // 2026-10-14 真机日志:click ... candidates=1 / wave-done ... hit=1 但 wave=0(本轮未投源)。
                // 2026-10-17 起扩档不再看命中(只看用户点没点),这个增量留给日志与收口判据用。
                val newHitsThisWave = hitCount - candidatesBeforeWave
                // 并发自适应:判据与弱网缩超时**共用**(样本 ≥8 且超时占比 ≥0.5)。
                // 上一波结算数不足样本时原样保持,别让一小波噪声改动并发。
                val settledThisWave = doneCount.get() - waveSizeBefore
                val concurrencyBefore = detailConcurrency
                detailConcurrency = SearchBatchPolicy.nextDetailConcurrency(
                    settledCount = settledThisWave,
                    timeoutCount = timeoutsThisWave.get(),
                    current = detailConcurrency,
                    cap = concurrencyCap,
                )
                LOG.i(
                    "echo-source-search wave-done done=${doneCount.get()}" +
                        " wave=" + settledThisWave +
                        " searched=" + searchedSourceKeys.size + " round=" + poolRound +
                        " frozen=$searchFrozen token=$myToken" +
                        " waveSize=$waveSize total=$total" +
                        " hit=$hitCount newHit=$newHitsThisWave" +
                        " conc=$concurrencyBefore->$detailConcurrency" +
                        " timeouts=" + timeoutsThisWave.get() + " concCap=$concurrencyCap" +
                        " variantAsks=" + variantAsks.get()
                )
                // 问过多少个:每档收口就入账。放在"让路 return"之前 —— 被冻结/换片打断的那一档
                // 同样真的问过这些源,那一行的数字不该退回 0。
                sourceSearchAsked.value = searchedSourceKeys.size
                // ⚠️ 「继续找源」的入口也必须在让路 return **之前**置位。
                // 旧实现只在下面正常收口分支里设置,于是"搜索被冻结打断"这条路上
                // 入口永远不出现 —— 用户明明还有 2/3 池没问,却没有任何地方能推进。
                // (这条路径长期被 manualSwitchActive 闩锁掩盖着,闩锁修好后就会暴露。)
                if (tokenStr == currentTokenStr()) {
                    canContinueSourceSearch.value = ExpandPoolPolicy.hasNextWave(total, poolRound)
                }
                // 让路:换片/冻结后立即停(别再投下一波、也别走收口)
                if (!searchActive(tokenStr)) return@launch
                // 扩档判定(2026-10-17 用户定案):**自动链只跑第 1 档**,跑完就停下给结论;
                // 要往下一档走必须是他点过「继续找源」(manualWavesQueued),一次点一档。
                // 命中的档不再往下问(目的已达成),但**手动点过就照点数的推进** —— 他要的就是"再问一批"。
                //
                // ⚠️ "还有下一档"是不可绕过的硬边界:若问尽后仍继续 expand ⇒ wave=0 空转、
                //   poolRound 无限自增、日志刷屏、CPU 空转(2026-10-14 v1.0.99 真机死循环现场:
                //   round 566→576… 每轮 1ms)。
                val hasMoreWaves = ExpandPoolPolicy.hasNextWave(total, poolRound)
                val canExpand = hasMoreWaves && manualWavesQueued > 0
                if (canExpand) {
                    manualWavesQueued -= 1
                    poolRound += 1
                    waveSize = ExpandPoolPolicy.poolSizeForWave(total, poolRound)
                    LOG.i(
                        "echo-source-search expand round=" + poolRound +
                            " nextSize=" + waveSize + " total=" + total +
                            " searched=" + searchedSourceKeys.size + " token=$myToken" +
                            " manual=true queued=$manualWavesQueued"
                    )
                    continue  // 继续下一波:只投"新增的"源(searchedSourceKeys 已滤掉已搜的)
                }
                // 停下来等用户:还有档没问 ⇒ 海报态那一行下面出「继续找源」
                sourceSearchAsked.value = searchedSourceKeys.size
                canContinueSourceSearch.value = hasMoreWaves && tokenStr == currentTokenStr()
                if (hasMoreWaves) {
                    // 停下来的原因要说实话:自动那档跑完 ≠ 手动那一档排队跑完
                    LOG.i(
                        "echo-source-search wave-stop round=" + poolRound +
                            " asked=" + searchedSourceKeys.size + " total=" + total +
                            " hit=" + hitCount + " token=$myToken" +
                            " reason=" + (if (manualSwitchActive) "manual-queue-empty" else "auto-runs-first-wave")
                    )
                }
                break
            }
            LOG.i(
                "echo-source-search all-done done=${doneCount.get()}" +
                    " total=${total} searched=${searchedSourceKeys.size} frozen=$searchFrozen token=$myToken"
            )
            // 「不冻结」闩锁到此解除。
            //
            // 历史:旧实现只在"把整池源都问尽"(searchedSourceKeys.size >= total)时解除,
            // 而 2026-10-17 定案"一次点击只推进一档"(一档 = 1/3 池) ⇒ **这个条件永远不成立**
            // ⇒ 用户点过一次「换源」后,这部片的起播冻结**永久失效**,搜索会一直跑到用户离开。
            //
            // 现在:一轮手动搜索走到收口(一档跑完 / 投尽全池)就算完成使命,立刻释放。
            // ⚠️ 刻意**不**按"起播后 N 秒"提前释放:用户点一次换源,期待的是这一档搜完,
            // 中途掐断只会让他拿到 1/3 档的结果 —— 那是更差的体验。
            if (manualSwitchActive) {
                manualSwitchActive = false
                // 短路判定按场缓存,而它的输入里有 manualSwitchActive(见 shortCircuitThisSession)。
                // 不同步清掉缓存的话,闩锁释放了、短路却仍然永久失效 —— 一个闩锁两处伤害的另一半。
                noHitShortCircuit = null
                LOG.i(
                    "echo-source-switch manual round done, re-arm freeze token=$myToken" +
                        " searched=" + searchedSourceKeys.size + " total=$total"
                )
            }
            // 源问尽了就没有"下一档"可言:按钮不该再挂着
            sourceSearchAsked.value = searchedSourceKeys.size
            if (searchedSourceKeys.size >= total) canContinueSourceSearch.value = false
            // 全池/在途波次收口再回存一次(过程中已按合并窗口增量回存,这里兜底)
            if (tokenStr == currentTokenStr() && fallbackSearchHits.isNotEmpty()) {
                saveFallbackHitsToCache()
            }
            // 冻结时状态已由 [freezeFallbackSearch] 收口,这里**不能**再走接管/空态收口:
            // 那两件事是"给还没起播的页面找内容"用的,而冻结的前提正是"画面已经出来了"。
            if (tokenStr == currentTokenStr() && !searchFrozen) {
                sourcesSearching.value = false
                manualSwitching.value = false
                searchProgress.value = null
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
     * 搜索进度上报。
     *
     * 分子 = searchedSourceKeys.size = 已投出去的源数(用户要的"已搜 xx / 共 N 个"),
     * 分母 = 全量源数 total。
     *
     * <p>2026-10-14 用户定稿:**"逐条真实 + 走满 322"**。分子口径仍是"已投源数",
     * 但入账时点从**波投前批量**改到**每个源真正投出时**(见波次循环内的 `searchedSourceKeys.add`),
     * 从而使进度从 0 逐条爬升(信号量 12 限流,分批 +1),既不"波投即满"、也不会像
     * "收口数 doneCount"那样停在 214(超时源永不收口)。
     *
     * <p>刻意不用"收口数"(doneCount):有些源会超时不出结果,收口数永远达不到 total,
     * 进度条会卡在"214/322"这种数字上不动(2026-10-14 真机现场:searched 已到 322、
     * 进度却停在 214 附近就 all-done)。用户口径明确是"已搜源数 / 总源数",故以投出去为准。
     */
    private fun publishSearchProgress() {
        val total = searchProgress.value?.total ?: 0
        if (total <= 0) return
        val searched = searchedSourceKeys.size
        // 只发"问到第几个 / 共多少个"。发现数不再随进度走:它得与下面那行源列表同口径,
        // 而列表在视图层还会滤掉正在播的源与隐藏的源 —— 以前在这里替它算一次、UI 再算一次,
        // 真机 v1.1.45 就分叉过(顶部"4 个"、下面 3 个)。现在 UI 自己问 totalCandidateCount()。
        searchProgress.value = SearchProgress(searched.coerceAtMost(total), total)
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
        // 与 [finishFallbackWithoutResult] 同一判据:多数源超时 ≠ 这部片不存在
        enterEmpty(
            str(
                if (SourceSearchStatus.emptyReason(searchedSourceKeys.size, timedOutSourceCount) ==
                    SourceSearchStatus.EmptyReason.TIMEOUT_DOMINANT
                ) {
                    R.string.player_play_timeout_dominant
                } else {
                    R.string.player_play_failed_all
                }
            )
        )
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onSearchResultEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_SEARCH_RESULT) {
            val data = event.obj as? AbsXml ?: return
            if (data.searchToken != currentTokenStr()) {
                // ⚠️ 2026-10-18:回包被判废 ⇒ 必须把这个源从**已投表**里撤销。
                //
                // 旧实现直接 return,而 `searchedSourceKeys` 是"发请求时"就写进去的
                // (见波次循环的 `searchedSourceKeys.add`)。于是这个源同时满足两件坏事:
                //   ① 它其实**没被回答过**(回包被我方丢弃),却记着"搜过了";
                //   ② 下一轮的 `wave` 过滤 `it.key !in searchedSourceKeys` 会永远跳过它
                //      ⇒ 用户再怎么点「继续找源」都问不到它。
                // 真机"问过 221 个、只有 203 个真的问出去"的那 18 个差值就是这个来源。
                // 把它移出表即可:下次投源时它是"没搜过"的,会被重新问到。
                val staleKey = data.sourceKey
                if (!staleKey.isNullOrEmpty() && searchedSourceKeys.remove(staleKey)) {
                    LOG.i("echo-source-search stale-drop key-len=" + staleKey.length)
                }
                return
            }
            pendingSearchDone.remove(data.sourceKey)?.complete(Unit)
            // **解析失败/空响应不算"源答过话"**(2026-10-14 审查修正,与搜索页同一处修复):
            // 旧路径给它也走 recordFallbackSourceAnswered → recordAnswered → 权重 +20 且清零连续超时,
            // 于是每次崩的源被越奖励越靠前。这里改记 SEARCH_FAILED(只写权重,仍不写封禁证据)。
            val videos = data.movie?.videoList.orEmpty()
            if (data.parseFailed && videos.isEmpty()) {
                recordDetailSearchTimeout(data.sourceKey, SourceWeightPolicy.EvidenceKind.SEARCH_FAILED)
                return
            }
            recordFallbackSourceAnswered(data)
            // 命中回存(2026-10-13):只收**同名**条目,供"同一部片再次进入"秒出候选。
            // 按源覆盖:同一源后到的结果更全(查询变体可能回包多次)。
            val hitSourceKey = data.sourceKey
            if (!hitSourceKey.isNullOrEmpty()) {
                val sameTitle = videos.filter {
                    !it.id.isNullOrEmpty() && CandidateMatchPolicy.isSameTitleScore(candidateRelevance(it))
                }
                if (sameTitle.isNotEmpty()) {
                    fallbackSearchHits[hitSourceKey] = sameTitle
                    // 合并写(见 hitsSaveJob):逐源到达就逐源落盘会让主线程反复序列化整张表;
                    // 但**不落盘**就等于"这次搜过的命中白搜"—— 上一版只在搜索全跑完时写一次,
                    // 用户提前退出/换片就把整轮命中丢了,"同一部片再次进入依旧慢"于是复发。
                    scheduleFallbackHitsSave()
                    // 这次给出了同名条目 ⇒ 上次那条"没有"必须作废,否则它会一直挡住这个源
                    pendingNoHitSources.remove(hitSourceKey)
                    NoHitSourceCache.forget(normalizedSearchTitle(), hitSourceKey)
                } else if (hitSourceKey != sourceKey) {
                    // 零命中(2026-10-15):答了话、一个同名条目都没给(空列表,或回的全是别的片名)。
                    // 判据用的是**同名判定**而不是"列表为空" —— 回了别人的片名同样算这部片它没有。
                    // 正在播的那条源不记:这部片显然就是从那儿播起来的,记它只会自相矛盾。
                    pendingNoHitSources.add(hitSourceKey)
                    scheduleFallbackHitsSave()
                }
            }
            // 分级(2026-10-14,与搜索页统一相关度判定):
            //   ≥3 分 = 认定同一部片 → 换源候选(可被自动换源链接管起播);
            //   1~2 分 = 只是名字像/仅别名相等/每个词都出现 → 进**相关推荐**,看得见但不自动起播;
            //   0/负分 = 丢弃。
            // 旧实现是二值 isSameTitle,且归一化比搜索页弱(不删括注、无 NFKC/假名折叠)
            // ⇒ "搜索页能找到、点换源却找不到"的漏搜从这里产生。
            //
            // ⚠️ 2026-10-15:**这里不再按"当前源/已切过的源"剔条目**。原先那两条件把
            // 起点源与刚切走的源整条丢出候选表,而"换源列表"只能由这张表渲染 ⇒
            // 用户看到的正是"这个源明明有这部片,换源列表里就是没有、也切不回去"。
            // 隐藏当前源是**视图层**的事([publishSourceChips] 早就在排除它),数据层留着它
            // 没有任何副作用:正在播时它不出现在那一行,切走之后它自然回来。
            // 自动换源链要跳过试过的源,它自己看 [usedSourceKeys]/[triedKeys],不靠这张表瘦身。
            val candidates = videos.filter {
                !it.id.isNullOrEmpty()
                    && CandidateMatchPolicy.verdictOf(candidateRelevance(it)) == CandidateMatchPolicy.Verdict.CANDIDATE
            }
            if (candidates.isNotEmpty()) {
                data.sourceKey?.let { sourcesWithHit.add(it) }
                // 追加走唯一出口(去重 + 定号);重复回包(变体补发会再来一次)不会再占新号
                val added = appendCandidates(candidates)
                if (added.isNotEmpty()) {
                    sortFallbackCandidatesByCompleteness()
                    maybeCorrectCompleteness()
                    publishSourceChips()
                    refreshCrossSourceHint()
                    if (shouldAutoTakeOver()) loadNextFallbackCandidate()
                }
            }
            val related = videos.filter {
                !it.id.isNullOrEmpty()
                    && CandidateMatchPolicy.verdictOf(candidateRelevance(it)) != CandidateMatchPolicy.Verdict.CANDIDATE
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

    /**
     * 换源链搜索结果:只向源健康台账报**正向证据**(2026-10-13)。
     *
     * <p>## 补的是什么空白
     * 换源链是**第二套聚合搜索**(与搜索页各跑一套,见 [startSourceSearch]),但此前它
     * 一条结果都不写台账 —— 于是:搜索页刚给某个源记下一次超时(连续超时计数 = 1),
     * 换源链随后明明拿到了它的应答,那个计数却**不会归零**;下一轮搜索再超时一次就凑满
     * "连续 2 次",把它封 30 分钟 —— 而它中间刚答过话。这是纯粹的误封来源。
     *
     * <p>## 为什么只报正向,不报超时/失败(这条线不能破)
     * 源级封禁的**规则 1** 门槛是"24 小时内失败 ≥3 次 **且** 涉及 ≥2 部不同影片",
     * 而换源链**每点开一张卡片就可能触发一轮**(候选池上限 120 个源)。若它也开始写失败证据,
     * 同一个源在几部片之间的失败会迅速凑满规则 1 ⇒ 6 小时封禁;而**雪崩保险丝只回滚规则 2**,
     * 管不到规则 1(见 [SourceHealthMemory] 的说明)。所以这里守住一条线:
     * **换源链不生产封禁证据** —— 它只减少误封(答话即清零 + 登记收录),
     * 绝不增加误封。这与 [recordSourcePlayFailure]"只观测不记账"是同一取向。
     *
     * <p>口径与搜索页 [SearchViewModel.recordSourceOutcome] 完全对齐:拿回一条 `AbsXml`
     * (哪怕是无命中)就算"源答话了"。耗时**刻意传 -1**:本链没有逐源计时,不编一个数出来
     * 污染"慢源"采样(搜索页有真值,慢源降权仍由它负责)。
     */
    private fun recordFallbackSourceAnswered(data: AbsXml) {
        val key = data.sourceKey ?: return
        if (key.isEmpty()) return
        val hitTitles = data.movie?.videoList.orEmpty()
            .mapNotNull { it.name?.takeIf { name -> name.isNotEmpty() } }
        if (hitTitles.isNotEmpty()) {
            SourceHealthMemory.recordCarriers(key, hitTitles)
        }
        SourceHealthMemory.recordAnswered(key, elapsedMs = -1L)
        // "这个源本轮被搜过"是**调度事实**(供权重台账的保底配额轮换用),与质量证据分开记;
        // 旧实现只有搜索页登记,换源链搜过的源在那张轮换表里始终缺席 ⇒ 两边轮换口径不一致。
        SourceWeightMemory.markSearched(key)
    }

    private fun currentTokenStr(): String = "detail_$searchToken"

    /**
     * 换源链的**降权证据**(2026-10-14):源没答话(超时/异常)时扣它的连续权重。
     *
     * <p>## 为什么必须补
     * 权重台账 [SourceWeightMemory] 过去只有搜索页会写(见 SearchViewModel.recordSourceOutcome)。
     * 换源链是**第二套聚合搜索**,它一条扣分证据都不产生 ⇒ 从不打开搜索页的用户,
     * 「有权重按权重降序」这条排序对换源永远不生效,超时率 75~83% 的慢源每轮都排在前面白占并发额度。
     *
     * <p>## 为什么只写权重、不写封禁台账(不变量)
     * 见 [recordFallbackSourceAnswered] 的"换源链不生产封禁证据"。这里刻意不调
     * `SourceHealthMemory.recordFail`:那条会累计连续超时并可能触发规则 1(6 小时封禁),
     * 而换源链每开一张卡片就跑一整轮,凑门槛太快。权重是**只影响排序**的连续值,
     * 30 分钟半衰期自动回归中性(见 SourceWeightPolicy),答话一次即由 recordAnswered 路径补正。
     *
     * <p>同 token 内每源只记一次(见 [evidenceRecordedKeys]):重试轮/迟到回包不该把
     * "一次没答话"扣成好几次。
     */
    private fun recordDetailSearchTimeout(sourceKey: String?, kind: SourceWeightPolicy.EvidenceKind) {
        val key = sourceKey?.takeIf { it.isNotBlank() } ?: return
        if (!evidenceRecordedKeys.add(key)) return
        SourceWeightMemory.record(key, kind)
        SourceWeightMemory.markSearched(key)
        // 会话级计数:只用来决定**空态该说什么话**(见 [SourceSearchStatus.emptyReason])。
        // 不加锁也不清(换片时随 searchedSourceKeys 一起复位)。
        if (kind == SourceWeightPolicy.EvidenceKind.SEARCH_TIMEOUT) timedOutSourceCount++
    }

    /**
     * 这一轮聚合搜索现在是否仍该**投源/回写进度**。
     *
     * <p>两道判据缺一不可:代次未变(换片/换源/重试后旧任务一律作废)且未被冻结
     * (起播成功 ⇒ 不再投新源,见 [freezeFallbackSearch])。
     */
    private fun searchActive(tokenStr: String): Boolean =
        tokenStr == currentTokenStr() && !searchFrozen

    /**
     * 起播成功 ⇒ **冻结**后台聚合搜索(2026-10-13,P1)。
     *
     * <p>## 为什么
     * 画面已经出来了,就没必要把剩下的源搜完:候选只在"这条源放不出来"时才有用,
     * 而那种情况会重新发起一轮新搜索([ensureSourceSearchRunning] 只看 [sourcesSearching],
     * 所以这里必须把它置回 false,否则冻结之后再也搜不动)。
     * 冻结把 12 个并发搜索请求迅速收干到 0,带宽让给正在缓冲/刚起播的播放流。
     *
     * <p>## 兑现口径(刻意只做一半)
     * **停投新源 + 在途照跑**:已经发出去的请求不撤 —— 它们的结果仍经 [onSearchResultEvent]
     * 计入候选与台账(冻结的是"还要不要再开新请求",不是"已发出的证据要不要")。
     * 排队等额度的源在 [searchActive] 处直接退出,所以并发额度会立刻陆续释放。
     */
    private fun freezeFallbackSearch() {
        // 2026-10-14:用户**主动换源**期间不冻结 —— 换源就是要搜全量找别的源,
        // 一起播就冻结会让候选没积累完就停(用户"看不到源列表"的真机问题根因之一)。
        if (manualSwitchActive) {
            LOG.i("echo-source-switch skip-freeze (manual switch active)")
            return
        }
        // 判据抽成纯函数:它很容易被改成"无条件冻结",而那会伤到"起播失败要靠搜索救命"的场景
        if (!SearchBatchPolicy.shouldFreezeOnPlaybackStarted(true, sourcesSearching.value)) return
        if (searchFrozen) return
        searchFrozen = true
        LOG.i("echo-source-search freeze token=$searchToken title=$searchTitle")
        // 状态即时收口:进度条随之下线(在途源的回写已被 [searchActive] 挡住,不会再让它冒出来)
        sourcesSearching.value = false
        manualSwitching.value = false
        searchProgress.value = null
        // 冻结即落盘:用户随时可能换片/退出,没落盘的命中等于没搜过
        flushFallbackHitsSave()
    }

    /**
     * 排队一次命中回存(合并窗口内的多次命中,见 [hitsSaveJob])。
     *
     * <p>只做合并、不做合并的兜底 —— 兜底由各内容边界上的 [flushFallbackHitsSave] 负责。
     */
    private fun scheduleFallbackHitsSave() {
        if (hitsSaveJob?.isActive == true) return
        hitsSaveJob = viewModelScope.launch {
            delay(HIT_CACHE_SAVE_MERGE_MS)
            saveFallbackHitsToCache()
        }
    }

    /**
     * 立即回存(并取消排队中的合并写)。
     *
     * <p>调用点都是**内容边界**:搜索开销、换片、起播冻结、退出页面。这些时点是"再也不用
     * 这一轮的命中表了"的最后机会,漏一个就会丢一整轮命中。
     */
    private fun flushFallbackHitsSave() {
        hitsSaveJob?.cancel()
        hitsSaveJob = null
        saveFallbackHitsToCache()
    }

    /**
     * 用 [SearchResultCache] 的历史命中**先灌一批候选**(2026-10-13)。
     *
     * <p>## 解决什么
     * 详情页的换源候选**只能**来自聚合搜索。索引型源(如 js_douban)没有详情接口,
     * 必须等聚合搜索给出第一个候选才能起播,而真机源超时率 75~83%(12 并发下,
     * 第一个候选实测要 9.8~10.8s)。于是"同一部片再次进入"要重新付出同样代价 ——
     * 而上次明明已经搜出过它由哪些源收录。
     *
     * <p>命中缓存时候选**立刻**可用,[startFallbackIfNeeded] 紧接着的
     * [loadNextFallbackCandidate] 就能直接加载对端详情(1~2s),把 12~15s 压到 2~3s。
     * 后台真实搜索照常执行(用于纠正/扩展),所以缓存陈旧**不会**导致"搜不到"。
     *
     * <p>过滤口径与 [onSearchResultEvent] 完全一致(同名 / 未用过 / 非当前源 / 未登记过),
     * 否则会灌进重复候选、或把已经试过的源又排在前面。
     */
    /**
     * 候选与本片名的**相关度**(与搜索页同一份分档 + 查询意图修正)。
     *
     * <p>2026-10-14:此前换源链自己用 [TitleMatcher.isSameTitle] 二值判同名,而搜索页早就有
     * [SearchSettings.relevanceScore](5 全等 / 4 前缀 / 3 包含 / 2 每词都现或剥长音符 / 1 仅别名 / 0 不匹配)
     * 加上别名、年份/季数意图修正。两套并存的直接后果就是**换源比搜索页漏得多**
     * (那份归一化不删括注、无 NFKC/假名折叠,标题写成"XX(2024)""《XX》第2季"就不认)。
     */
    private fun candidateRelevance(video: Movie.Video, title: String = searchTitle): Int {
        val intent = SearchSettings.parseIntent(title)
        return SearchSettings.relevanceScore(video.name, video.alias, title, video.note) +
            SearchSettings.intentModifier(intent, video.name, video.year)
    }

    /**
     * 从命中缓存灌一批候选(见调用点)。
     *
     * <p>⚠️ **自己起协程,调用方不必是挂起上下文**(2026-10-18,缺陷 M)。
     * 它要读 MMKV 并把**整个缓存桶**解析出来 —— `SearchResultCache.load` 会把桶里所有词条的
     * JSON 都读一遍(只有命中词条才做 Gson),理论最坏约 9600 次 `fromJson`。
     * 跑在主线程上会直接卡住首帧渲染,而它恰好在"详情就绪、刚起播"这个最敏感的时刻被调用。
     *
     * <p>早先的注释说"放在 requestPlay() 之后就能让播放优先"是**错的** ——
     * `playSignal` 由 Compose 在主线程消费,同一派发内 `playCurrent()` 必须等当前协程块结束,
     * 灌种照样挡在起播前面。现在整段挪到 IO,主线程只做最后的候选表写入与 UI 刷新。
     *
     * <p>**线程安全**:`appendCandidates` 内部对候选表加锁;`publishSourceChips` /
     * `sortFallbackCandidatesByCompleteness` 改 StateFlow,所以必须回到主线程执行 —— 用
     * `withContext(Dispatchers.Main)` 明确切回,不依赖调用方所在线程。
     */
    private fun seedCandidatesFromCache(title: String) {
        val normalized = SearchSettings.normalizedTitle(title)
        if (normalized.isEmpty()) return
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                val cached = SearchResultCache.load(normalized)
                if (cached.isEmpty()) return@withContext emptyList<Movie.Video>()
                val out = ArrayList<Movie.Video>()
                for ((_, videos) in cached) {
                    for (v in videos) {
                        if (v.id.isNullOrEmpty()) continue
                        if (!CandidateMatchPolicy.isSameTitleScore(candidateRelevance(v, title))) continue
                        // 2026-10-15:这里**不再**按"当前源/已用过的源"筛掉条目(与 onSearchResultEvent 同口径)。
                        // 灌种的是"上次这部片由哪些源收录"这个事实,把它丢掉就等于把可切回的源抹掉;
                        // 当前源不该出现在那一行,由 [CandidatePager.pageableSources] 在视图层排除。
                        out.add(v)
                    }
                }
                out
            }
            if (fresh.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                val seeded = appendCandidates(fresh)
                if (seeded.isEmpty()) return@withContext
                sortFallbackCandidatesByCompleteness()
                publishSourceChips()
                LOG.i("echo-source-search cache-seed title=" + title + " n=" + seeded.size)
                // 灌种是异步的 ⇒ 可能晚于调用方的取候选时机。若那时链已因"表里没货"提前收口,
                // 这批候选就白灌了。所以在这里补一次接管检查:只在"确实该接管且还没接管"时生效
                // ([shouldAutoTakeOver] 的判据是 fallbackAutoSwitch || currentSourceConfirmedEmpty,
                // 正常能播的片两者皆假 ⇒ 不会误触发换源)。
                if (shouldAutoTakeOver()) {
                    LOG.i("echo-source-search cache-seed take-over n=" + seeded.size)
                    loadNextFallbackCandidate()
                }
            }
        }
    }

    /**
     * 把本轮换源搜索的命中回存,供下次进入秒出候选。
     *
     * <p>**必须回存**:[SearchResultCache] 此前只有搜索页([SearchViewModel])会写,
     * 而首页卡片(索引型源)进来的片名往往从未在搜索页搜过 ⇒ 缓存恒为空、修复形同虚设。
     *
     * <p>调用时机有两类:**过程增量**([scheduleFallbackHitsSave],合并 2s 一次)与
     * **边界兑现**([flushFallbackHitsSave]:搜索开头/换片/起播冻结/退出页面)。后者是硬要求 ——
     * 只在"整轮搜索收口"时写一次的话,用户提前退出或换片就把这一轮命中整批丢掉,
     * 于是"同一部片再次进入依旧慢"会复发(这正是 2026-10-10 之前的形态)。
     */
    private fun saveFallbackHitsToCache() {
        val normalized = SearchSettings.normalizedTitle(searchTitle)
        if (normalized.isEmpty()) return
        // 零命中名单与命中表同一个合并窗口、同一批边界(开头/换片/冻结/收口)一起兑现
        if (pendingNoHitSources.isNotEmpty()) {
            NoHitSourceCache.record(normalized, pendingNoHitSources.toList())
            pendingNoHitSources.clear()
        }
        if (fallbackSearchHits.isEmpty()) return
        SearchResultCache.save(normalized, HashMap(fallbackSearchHits))
    }

    /** 缓存/台账用的片名键(与命中缓存同一份归一化,两边才能同进同退) */
    private fun normalizedSearchTitle(): String = SearchSettings.normalizedTitle(searchTitle)

    /** 当前源 + 已返回的同名影片候选源;不等待仍在进行的搜索,未知候选不增加起播延迟。 */
    /**
     * 此刻"已知收录这部片"的源数(候选表里的不同 sourceKey + 当前源自己)。
     *
     * <p>用于 [LineQualitySelector.shouldPreflightQuality] 判断"要不要为画质多等 1 秒" ——
     * 只有多个源都有这部片时才值得预检(单源/单线路直接起播)。
     *
     * <p>⚠️ **它读的是"当刻"的候选表,而灌种(见 [seedCandidatesFromCache])是异步的**
     * (2026-10-18 起走 IO)。所以本函数的结果**可能还没包含这次灌种的源**。
     * 这是刻意保留的:灌种源是**历史缓存**,而预检问的是"这次实打实发现了几个源";
     * 用历史数据去抬高这个计数,结果就是"冷启动时明明只有一个真源,却因为缓存里有 3 条,
     * 白白多等一轮画质探测"——与本功能"播放优先"的初衷相反。
     */
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
        // 2026-10-14(详情页「换源」分页设计):候选池**不再永久排除"切过的源"**。
        // 旧实现 `.filter { !usedSourceKeys.contains(...) }` 会让用户切过的源从列表消失 ⇒
        // 切几轮之后列表越来越空、"想切回之前那个好源"都找不到(用户真机反馈)。
        // 现在:只排除**当前源**(它没有"换到自己"的意义),切过的源**保留**(可切回),只是不特殊标注。
        //
        // 2026-10-14 反馈 #3 回退:曾尝试"当前源置顶+标当前源",但**前提是错的** ——
        // 候选列表里的源全是**其他源**(全站聚合搜出来的),与起播的当前源无关;
        // 把"源1"标成"当前源"会让用户误以为源1=正在播放的源。故回退为纯匿名序号。
        // 2026-10-16 口径纠正:隐藏判据是**纯计数** —— 这一场该源失败满两次就隐藏
        //   (见 SourceBoardLedger.failsToHide)。以前这里写的"两条**不同来源**都失败过"是
        //   把我举的例子当成了合取条件,那个条件真机上几乎永远凑不齐(同一个起点源重播三次
        //   全落在同一侧),结果就是"说了两次就隐藏,却一直没隐藏"。
        //   撤销侧另有一条更严的凭据:画面真的上过屏(见 [revokeBoardMarkWithPicture])。
        //   隐藏只发生在视图层 —— 候选表本身一条都不删,撤销状态后原样回来、号也还是原来那个号。
        val all = CandidatePager.pageableSources(
            synchronized(fallbackCandidates) { fallbackCandidates.toList() },
            currentSourceKey = sourceKey,
            hiddenSourceKeys = sourceBoard.hiddenKeys(),
        ) { it.sourceKey }
        // 源评分(2026-10-14 出路 A):有历史数据的源显示评分,无数据的显式标"暂无评分"。
        // ⚠️ score() 对无数据也返回中性 60,所以这里必须**另行判断"是否真无数据"**并置 null ——
        // 直接把 60 显示成"60分"是误导(用户会以为"这源就 60 分",实际是"没有证据")。
        val snap = CompletenessMemory.snapshot(completenessTitleKey)
        val confirmed = EpisodeSetMath.confirmed(snap.values)
        val keys = all.mapNotNull { it.sourceKey?.takeIf { k -> k.isNotBlank() } }.toSet()
        val weightScores = SourceWeightMemory.scoresOf(keys)
        val penalized = SourceHealthFilter.penalizedKeys()
        val blocked = SourceHealthFilter.blockedKeys()
        // 全量算分 → 不排序(自然顺序,用户终稿) → 按当前页切片
        val full = all.map { video ->
            val key = video.sourceKey.orEmpty()
            val weightScore = weightScores[key] ?: -1
            val set = snap[key]
            val prior = SourceCompletenessPolicy.priorCountFromNote(video.note)
            val completenessRank = if (set != null && confirmed.isNotEmpty()) {
                SourceCompletenessPolicy.rankScore(SourceCompletenessPolicy.tier(set, confirmed), set.size)
            } else {
                SourceCompletenessPolicy.rankScore(SourceCompletenessPolicy.tier(prior, confirmed.size), prior)
            }
            // "真无数据" = 权重无记录 且 完整度无记录(完整度 prior 也拿不到) ⇒ 显式暂无评分
            val hasAnyData = weightScore >= 0 || set != null || prior > 0
            val score = if (hasAnyData) {
                SourceQualityScore.score(
                    SourceQualityScore.Signals(
                        weightScore = weightScore,
                        completenessRank = completenessRank,
                        penalized = penalized.contains(key),
                        blocked = blocked.contains(key),
                    ),
                )
            } else {
                null
            }
            SourceChip(key, score, suspect = sourceBoard.markOf(key) == SourceBoardLedger.Mark.SUSPECT)
        }
        // 页号钳制:列表可能因换片/翻页后变短,越界会显示空白(用户以为"没有源")
        chipPage.value = CandidatePager.clampPage(chipPage.value, full.size)
        sourceChips.value = CandidatePager.pageSlice(full, chipPage.value)
    }

    /**
     * 用户点击「换源」按钮(2026-10-14 详情页「换源」分页设计的入口)。
     *
     * <p>## 语义
     * 用户对当前源不满意,主动要搜"其他源"。与"进详情页自动搜索"的区别是**用户明确意图**:
     * 因此**解冻搜索**(起播成功后搜索会被冻结,若不解冻则搜索跑不动 → 用户看不到源列表),
     * 并**标记 [manualSwitchActive]** 让后续"起播成功"**不再重新冻结**(否则一起播就停,又功亏一篑)。
     *
     * <p>复用现有搜索 job([startSourceSearch]):同片 ⇒ `searchTitle` 不变 ⇒
     * [searchedSourceKeys] 不清空 ⇒ **天然增量续搜**(已搜过的源不重搜),无需新建搜索通道。
     *
     * <p>搜索**跑全量**(直到所有源搜完或用户退出),不因"够 5 个"或"切了某源"而停 ——
     * 用户明确说过"用户切了源不代表找到满意的,应把剩下的搜完"。
     */
    fun onSwitchSourceClick() {
        // 防重入(2026-10-14 用户反馈 #2):用户主动换源后该轮搜索仍在跑时,按钮置灰,
        // 这里是**第二道防线** —— 万一有别的入口绕过 disabled,也拦下。
        if (manualSwitching.value) {
            LOG.i("echo-source-switch skip-duplicate manualSwitching=true")
            return
        }
        manualSwitching.value = true
        manualSwitchActive = true
        // 一次点击 = 往前推一档(2026-10-17 用户定案:剩下的档由他一档一档点着走):
        //  - 搜索还在跑 ⇒ 排队,本档收口时接上(见 wave-done 的 manualWavesQueued);
        //  - 已经停下来(海报态点「继续找源」)⇒ 当场进下一档,否则重投的还是同一批已问过的源。
        if (!sourcesSearching.value && ExpandPoolPolicy.hasNextWave(sourcePoolTotal.value, poolRound)) {
            poolRound += 1
        } else {
            manualWavesQueued += 1
        }
        chipPage.value = 0
        // 解冻:否则 startSourceSearch 内 searchActive 恒 false,搜索跑不动
        searchFrozen = false
        // 先按当前已有候选刷一次(可能有 cache-seed 的历史命中),再启动/续搜
        publishSourceChips()
        LOG.i(
            "echo-source-switch click title=$searchTitle searching=${sourcesSearching.value}" +
                " searched=${searchedSourceKeys.size} round=$poolRound" +
                " candidates=${synchronized(fallbackCandidates) { fallbackCandidates.size }}" +
                " conc=$detailConcurrency"
        )
        startSourceSearch(userInitiated = true)
    }

    /**
     * 「再搜一次」:全池都问完了还是 0 命中,用户要的是**真的再问一遍**,
     * 不是拿"已问过"的名单原地空转(那会直接走 no-new-source 出口,等于按钮没反应)。
     */
    fun restartSourceSearch() {
        poolRound = ExpandPoolPolicy.FIRST_WAVE
        manualWavesQueued = 0
        canContinueSourceSearch.value = false
        noHitShortCircuit = null
        sessionPlanTotal = 0
        searchedSourceKeys.clear()
        timedOutSourceCount = 0
        manualSwitching.value = true
        manualSwitchActive = true
        LOG.i("echo-source-switch restart title=$searchTitle total=${sourcePoolTotal.value}")
        startSourceSearch(userInitiated = true)
    }

    /** 下一页(仅在有下一页时生效;钳制由 [CandidatePager.hasNext] 保证)。 */
    fun nextChipPage() {        if (!CandidatePager.hasNext(chipSourceTotal(), chipPage.value)) return
        chipPage.value += 1
        publishSourceChips()
        LOG.i("echo-source-switch next page=${chipPage.value}")
    }

    /** 上一页(仅在有上一页时生效)。 */
    fun prevChipPage() {
        if (!CandidatePager.hasPrev(chipPage.value)) return
        chipPage.value -= 1
        publishSourceChips()
        LOG.i("echo-source-switch prev page=${chipPage.value}")
    }

    /** 候选源总数（全量，非当前页）：供界面判断"是否有下一页"。
     *
     * <p>必须与 chip 那一行用的同一份去重口径（见 CandidatePager.pageableSources）—— 真机出现过
     * 「只有 3 个源却显示下一页、点了不动」，就是因为界面按条目数、列表按去重后的源数：
     * 同一个源返回多张卡片（国语/粤语）时，25 条可能只对应 3 个源。
     */
    fun totalCandidateCount(): Int = chipSourceTotal()

    /** 分页唯一真值：参与分页的源数（排除当前源 + 按源去重）。
     *  UI 的 hasNext、点「下一页」的判据、publishSourceChips 的切片取数都只能走这一个。 */
    private fun chipSourceTotal(): Int = CandidatePager.pageableSources(
        synchronized(fallbackCandidates) { fallbackCandidates.toList() },
        currentSourceKey = sourceKey,
        hiddenSourceKeys = sourceBoard.hiddenKeys(),
    ) { it.sourceKey }.size

    /**
     * 候选源的**匿名**展示名。只依赖这个源的匿名号,绝不碰 `ApiConfig.getSource(key).name` ——
     * 那个名字就是要藏起来的身份信息。返回空串表示"这一行什么都不用显示"(调用方跳过该 chip)。
     *
     * <p>2026-10-15：号从**渲染位置**改成**绑源身份**（见 [SourceBoardLedger]）。
     * 位置会随每次回包重排而漂，而"切回刚才那个源"这件事全靠这个数字认人；
     * 代价是列表里会出现跳号/号与顺序不一致，那是可接受的 —— 找不到源才是致命的。
     */
    fun sourceLabelFor(sourceKey: String?): String =
        SourceIdentityMask.anonymousLabel(
            sourceBoard.ordinalOf(sourceKey) - 1,
            str(R.string.common_source_anonymous_prefix),
        )

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
        // 「画质选项」三档分流(口径见 DeviceCapability.QualityMode)。
        // 2026-10-14 定案:**只有画质优先愿意为画质等首帧**。自动档不再在这里花 1s 探测 ——
        // 调用方已经用实测记忆零成本选过线([LineQualitySelector.pickFromMemory]),
        // 无记忆时的探测收益(选到更高档)完全可以由起播后的 [sweepSourceLinesForQuality] 补,
        // 而它不占用用户正在等的那一秒。实测记忆回写照常积累,不受档位影响。
        val mode = DeviceCapability.effectiveMode()
        if (!mode.probeBlocksStart) {
            LOG.i("echo-quality pre-start skip mode=" + mode.name + " (起播不等网络,画质后置补救)")
            return
        }
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
                // 同上一条路径:预算要告诉探测本身,否则重试会撑破这个 withTimeout(见那里)
                probe = { url -> probe.probe(url, headers, mode.probeBudgetMs) },
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
        // 实测最优 ≠ 能用(2026-10-13):一条刚连续失败两次的线路,即便探测出的清晰度最高,
        // 切过去大概率还是失败,用户白等一次起播超时。而**当前线路**是上一步
        // bestFlagByLabelAndCompleteness 已经按线路健康选过的,所以这里只需回答
        // "要不要为了画质去换一条已知有问题的线" —— 答案是不要。
        // 只在"目标可疑且当前不可疑"时拦下:当前也 anyway 可疑时,换一条仍然可能更好。
        if (LineHealthMemory.isSuspect(siteKey, vodId, chosen) &&
            !LineHealthMemory.isSuspect(siteKey, vodId, currentFlag)
        ) {
            LOG.i("echo-quality skip-suspect chosen=" + chosen + " keep=" + (currentFlag ?: "null"))
            return
        }
        // 等待期间可能换了片/进了换源链:那时再写 vodInfo 会把新内容顶掉
        if (token != detailBuildToken || sourceKey != siteKey || info.playFlag != currentFlag) return
        info.playFlag = chosen
        val list = seriesMap[chosen]
        if (!list.isNullOrEmpty()) info.playIndex = index.coerceIn(0, list.size - 1)
        for (flag in info.seriesFlags) flag.selected = flag.name == chosen
        vodInfo = info
        bumpRevision()
    }

    /** P-完整性:某线路相对可信参考集的缺失集号(升序);选集面板"缺集"标注用 */
    fun lineMissingEpisodes(flagName: String?): List<Int> {
        val list = vodInfo?.seriesMap?.get(flagName) ?: return emptyList()
        val own = HashSet<Int>()
        list.forEach { e ->
            val n = EpisodeNormalizer.normalize(e.name)
            if (n.type == EpisodeType.REGULAR && n.episode != null) own.add(n.episode)
        }
        // 走 missingForLabel 而不是 missing:电影线路名(「极速蓝光-b」)提不出集号时 own 是空集,
        // 直接求差会得到"缺1集"—— 空集是**不可数**,不是"一集都没有"(真机 v1.1.50 的误标)。
        return EpisodeSetMath.missingForLabel(own, CompletenessMemory.confirmedSet(completenessTitleKey))
    }

    /**
     * 该线路的**健康后缀标签**(2026-10-14 追加式):"稳定"/"起播慢"/"不稳定"之一,或空串。
     *
     * <p>数据来自既有线路健康台账([LineHealthMemory],按"源+片+线路"绑定,跨会话落盘) ——
     * **只读、不改任何探测逻辑**。追加在实测画质之后([lineLabel] 里 `·` 拼接),
     * 与既有"缺集"后缀同一模式。没数据返回空(宁缺勿假)。
     */
    fun lineHealthSuffix(flagName: String?): String {
        val info = vodInfo ?: return ""
        val state = LineHealthMemory.lookup(info.sourceKey, info.id, flagName)
        return LineLabelPolicy.healthSuffix(state.failStreak, state.resolveMs, state.playedAt)
    }

    /** 该线路在该集上是否有可直接探测的直链(与 PlayLoader.shouldDirectPlay 同口径) */
    private fun directUrlOf(list: List<VodInfo.VodSeries>?, index: Int): String? =
        list?.getOrNull(index)?.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /**
     * P-质量维度线路级排序(2026-10-10):四级键取最优线路——
     * ①自报标签档(HIGH>PLAIN>TC,实测缺失时的先验) ②覆盖当前播放位置(多集剧护栏:
     * 防"4K 但只有 3 集"的线路在看第 45 集时被误选) ③可数集数降序 ④站点默认序。
     * 实测画质记忆存在时由 [LineQualitySelector] 主导,本方法只兜底;
     * 电影(全不可数)标签档自然成为主导——与完整性豁免设计互补。
     */
    private fun bestFlagByLabelAndCompleteness(info: VodInfo): String? {
        // 候选按站点展示顺序取(与换线侧 EpisodeMatcher.lineFlagsInDisplayOrder 同源),
        // 这样"线路健康降权"与既有四级键的相对次序才有稳定基准。
        val flags = EpisodeMatcher.lineFlagsInDisplayOrder(info)
        if (flags.isEmpty()) return null
        // 线路健康(2026-10-13,B 方案):在既有四级键之上叠加"近期连续失败/取流慢"的降权。
        // **只降权、不剔除** —— 与换线侧同一口径;开关关闭或没有记忆时 orderScores 全 0,等价原行为。
        // ⚠️ 键必须与录入侧同构:sourceKey 取 info.sourceKey(详情期已带),缺任一要素则取不到记忆、按中性处理。
        val keys = flags.map { LineHealthMemory.keyOf(info.sourceKey, info.id, it) }
        val health = if (keys.any { it.isNotEmpty() }) {
            LineHealthMemory.orderScores(keys.filter { it.isNotEmpty() }, System.currentTimeMillis())
        } else {
            emptyMap()
        }
        // 每个 flag 的排序键预计算一次: [健康分, 标签档, 覆盖当前集, 可数集数]
        val ranked = flags.mapIndexed { i, flag ->
            flag to (listOf(health[keys[i]] ?: 0) + labelCompletenessScore(info, flag))
        }
        // 排序优先级:健康分(降权沉底)> 自报标签档 > 覆盖当前集 > 可数集数 > 站点原序(stable)。
        // 高级分靠前 ⇒ 坏线不再"因为排在站点第一位就先被起播选中",这是 B 方案的核心收益。
        return ranked.sortedWith(
            compareByDescending<Pair<String, List<Int>>> { it.second[0] }
                .thenByDescending { it.second[1] }
                .thenByDescending { it.second[2] }
                .thenByDescending { it.second[3] },
        ).firstOrNull()?.first
    }

    /**
     * 四级排序键(P-质量维度,2026-10-10):自报标签档(HIGH>PLAIN>TC) → 覆盖当前播放位置 →
     * 可数集数降序 → 站点默认序(由调用方的稳定排序保证)。返回可比较的元组,越大越优先。
     */
    private fun labelCompletenessScore(info: VodInfo, flag: String): List<Int> {
        val list = info.seriesMap?.get(flag).orEmpty()
        val label = QualityLabelPolicy.priorityFromLabel(flag)
        val count = list.mapNotNull { e ->
            val n = EpisodeNormalizer.normalize(e.name)
            if (n.type == EpisodeType.REGULAR) n.episode else null
        }.distinct().size
        val covers = if (list.size > info.playIndex) 1 else 0
        return listOf(label, covers, count)
    }

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
    /**
     * 探测请求的 header —— **与播放侧同口径**。
     *
     * <p>播放侧（`PlayLoader.mergeSiteHeaders`）的 header = 爬虫解析结果里的头 +
     * 源配置的头（**只补缺、不覆盖**），**从不注入 UA**。
     *
     * <p>⚠️ 旧实现无条件 `headers["User-Agent"] = UA.random()` —— 这是**探测独有的**，
     * 播放侧没这一步。真机后果（2026-10-10）：同一个 m3u8 地址「播放顺畅、探测 404」，
     * 且抽到 `UA.random()` 的兜底值（`Mozilla/5.0 (Macintosh…)`）时**稳定复现 404**。
     *
     * <p>探测要与播放同命运，header 就必须同源 —— 所以这里**一个字段都不多加**。
     */
    private fun probeHeaders(siteKey: String): Map<String, String> {
        val headers = HashMap<String, String>()
        ApiConfig.get().getSource(siteKey)?.header?.let { headers.putAll(it) }
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
    /**
     * 进入"暂无片源"空态。
     *
     * @param msg   空态文案
     * @param force **预算强制收口**专用:绕过"聚合搜索还在跑"的递延守卫。
     *   普通路径(搜索已定论)传默认 false,守卫照旧 —— v1.0.33 的教训就是空态守卫不能拆。
     *
     * <p>⚠️ force=true 且搜索仍在跑时**刻意不记**"无资源":聚合搜索可能马上回话,
     * 此刻记会把一部真实存在的片从首页/搜索里永久藏掉。"无资源"这个结论只属于
     * **搜索已定论**的路径([settleDeferredEmpty] / [finishFallbackWithoutResult])。
     */
    private fun enterEmpty(msg: String? = null, force: Boolean = false) {
        if (sourcesSearching.value) {
            if (!force) {
                // ⚠️ 这条日志是「为什么页面没进空态」的唯一可观测点,别删。
                LOG.i("echo-detail-empty-deferred key=$sourceKey id=$vodId reason=search-in-flight msg=$msg")
                return
            }
            LOG.i("echo-detail-empty-budget key=$sourceKey id=$vodId searching=true msg=$msg")
        }
        sendCommand(PlaybackCommand.ClearSourceSwitchTip)
        LOG.i("echo-detail-empty-state msg=$msg key=$sourceKey id=$vodId")
        pageState.value = PageState.Empty(msg)
        // 方案 B 第二层(点空回写):走到这里说明"当前源 + 聚合换源都没能拿到内容",
        // 是"这部片子当前真的没资源"的可靠信号 —— 记下来,下次搜索/首页不再显示它的海报。
        //
        // ⚠️ 刻意**只在这一步**记,不在 handleEmptyDetail 入口记:入口处换源链还没跑完,
        // 站点抖动返回的空会被误判成"永久没资源",好片就此消失。真正的空只有终态才算。
        // 预算强制收口时搜索尚未定论,同样不算"终态"(见上)。
        if (!force || !sourcesSearching.value) {
            markCurrentVodUnavailable()
        }
    }

    /**
     * 把当前片标记为无资源并通知列表刷新。
     *
     * <p>标记失败不影响任何播放逻辑 —— 这是一条纯体验优化,不该有能力影响主流程。
     *
     * <p>## 为什么源被封时**不写**这个标记(2026-10-13)
     * 两条链路的有效期差三个数量级:源封禁是 30 分钟 ~ 6 小时(**会**自动解除),
     * 而本标记是 90 天(见 [AvailabilityMemory] 的 TTL)。若当前源正在封禁中就写标记,
     * 等于把"源暂时不答话"这件**源侧**的短期故障,记成了这部片**影片侧**的长期属性 ——
     * 源 30 分钟后已自动解封,用户却还要 90 天看不到这张海报。
     *
     * <p>触发路径真实存在:从历史/收藏进入(那里不过滤被封源),或本次会话中该源
     * 被并行的搜索判封。此时"取不到内容"有明确的旁因,不能据此断言影片无资源。
     */
    private fun markCurrentVodUnavailable() {
        val site = sourceKey
        val id = vodId
        if (site.isEmpty() || id.isEmpty()) return
        if (SourceHealthMemory.isBlocked(site)) {
            LOG.i("echo-unavailable mark skip: source-blocked site=$site vod=$id")
            return
        }
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
     * 该源放不出来 ⇒ 2026-10-10 起**只观测、不再写源级健康台账**。
     *
     * <p>为什么摘掉(真机实证的误伤):播放失败绝大多数是**单条线路**的问题 ——
     * "利未记"在健康源「天堂」上撞到一条 Ksvideo 伪地址线路,这条 PLAY_FAILED 连同另外两部片的
     * 播放失败就把整个源封了 6 小时,用户再搜这部片直接搜不到(源被封不参与搜索)。
     * 线路级问题已有线路优选/画质记忆/重试收口处置;源级封禁只认"搜索环节就无应答"的证据
     * (SEARCH_TIMEOUT / SEARCH_FAILED,见 [SourceHealthPolicy.shouldBan])。
     * 保留观测日志,便于统计"源级失败"真实发生频率,再决定是否需要更温和的源级证据。
     */
    private fun recordSourcePlayFailure() {
        val title = vodInfo?.name ?: vodName
        LOG.i("echo-srcban play-failed observed source=" + sourceKey + " content=" + title + " (not recorded)")
        // 2026-10-13 真机修复(问题 17c):**记入会话级短期失败记忆**(与源级健康台账不同 ——
        // 那条刻意不记播放失败以免误封;这条只影响"本轮换源链别原地打转")。
        // 换源链在 loadNextFallbackCandidate 里据此跳过本会话已放不出来的源,根治死循环。
        PlaybackFailureMemory.recordFailure(sourceKey)
        recordBoardFailure(fallbackAutoSwitch, "play-failed")
    }

    /**
     * 往**面板台账**记一条"这个源这一场没交付出内容"（🟡 与两次失败后隐藏的状态来源）。
     *
     * <p>一次起播尝试只允许贡献**一条**事实：线路耗尽、详情超时、质量看门狗往往指向同一次失败，
     * 记两条就会把一次抖动凑成"两条不同来源的证据"而误隐藏一个源。闩随 resetEngineState 复位。
     *
     * @param fromAutoChain true=自动链/引擎自己发现的失败；false=用户手动切过去之后才失败
     *                      （只有后者叠加前者才会隐藏）
     * @param reason        日志尾缀，用于区分是哪条路径记的这条事实
     */
    private fun recordBoardFailure(fromAutoChain: Boolean, reason: String) {
        if (failureCountedForAttempt) return
        failureCountedForAttempt = true
        if (sourceKey.isNullOrEmpty()) return
        // 面板状态(🟡 / 隐藏)记在**这块面板自己的台账**上,不借用短期失败记忆那条 5 分钟账 ——
        // 那个 TTL 是给机器节流用的,给人看的状态要是跟着它过期,🟡 会自己变回无色、
        // 隐藏的源会自己冒回来,用户看到的就又是"列表在乱动"。
        val changed = sourceBoard.recordFailure(sourceKey)
        // ⚠️ 状态没变也要留一行:v1.1.49 真机同一个源连失败三次,只有第一条进了日志,
        // 于是"为什么还不隐藏"根本无从判断(那时判据是"两条不同来源",第二侧永远等不来)。
        LOG.i(
            "echo-source-board ord=" + sourceBoard.ordinalOf(sourceKey) +
                " fails=" + sourceBoard.failCount(sourceKey) +
                " mark=" + sourceBoard.markOf(sourceKey) +
                " by=" + (if (fromAutoChain) "auto-chain" else "user-pick") + " " + reason
        )
        if (changed) publishSourceChips()
    }

    /**
     * "当前源这次没给出内容"（详情超时 / 不可播 / 空详情）⇒ 记一条与播放失败同口径的事实。
     *
     * <p>归属沿用 [recordSourcePlayFailure] 的判据：`fallbackAutoSwitch` 为真说明这个站是**自动链
     * 替他试的**，为假说明这一站是**他自己选进来的**（点卡片进来的起点源、或手动切的芯片）。
     * 硬写 true 会让"手动切过去却空详情"永远记不到 user 侧，🟡 就再也变不成隐藏。
     *
     * <p>⚠️ 断网闸门是必须的：设备没网时**全池**都会同时超时，那是网络的错不是源的错。
     * 不加这道闸，一次断网就能把整页候选都标成 🟡，回到有网后用户看到的是"源莫名其妙变黄/消失"。
     */
    private fun markCurrentSourceGaveNothing(reason: String) {
        if (!SourceHealthMemory.isNetworkAvailable()) return
        // 索引型源(indexs=1、或实测回包缺 list)与已不在订阅的源**不给详情是我们自己的路由**
        // (见 [loadDetail] 的 isUnloadableTarget 早退),不是"这个源放不出这部片"的证据。
        // 真机 v1.1.47:每次从豆瓣卡片进详情页都会记一条 `ord=0 mark=SUSPECT by=user-pick
        // detail-unavailable` —— 豆瓣本来就没有正片,而它连号都没分到(不在表里),这条 🟡 纯脏账。
        val bean = ApiConfig.get().getSource(sourceKey)
        if (bean == null || bean.isIndexSource() || DetailNoListMemory.isIndexLike(sourceKey)) return
        recordBoardFailure(fallbackAutoSwitch, reason)
    }

    private fun startFallbackIfNeeded(auto: Boolean, fromLinesExhausted: Boolean = false): Boolean {
        // 换源链自己带 4s 候选站超时(见 scheduleDetailTimeout),详情看门狗到此交班,免得两套表互相抢
        disarmDetailWatchdog()
        val currentSource = ApiConfig.get().getSource(sourceKey)
        // 站点不在当前订阅(切源后残留的历史/收藏条目)时没有"当前源"可换,但同名片仍能靠聚合搜索接管。
        //
        // ⚠️ 判据**不能**只是 `!isChangeable()`(2026-10-13 真机修正)。索引型源
        // (js_douban:`"searchable":0,"quickSearch":0,"changeable":0`)自己没有任何线路,
        // 内容只能靠聚合搜索从别站取 —— 它的 `changeable=0` 是"站内没有可换线路",
        // 不是"不许去别处取内容"。旧判据把它一律拦下 ⇒ 已经灌进候选表的种子候选
        // (seedCandidatesFromCache)没人消费,链永远起不来。
        // 真机实测(v1.0.83-diag,16:49:33《潜伏6》二次进入):cache-seed n=1 之后链被否掉,
        // 直到 16:50:25 新候选迟到才起播 —— 白等 55 秒。
        // 详见 [FallbackEntryPolicy.blocksFallbackEntry]。
        if (FallbackEntryPolicy.blocksFallbackEntry(
                changeable = currentSource?.isChangeable() ?: true,
                // 与 loadDetail 的 indexSource 判据**完全一致**:两处口径不同会让"该早退的没早退"
                // 或"该放行的被拦住",而这正是本条修复要消灭的那类不一致。
                indexLike = currentSource?.isIndexSource() == true || DetailNoListMemory.isIndexLike(sourceKey),
                onChain = fallbackActive,
            )
        ) {
            LOG.i("echo-fallback no-start reason=entry-blocked changeable=" + (currentSource?.isChangeable() ?: true) + " indexLike=" + (currentSource?.isIndexSource() == true || DetailNoListMemory.isIndexLike(sourceKey)) + " onChain=" + fallbackActive)
            return false
        }
        // 已在换源链上又收到"线路耗尽" ⇒ 刚切换过去那个候选站也挂了,必须继续往下取候选。
        // 原来这里直接 return true 会让换源链只跳一跳,之后永久卡死在那个坏站上(既不换源也不再报错推进)。
        if (fallbackActive) {
            usedSourceKeys.add(sourceKey)
            fallbackLoadingCandidate = false
            return loadNextFallbackCandidate()
        }
        val title = (if (vodInfo?.name.isNullOrEmpty()) vodName else vodInfo?.name).orEmpty().trim()
        if (title.isEmpty()) {
            LOG.i("echo-fallback no-start reason=empty-title key=$sourceKey id=$vodId")
            return false
        }
        fallbackKeepCurrentDetail = fromLinesExhausted && vodInfo != null && !vodInfo?.seriesMap.isNullOrEmpty()
        captureFallbackEpisode()
        searchTitle = title
        usedSourceKeys.add(sourceKey)
        fallbackActive = true
        fallbackAutoSwitch = auto
        triedKeys.add(candidateKey(sourceKey, vodId))
        return loadNextFallbackCandidate()
    }

    /** 用户**此刻手上这一集**的集号(不可数/无数据 ⇒ 0,判据据此退化为中性)。
     *
     * <p>取数优先级:换源链已捕获的目标集([fallbackEpisode])> 当前详情页正在播的集。
     * 前者优先是因为排序发生在"候选站还没加载详情"的时刻,那时 `vodInfo` 可能还是上一个源的内容。
     */
    private fun currentEpisodeNumber(): Int {
        val name = fallbackEpisode?.name ?: run {
            val info = vodInfo ?: return 0
            if (info.playFlag.isNullOrEmpty()) return 0
            info.seriesMap?.get(info.playFlag)?.getOrNull(info.playIndex.coerceAtLeast(0))?.name
        } ?: return 0
        val parsed = EpisodeNormalizer.normalize(name)
        return if (parsed.type == EpisodeType.REGULAR) parsed.episode ?: 0 else 0
    }

    /**
     * 换源候选排序(2026-10-14 定稿:**此刻能不能看 → 源权重 → 实测到达序**)。
     *
     * <p>旧口径是「总覆盖率档位 → 标称画质标签 → 融合评分」,两处与产品目的相反:
     * <ul>
     *   <li>总覆盖率回答"整部剧全不全",而用户点换源那一刻要的是"手上这一集及往后能不能连着看"。
     *       一个只更到 20 集的源在他看第 5 集时完全可用,一个缺第 5 集的源哪怕 98% 完整也等于没资源 ——
     *       判据换到 [CandidateRankPolicy.watchabilityRank],并且**只有硬证据才判不可用**。</li>
     *   <li>标称画质标签来自站方自述,而同文件的其它注释早就承认"站点把 4K 写在 540P 地址上是常事"
     *       —— 拿它当排序键等于信广告。用户定稿"没有评分的按测试出来的顺序自然排列",故删除该键,
     *       同层内由稳定排序保持到达序。</li>
     * </ul>
     *
     * <p>第二键用**裸权重**([SourceWeightMemory],与搜索投源同一份台账)而非融合评分:
     * 融合分把完整度又算了一遍(与第一键重复),而健康惩罚本就体现在权重衰减上;
     * 三层语义(有权重者按权重降序在前、无权重者按到达序垫后)与 [SearchBatchPolicy.orderByWeight] 一致,
     * 不会出现"搜索时已把这源排到队尾、换源却仍优先切过去"的自相矛盾。
     *
     * <p>⚠️ **只重排不剔除**:不可用的源沉到队尾,前面的候选全放不出来时仍会被试到;
     * 用户换到别的集号后它会自己浮回来(每次取候选前都重算)。
     */
    private fun sortFallbackCandidatesByCompleteness() = synchronized(fallbackCandidates) {
        val snap = CompletenessMemory.snapshot(completenessTitleKey)
        val confirmed = EpisodeSetMath.confirmed(snap.values)
        val currentEpisode = currentEpisodeNumber()
        val keys = fallbackCandidates.mapNotNull { it.sourceKey?.takeIf { k -> k.isNotBlank() } }.toSet()
        val weightScores = SourceWeightMemory.scoresOf(keys)
        val watchabilityOf: (Movie.Video) -> Int = { video ->
            CandidateRankPolicy.watchabilityRank(
                own = video.sourceKey?.let { snap[it] },
                priorCount = SourceCompletenessPolicy.priorCountFromNote(video.note),
                confirmed = confirmed,
                currentEpisode = currentEpisode,
            )
        }
        val weightOf: (Movie.Video) -> Int = { video ->
            weightScores[video.sourceKey.orEmpty()] ?: SourceWeightPolicy.NEUTRAL
        }
        val hasWeight: (Movie.Video) -> Boolean = { video ->
            weightScores.containsKey(video.sourceKey.orEmpty())
        }
        fallbackCandidates.sortWith(
            compareByDescending<Movie.Video> { watchabilityOf(it) }
                // 第二键(2026-10-14):**相关度**。用户定案"相关度越高的越排在前面"。
                // 放在可看性之后、权重之前 —— 名字更像的那条先赢:权重再高,标题差得远就不该抢位置;
                // 而"能不能连着看"仍是第一优先(残源排再前也没意义,这是上一轮定的口径)。
                .thenByDescending { candidateRelevance(it) }
                .thenByDescending { hasWeight(it) }
                .thenByDescending { weightOf(it) },
        )
        // 可观测(echo-source 前缀已在 LOG 白名单):打 top3 的「可看性分/权重」两个数,
        // 便于真机核对排序是否符合预期。⚠️ 不打源标识(匿名铁律:此处无 index 上下文,打 key 会泄露身份)。
        if (fallbackCandidates.size > 1) {
            val top = fallbackCandidates.take(3).joinToString(",") { v ->
                watchabilityOf(v).toString() + "/" + weightOf(v) + if (hasWeight(v)) "" else "n"
            }
            LOG.i(
                "echo-source-score reorder n=" + fallbackCandidates.size +
                    " ep=" + currentEpisode + " authority=" + confirmed.size + " top3=" + top
            )
        }
    }

    /**
     * 详情就绪后记录该片权威集数(只增不减)与当前源实绩。
     * 取 seriesMap 各线路可数集数的最大值(episodeCount 对"多版本铺列"返回 null,天然过滤);
     * count ≤ 1(电影/单集/集名不可数)不写权威 —— **但无条件打日志**:
     * 2026-10-09 真机核验发现"静默跳过"导致 echo-completeness 全程 0 条,
     * 无法区分"没执行"与"集名不可数" —— 可观测性纪律:诊断日志不该有守卫。
     */
    private fun recordCompleteness(info: VodInfo) {
        val title = searchTitle.ifEmpty { info.name ?: vodName }
        val titleKey = SearchSettings.normalizedTitle(title)
        completenessTitleKey = titleKey
        if (titleKey.isEmpty()) {
            completenessOwnSet = emptySet()
            LOG.i("echo-completeness record skip: empty title key")
            return
        }
        val srcKey = info.sourceKey ?: sourceKey
        // 每条线路经 EpisodeNormalizer 标准化,只收 REGULAR 正片(预告/花絮/特别篇/UNKNOWN 剔除),
        // 跨线路取并集 = 该源可用正片;跨源两源印证由 CompletenessMemory/EpisodeSetMath 完成
        var own = emptySet<Int>()
        info.seriesMap?.forEach { (flag, list) ->
            val s = HashSet<Int>()
            list.forEach { e ->
                val n = EpisodeNormalizer.normalize(e.name)
                if (n.type == EpisodeType.REGULAR && n.episode != null) s.add(n.episode)
            }
            if (s.isNotEmpty()) own = own.union(s)
        }
        completenessOwnSet = own
        if (own.isNotEmpty()) CompletenessMemory.record(titleKey, srcKey, own)
        val confirmed = CompletenessMemory.confirmedSet(titleKey)
        val covPct = (SourceCompletenessPolicy.coverage(own, confirmed) * 100).toInt()
        // 无条件打印(2026-10-09 教训:诊断日志不该有守卫)
        LOG.i(
            "echo-completeness record title=$titleKey src=$srcKey regular=${own.size}" +
                " confirmed=${confirmed.size} cov=${covPct}%" +
                " continuity=${EpisodeSetMath.continuity(own)}"
        )
    }

    /**
     * 取下一个候选换源。
     *
     * @return true = 已取到候选并在加载中,或聚合搜索仍在跑(结论未定,别急着报"无有效源");
     *         false = 候选确实耗尽 ⇒ 调用方据此把"无路可走"交回播放层,由它统一提示
     */
    private fun loadNextFallbackCandidate(): Boolean {
        while (true) {
            if (fallbackCandidates.size > 1) sortFallbackCandidatesByCompleteness()
            // 2026-10-15(用户指出):试过的候选**不再从表里摘掉**。原先这里 `removeAt(0)` 是
            // "取走=删除",于是被自动换源路过的源会从换源列表里**永久消失** ——
            // 用户看到的正是"上一个源明明有这部片,列表里却再也找不到、也切不回去"。
            // 消费语义改由 triedKeys 承担(同一个候选只试一次,不会原地打转),
            // 表只增不减;隐藏当前源是视图层([CandidatePager.pageableSources])的事。
            val video = synchronized(fallbackCandidates) {
                fallbackCandidates.firstOrNull { v ->
                    !usedSourceKeys.contains(v.sourceKey) && !triedKeys.contains(candidateKey(v)) &&
                        sourceBoard.isVisible(v.sourceKey)
                }
            } ?: break
            val cKey = candidateKey(video)
            // **消费的是"试过"这个事实,不是表里的这一行** —— 先记账再判断,否则下面跳过时
            // 同一个候选会被反复取到,这里就会死循环。
            if (!triedKeys.add(cKey)) continue
            // 2026-10-13 真机修复(问题 17c):跳过**本会话内这部片已放不出来**的源。
            // 真机取证(千与千寻):换源链在几个源之间无限循环 —— 换 A 失败→换 B 失败→重搜→又切回 A。
            // usedSourceKeys 只防"同一候选重复取",拦不住"重搜后同源又被重新灌进候选"的死循环。
            // 短期失败记忆(TTL 5min,换片即清)让这一轮别再切回刚失败过的源。
            // ⚠️ 只跳过、不封禁:TTL 过后自动遗忘,该源仍会被重新考虑(与"只降权不封禁"同源)。
            if (PlaybackFailureMemory.hasFailed(video.sourceKey)) {
                LOG.i("echo-fallback skip failed source key=" + video.sourceKey +
                    " content=" + PlaybackFailureMemory.currentContentKey())
                continue
            }
            fallbackLoadingCandidate = true
            fallbackActive = true
            fallbackAutoSwitch = true
            // 换源链**不走 resetEngineState**(它不清 triedKeys/候选表)，所以"这一次尝试只记一条事实"
            // 的闩要在这里自己复位 —— 否则第一个候选记完，后面 B/C/D 全都标不上 🟡。
            failureCountedForAttempt = false
            usedSourceKeys.add(video.sourceKey.orEmpty())
            vodName = video.name ?: vodName
            vodPicture = video.pic ?: vodPicture
            publishHeader()
            publishSourceChips()
            // 取走这个候选后它就被记入 usedSourceKeys、从 chips 里消失 —— 必须同步重算提示,
            // 否则会留下一条指向"已不在 chips 里"的源、UI 渲染不出来却非 null 的僵尸提示
            refreshCrossSourceHint()
            scheduleDetailTimeout()
            // 传 field 而不是捕获一次:候选站与发起请求同属一代(这一代由 loadDetail 或 rollback 定下)
            loadDetailInternal(video.id.orEmpty(), video.sourceKey.orEmpty(), detailRequestToken)
            return true
        }
        publishSourceChips()
        refreshCrossSourceHint()
        // 2026-10-13 真机修复(问题 17b,失败终态):候选遍历完时,若**当前所有候选源都在本会话失败过**
        // ⇒ 换源链无路可走,直接收口进空态,**不再触发新一轮重搜**。
        //
        // 真机取证(千与千寻):所有源都放不出来时,链路表现为
        // "重搜(token=13)→ 换源失败 → 重搜(token=14)→ 换源失败 → …"**无限循环**,
        // 日志里 `source-search all-done ... frozen=false` 后立刻又 `start`,用户看到的就是"卡死无举动"。
        // 短期失败记忆让这里能判定"确实全废了",从而进入既有 [finishFallbackWithoutResult] 终态。
        val liveCandidateKeys = synchronized(fallbackCandidates) {
            fallbackCandidates.mapNotNull { it.sourceKey?.takeIf { k -> k.isNotBlank() } }
        }
        if (liveCandidateKeys.isNotEmpty() && PlaybackFailureMemory.allFailed(liveCandidateKeys)) {
            LOG.i("echo-fallback all candidates failed this session, enter终态 n=" + liveCandidateKeys.size)
            finishFallbackWithoutResult()
            return false
        }
        if (!sourcesSearching.value) {
            // 候选遍历完 + 全站搜索也已经停了 ⇒ 真没路了。这条与上面"全候选本场都失败过"
            // 是两种不同终态,日志必须分开写,否则复盘时分不清是哪一种收口。
            LOG.i("echo-fallback end reason=search-stopped candidates=" + liveCandidateKeys.size)
            finishFallbackWithoutResult()
            return false
        }
        // 说"已接管"其实是"等下一批候选回来":搜索还在跑 ⇒ 不换源、也不报终态。
        // 没有这行日志,外面看到"线路耗尽之后什么都没发生"时无法区分"在等"与"卡死"。
        LOG.i("echo-fallback keep reason=search-running candidates=" + liveCandidateKeys.size)
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

    /**
     * P-完整性播放中纠偏(2026-10-09):当前源"严重不全"(如 1/50)且聚合发现了**完整档**候选
     * ⇒ 静默自动换源(走既有换源链:候选按完整度排序、集数/进度继承、失败回滚)。
     *
     * <p>护栏:仅 Ready 态触发(Loading 态由起播跳过路径负责);不在换源链上时才触发;
     * 每部片一次([completenessCorrected]);候选必须**先验即完整档**才值得打断当前播放
     * —— 轻微落后不纠偏,避免频繁切换打扰。
     */
    private fun maybeCorrectCompleteness() {
        if (completenessCorrected) return
        if (pageState.value !is PageState.Ready) return
        if (fallbackActive || fallbackLoadingCandidate) return
        val confirmed = CompletenessMemory.confirmedSet(completenessTitleKey)
        if (confirmed.isEmpty() || completenessOwnSet.isEmpty()) return
        if (SourceCompletenessPolicy.tier(completenessOwnSet, confirmed) !=
            SourceCompletenessPolicy.Tier.BACKUP
        ) return
        val better = synchronized(fallbackCandidates) {
            fallbackCandidates.any { c ->
                val set = completenessCandidateSet(c, confirmed)
                set != null && SourceCompletenessPolicy.tier(set, confirmed) ==
                    SourceCompletenessPolicy.Tier.COMPLETE
            }
        }
        if (!better) return
        completenessCorrected = true
        LOG.i("echo-completeness correct-trigger own=${completenessOwnSet.size} auth=${confirmed.size}")
        startFallbackIfNeeded(auto = true)
    }

    /** 候选的正片集号集合:优先取已记录的实测集合,否则退回 note 先验按可信参考集大小换算 */
    private fun completenessCandidateSet(c: Movie.Video, confirmed: Set<Int>): Set<Int>? {
        CompletenessMemory.snapshot(completenessTitleKey)[c.sourceKey]?.let { return it }
        val prior = SourceCompletenessPolicy.priorCountFromNote(c.note)
        if (prior <= 0) return null
        // note 只有数量没有集号:构造 1..prior 的近似集合(仅供档位判定,不用于缺集标注)
        return (1..prior).toSet()
    }

    private fun finishFallbackWithoutResult() {
        val keep = fallbackKeepCurrentDetail
        // P-完整性:起播跳过当前源后链收口无果(候选全失败/全不更全)⇒ 回退起播当前(不完整)源
        // —— 有得播总比空态好;"跳过"只承诺"先试更全的",不承诺"当前源不能用"
        if (pendingCompletenessSkip && vodInfo != null) {
            pendingCompletenessSkip = false
            LOG.i("echo-completeness skip-exhausted fallback-to-origin")
            resetEngineState(keepChips = true)
            pageState.value = PageState.Ready
            requestPlay()
            return
        }
        resetEngineState(keepChips = true)
        if (!keep && rollbackManualSwitch()) return
        if (!keep && pageState.value != PageState.Ready) {
            // 候选与搜索都已尽:页面留在空态。
            // 文案必须区分两种情况(见 [SourceSearchStatus.emptyReason]):
            //   - 源都答了话、确实没有 ⇒「此影片暂无法播放」
            //   - 多数源超时/没答上   ⇒「多数片源没答上…可稍后重试」
            // 真机《潜伏6》:问过 58 个源、49 个超时、只有 1 个答话,却统一报前者 ——
            // 用户读到的是"这片不存在",而该做的事其实是换个网络/时段重试。
            val reason = SourceSearchStatus.emptyReason(
                asked = searchedSourceKeys.size,
                timedOut = timedOutSourceCount,
            )
            LOG.i(
                "echo-fallback empty-reason reason=$reason asked=${searchedSourceKeys.size}" +
                    " timeout=$timedOutSourceCount"
            )
            enterEmpty(
                str(
                    if (reason == SourceSearchStatus.EmptyReason.TIMEOUT_DOMINANT) {
                        R.string.player_play_timeout_dominant
                    } else {
                        R.string.player_play_failed_all
                    }
                )
            )
        }
    }

    private fun scheduleDetailTimeout() {
        if (detailTimeoutScheduled) return
        detailTimeoutScheduled = true
        mainHandler.postDelayed(candidateDetailTimeout, DETAIL_FALLBACK_DETAIL_TIMEOUT_MS)
    }

    private fun cancelDetailTimeout() {
        detailTimeoutScheduled = false
        // ⚠️ 只撤**自己布防的两个**任务,绝不能用 `removeCallbacksAndMessages(null)`:
        // 同一个 Handler 上还挂着 [loadingBudget] —— 那是"页面停在 Loading 超过 15s
        // 必须给用户一个确定画面"的最后一道保险。一并摘掉的表现就是:详情慢或压根不回包时,
        // 页面无限转圈、看门狗也不再报,用户只能退出重进。
        mainHandler.removeCallbacks(candidateDetailTimeout)
        disarmDetailWatchdog()
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
        // 2026-10-14 修复用户反馈 #1:「搜索在途却显示'点击搜索其他片源'」。
        //
        // 真机证据(v1.1.3-diag,盒子里的羊/与王生活的男人):
        //   `18:41:01.909 ui searching=true  progress=null/null chips=1`
        //   `18:41:08.809 ui searching=false progress=null/null chips=1`
        // → UI 侧 `sourcesSearching=true` 但 `searchProgress=null`,于是 [DetailSections]
        //   落到 else 分支显示提示文字,进度条永不出现。
        //
        // 根因:本函数原先**只清 searchProgress 不清 sourcesSearching**,造成二者撕裂。
        // 更糟的是 [publishSearchProgress] 开头 `if (total <= 0) return`,一旦 total 被清 null,
        // 之后所有进度上报全部 return —— 进度再也回不来(直到下一轮 startSourceSearch)。
        // 每次 `switchSource`/`loadDetail`/换源都会走到这里,所以"每次换源后进度就消失"。
        //
        // 修法:**二者成对清**(与 [startSourceSearch] 成对置位、[freezeFallbackSearch] 成对清一致),
        // 绝不允许出现 searching=true && progress=null 的非法组合。
        searchProgress.value = null
        // ⚠️ 2026-10-18:换片与换源**必须分开处理**,这是缺陷 A 的修正。
        //
        // 旧实现在这里无条件把 `sourcesSearching` 写成 false,却让 Job 活着 ——
        // 而本函数 8 个调用点里,`keepChips = true` 的那几处(详情就绪 `onDetailResult`、
        // 用户点换源 `switchSource`、起播跳过兑现 `finishFallbackWithoutResult`)都是
        // "同一部片、搜索应当继续跑"的场景。于是在那些路径上出现"状态说停了、搜索还在跑":
        //   - Job 继续投源、继续往 [searchedSourceKeys] 记账;
        //   - [loadNextFallbackCandidate] 的判据 `!sourcesSearching` 被谎报成"搜索已停",
        //     换源链提前宣布"无路可走"(真机"越搜越少"的观感即由此放大);
        //   - Job 真正收口时又会按 `!searchFrozen` 走一次接管分支,而页面内容可能已换。
        //
        // 现在:换片(!keepChips)⇒ 撤 Job 并置 false,两者同生共死;
        //      换源/详情就绪(keepChips)⇒ **两者都不动**,由搜索自己收口
        //      ([startSourceSearch] 的收口分支与 [freezeFallbackSearch] 负责置位)。
        if (!keepChips) {
            searchJob?.cancel()
            searchJob = null
            sourcesSearching.value = false
        }
        manualSwitching.value = false
        // 2026-10-14(换源分页):换源(keepChips=true)时**保留** manualSwitchActive —— 用户还在
        // "换源浏览"状态,搜索应继续跑全量;换片(keepChips=false)才复位(新片重新开始)。
        if (!keepChips) {
            manualSwitchActive = false
            chipPage.value = 0
            // 换片 ⇒ 整场作废:候选表、去重集、匿名号一起清(号从 1 重新开始)。
            // 这条日志是"源是不是又没了"的唯一可核对点:同一个片名**重复**出现这行就是 bug,
            // 因为表只允许在换片时清。片名取 boardTitle(面板自己记得它是哪部片的表)——
            // 现场取 searchTitle 会是空串,因为 resetContentState 先把它清了(v1.1.47 真机四条全空)。
            // `(empty-board)` = 这块表从来没进过源,清它本来就无话可说。
            LOG.i(
                "echo-source-board cleared title=" +
                    (boardTitle.ifEmpty { searchTitle }.ifEmpty { vodName }.ifEmpty { "(empty-board)" }) +
                    " dropped=" + synchronized(fallbackCandidates) { fallbackCandidates.size } +
                    " ordinals=" + sourceBoard.size()
            )
            sourceBoard.clear()
            boardTitle = ""
            // 新片=新的一场:措辞回到"还没搜过",否则一进来就显示"暂时没找到其他源"
            sourceSearchStarted.value = false
        }
        // 换源/重扫:新源尚未有收敛结论,跨源提示也必须先撤下,否则会拿着上一个源的结论弹提示
        sourceConverged = false
        crossSourceHint.value = null
        pendingCompletenessSkip = false
        completenessCorrected = false
        // 2026-10-13(问题 17c):切换内容键。**仅当片名真的变了才清**短期失败记忆 ——
        // 换源时片名不变,记忆必须保留(否则"跳过本会话失败过的源"立刻失效);
        // 换片时片名变,记忆清空(另一部片在源A失败不该牵连这一部)。见 PlaybackFailureMemory.beginContent。
        PlaybackFailureMemory.beginContent(searchTitle.ifEmpty { vodName })
        cancelDetailTimeout()
        triedKeys.clear()
        // 新的等待开始 ⇒ 面板按需探测的资格与"已试过哪些线路"一起复位。
        // 换源也算：不同站的同名 flag 是完全不同的线路(日志里 aidan 与虎牙都有 xzm3u8),
        // 沿用上一源的"试过了"会让新源的线路永远拿不到标签。
        playbackStartedHere = false
        failureCountedForAttempt = false
        panelProbeJob?.cancel()
        panelProbeJob = null
        panelProbedFlags.clear()
        if (!keepChips) {
            synchronized(fallbackCandidates) { fallbackCandidates.clear() }
            candidateKeys.clear()
        }
        // 换片/换源:迷你探测的"每片一次"、窗口与冷却都要清零,否则旧片的探完就会带给新片
        miniProbeJob?.cancel()
        miniProbeJob = null
        miniProbeDoneTitles.clear()
        miniProbeNegativeAt.clear()
        publishSourceChips()
    }

    fun destroyEngine() {
        // 页面要走:合并窗口里排队的命中回存必须当场兑现(作用域随后就会被取消)
        flushFallbackHitsSave()
        cancelDetailTimeout()
        qualityProbeJob?.cancel()
        qualityProbeJob = null
        // ⚠️ 迷你探测是**独立** Job(见 runMiniProbeForCrossSource),必须单独撤 ——
        // 否则页面销毁后它还在后台跑网络请求,而 BoundedCall 是阻塞调用、cancel 打断不了,
        // 只能靠这里的取消 + 协程内的 isActive 自检共同兜住。
        miniProbeJob?.cancel()
        miniProbeJob = null
        miniProbeDoneTitles.clear()
        miniProbeNegativeAt.clear()
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

    /**
     * 候选表的**唯一追加口**：去重（同一 `源|id` 只留一条）→ 定号（源第一次进表才占号）→ 落表。
     * 返回**真正新增**的条目，调用方据此决定要不要重排 / 发布 / 自动接管。
     *
     * <p>为什么要有这一个出口：此前有三处各自 `fallbackCandidates.add(...)` + 各自一份
     * `candidateKeys` 去重，而号（给用户的"源N"）从来没跟身份绑过 —— 于是加一处漏一处，
     * 标签与表内容迟早分叉。追加只走这里，清表只走换片那一条，二者对称。
     */
    private fun appendCandidates(videos: List<Movie.Video>): List<Movie.Video> {
        if (videos.isEmpty()) return emptyList()
        val added = ArrayList<Movie.Video>(videos.size)
        var rejectedByTitle = 0
        synchronized(fallbackCandidates) {
            for (v in videos) {
                // 「是不是这部片」的门槛(判据见 PlayTitleMatch)。**在占去重键之前**判:
                // 先 add 再拒掉会把 key 烧掉,同一张卡片以后带着正确标题再来就再也进不来。
                if (PlayTitleMatch.isDifferentFilm(v.name, entryTitle)) {
                    rejectedByTitle++
                    continue
                }
                if (!candidateKeys.add(candidateKey(v))) continue
                sourceBoard.assign(v.sourceKey)
                // 这块面板从有了第一个源起就属于某一部片了:记下出场片名(诊断用,见 boardTitle KDoc)
                if (boardTitle.isEmpty()) boardTitle = searchTitle.ifEmpty { vodName }
                fallbackCandidates.add(v)
                added.add(v)
            }
        }
        if (rejectedByTitle > 0) {
            LOG.i(
                "echo-play-verify layer=candidates rejected=" + rejectedByTitle +
                    " of=" + videos.size + " want=\"" + entryTitle.take(30) + "\""
            )
        }
        return added
    }

    /**
     * 把**当前这个源自己**补进换源候选表(2026-10-15 用户指出的缺口)。
     *
     * <p>## 补的是什么空白
     * 候选表此前只有一个来源:全站聚合搜索的回包。而"用户点进来的这个源"是从详情直接起播的 ——
     * 2026-10-14 定案"能起播就不自动搜全站"之后它常常**根本没被搜过**;就算搜过,
     * 回包也在数据层被"当前源"那条判据丢掉。两条路都进不了表,于是真机表现成:
     * 起点源因网络波动整源放不出来 → 自动切到下一个源 → **原来那个源在换源列表里永远消失**,
     * 用户既看不到它也切不回去,而那部片明明就是它给出的。
     *
     * <p>视图层([CandidatePager.pageableSources])本来就排除当前源,所以把它留在表里没有副作用:
     * 正在播时它不出现在那一行,切走之后自然回来。
     */
    private fun ensureCurrentSourceCandidate(video: Movie.Video) {
        val key = video.sourceKey.orEmpty()
        val id = video.id.orEmpty()
        if (key.isEmpty() || id.isEmpty()) return
        if (appendCandidates(listOf(video)).isEmpty()) return
        LOG.i(
            "echo-source-switch anchor-seeded ord=" + sourceBoard.ordinalOf(key) +
                " board=" + sourceBoard.size()
        )
    }

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
     * <p>解析与播放同路([resolveTargetForProbe]):先跑爬虫 `playerContent`,拿不到才退回
     * `series.url`。**不能**像旧实现那样优先用 `series.url` —— 真机坐实它常常不是播放地址
     * (同一条线的播放地址由爬虫现算),那样探的是另一个流。
     * 本仓无解析缓存,所以起播那次仍会照常解析(多一次解析 + 一个 ≤256KB 的探测)。
     */
    private fun probeLineOnDemand(flagName: String) {
        vodInfo ?: return
        val mode = DeviceCapability.effectiveMode()
        if (!mode.shouldProbeOnFirstWatch) return
        val site = sourceKey
        val vod = vodId
        if (site.isEmpty() || vod.isEmpty()) return
        // 已有实测值就别再探:重复请求毫无意义(这条线的画质是稳定的)
        val existing = VideoQualityMemory.lookupAll(site, vod, listOf(flagName))
        if (existing.isNotEmpty()) return
        qualityProbeJob?.cancel()
        qualityProbeJob = viewModelScope.launch {
            val (target, _) = resolveTargetForProbe(flagName)
            if (target == null) return@launch
            val probe = VideoQualityProbe()
            val measured = try {
                probe.probe(target.url, mergeProbeHeaders(probeHeaders(site), target.headers))
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
     * 迷你只读探测(P1):为**跨源提示**提前取一次对端画质。
     *
     * <p>## 与源内 sweep 的分工
     * 源内 sweep 只认当前源;跨源要提前知道对端,只能真去问一次对端。
     * 但这是**额外网络请求** —— 在实测源超时率 75~83% 的环境里,无脑发就是纯负收益。
     * 所以这里一切以"不发"为默认:先过 [MiniProbeGate](类型闸)与 [MiniProbeBudget](预算闸),
     * 任何一条不过就直接返回。
     *
     * <p>## 安全边界
     * <ul>
     *   <li>**绝不投递 UI 通道**:取数走 [MiniProbeReader](独立哑通道),只把实测值写进画质记忆;</li>
     *   <li>**绝不自动换源**:本方法一个 switch 都不调,提示由 [refreshCrossSourceHint] 独立决定;</li>
     *   <li>每片每会话一次、串行走、失败进 30 分钟冷却。</li>
     * </ul>
     */
    private fun runMiniProbeForCrossSource() {
        if (!sourceConverged) return
        val info = vodInfo ?: return
        val titleKey = searchTitle.ifEmpty { vodName }
        // 整体准入:流量节省 / 本片已探过。**刻意不含"网络是否富余"** ——
        // 那个信号只存在于播放内核内部,详情页观测不到,硬编码一个 true 就是空闸门
        // (理由见 MiniProbeBudget 的 KDoc)。
        if (!MiniProbeBudget.canStart(
                trafficSaver = DeviceCapability.trafficSaverOn(),
                titleAlreadyProbed = miniProbeDoneTitles.contains(titleKey),
            )
        ) {
            LOG.i("xhint-skip budget-gate")
            return
        }
        // 已给出去提示就不必再探;已有提示时 refreshCrossSourceHint 已经能工作
        val candidates = synchronized(fallbackCandidates) { fallbackCandidates.toList() }
            .mapNotNull { v ->
                val k = v.sourceKey.orEmpty()
                val id = v.id.orEmpty()
                if (k.isEmpty() || id.isEmpty()) null else Triple(k, id, v.name.orEmpty())
            }
            .distinctBy { it.first to it.second }
        if (candidates.isEmpty()) return
        miniProbeDoneTitles.add(titleKey)
        val title = searchTitle.ifEmpty { info.name ?: vodName }
        // 在**主线程**快照一份已用源集合:usedSourceKeys 是普通 HashSet,由主线程增删,
        // 探测协程在 IO 上遍历它属于跨线程读 —— 快照一次既去掉这个竞争,也固定了这一轮的口径。
        val usedSnapshot = HashSet(usedSourceKeys)
        val curSourceKey = sourceKey
        // L2 探活候选(2026-10-13,P0-3):含源类证据、快到期、被封着的源 —— 让它们借这次迷你探测
        // **自证一次**(MiniProbeGate 会对这批放行被封禁的源),探活成功即平反放回搜索池。
        // 这正是"拉黑后源无法自证"痛点的落地。
        val recoveryKeys = SourceHealthMemory.probeRecoverableKeys().toHashSet()
        // ⚠️ 必须用**独立** Job:本方法是从 sweep 协程(qualityProbeJob)内部调用的,
        // 若复用 qualityProbeJob 就会把正在运行的 sweep 自己取消掉。
        miniProbeJob?.cancel()
        miniProbeJob = viewModelScope.launch(Dispatchers.IO) {
            var sentInWindow = 0
            var windowStart = System.currentTimeMillis()
            for ((candKey, candId, _) in candidates) {
                val bean = ApiConfig.get().getSource(candKey) ?: continue
                // ① 类型闸:探不到的类型一次请求都不发。
                // "已用过"由闸门统一判定(含当前源)—— 不在循环里另判一次,
                // 否则 alreadyUsed 恒为 false,那个分支就成了永远走不到的死代码。
                val gate = MiniProbeGate.decide(
                    sourceType = bean.type,
                    indexSource = bean.isIndexSource,
                    alreadyUsed = candKey == curSourceKey || usedSnapshot.contains(candKey),
                    healthBlocked = SourceHealthMemory.isBlocked(candKey),
                    extendWarm = SourceRuntimeState.isExtendWarm(bean.ext),
                    // L2 探活候选:放行被测所以给"被判死的源"一次自证机会(见 MiniProbeGate.decide)
                    recoveryProbe = recoveryKeys.contains(candKey),
                )
                if (gate is MiniProbeGate.Decision.Skip) {
                    LOG.i("xhint-skip " + gate.reason + " key=" + candKey)
                    continue
                }
                // ② 负冷却:探过的失败源 30 分钟内不再试
                val lastFail = miniProbeNegativeAt[candKey] ?: 0L
                if (MiniProbeBudget.inNegativeCooldown(lastFail, System.currentTimeMillis())) {
                    LOG.i("xhint-skip cooldown key=" + candKey)
                    continue
                }
                // ③ 窗口额度:5 个 / 30 秒,串行铺开
                if (System.currentTimeMillis() - windowStart > MiniProbeBudget.WINDOW_MS) {
                    windowStart = System.currentTimeMillis()
                    sentInWindow = 0
                }
                if (!MiniProbeBudget.hasWindowQuota(sentInWindow)) {
                    LOG.i("xhint-skip quota-out")
                    break
                }
                sentInWindow++
                // ④ 取详情(只读,不进任何 UI 通道)
                val flagHolder = arrayOf<String?>(null)
                val video = try {
                    MiniProbeReader.fetchVideo(bean, candId, title, MINI_PROBE_TIMEOUT_S, flagHolder)
                } catch (t: Throwable) {
                    null
                }
                // ⚠️ BoundedCall 是**阻塞**调用(不是挂起点),所以上面的 job.cancel() 打断不了它 ——
                // 换片/换源后这一轮可能刚刚返回。必须在这里自检活跃性,否则会拿旧片的结果继续往下走,
                // 甚至给新会话写进一条来源不明的记忆。
                if (!isActive) return@launch
                if (video == null) {
                    miniProbeNegativeAt[candKey] = System.currentTimeMillis()
                    LOG.i("xhint-probe no-detail key=" + candKey)
                    continue
                }
                val flag = flagHolder[0]
                // ⚠️ flag 为空 ⇒ **不探也不写**:记忆键是 `站点|片|flag`,编一个 "线路1" 出来会污染
                // 那个源真实线路的键空间(等它真的被播放时,实测值会与这条假键并存/互相覆盖)。
                // 放在探测**之前**:拿不到 flag 就没有可用的写入键,先探一轮再丢弃纯属浪费带宽。
                if (flag.isNullOrEmpty()) {
                    miniProbeNegativeAt[candKey] = System.currentTimeMillis()
                    LOG.i("xhint-probe no-flag key=" + candKey)
                    continue
                }
                val target = firstPlayableUrl(video, flag)
                if (target == null) {
                    miniProbeNegativeAt[candKey] = System.currentTimeMillis()
                    LOG.i("xhint-probe no-url key=" + candKey)
                    continue
                }
                val measured = try {
                    VideoQualityProbe().probe(target, probeHeaders(candKey))
                } catch (t: Throwable) {
                    null
                }
                if (measured == null || !measured.known) {
                    miniProbeNegativeAt[candKey] = System.currentTimeMillis()
                    LOG.i("xhint-probe no-size key=" + candKey)
                    continue
                }
                // 只写记忆:不弹 UI、不换源
                VideoQualityMemory.record(candKey, candId, measured.copy(flag = flag))
                LOG.i(
                    "xhint-probe ok key=" + candKey +
                        " size=" + measured.width + "x" + measured.height +
                        " flag=" + flag,
                )
                // L2 探活成功 ⇒ 平反(2026-10-13,P0-3):这个源此前因源类证据被封着,
                // 但它的详情/取流刚刚实测成功、连尺寸都拿到了 —— 证据确凿地"活着",放回搜索池。
                // 只在它确实是探活候选时才平反(避免把普通探测的成功也当成"平反")。
                if (recoveryKeys.contains(candKey)) {
                    val lifted = SourceHealthMemory.liftByProbe(candKey)
                    if (lifted) LOG.i("echo-recover layer=L2 probe-lifted key=" + candKey)
                }
                // 探到一个就够:拿到能撑起提示的数据即可,不必把所有源都问一遍
                break
            }
            // 探测有了新记忆 ⇒ 重算提示(可能因此出现一条本来没有的提示)
            withContext(Dispatchers.Main) { refreshCrossSourceHint() }
        }
    }

    /** 从候选详情里取"该线路第一集"的播放地址(探测用,不参与播放决策) */
    private fun firstPlayableUrl(video: Movie.Video, flag: String?): String? {
        val infoList = video.urlBean?.infoList ?: return null
        val chosen = infoList.firstOrNull { it?.flag == flag } ?: infoList.firstOrNull()
        val first = chosen?.beanList?.firstOrNull()?.url
        return first?.takeIf { it.startsWith("http") }
    }

    private var miniProbeDoneTitles = HashSet<String>()

    /**
     * 探测失败源的冷却表。
     *
     * <p>⚠️ 必须是并发容器:写入发生在 IO 协程里,而 [loadDetail]/[resetEngineState] 会在
     * **主线程** clear 它。两者可能真正重叠 —— 探测线程常阻塞在 `BoundedCall`(网络)里,
     * 此刻 `job.cancel()` 并不能把它停下。普通 HashMap 的并发 clear/put 会损坏内部结构
     * (表现为取到 null、甚至死循环),这里不能省。
     */
    private val miniProbeNegativeAt = ConcurrentHashMap<String, Long>()

    /** 迷你只读探测的独立协程(sweep 是 qualityProbeJob,两者生命周期必须分开) */
    private var miniProbeJob: Job? = null

    /** 迷你只读探测的单次请求超时(秒):与换源回退的 4s 同档,不给慢源拖时间 */
    private val MINI_PROBE_TIMEOUT_S = 4L

    /**
     * 探测目标:地址 + **与播放同源的 header**。
     *
     * @param via 解析来源(`spider` = 爬虫给出的真实流;`direct` = 爬虫给不出 → 退回 `series.url`)
     */
    private class ProbeTarget(
        val url: String,
        val headers: Map<String, String>,
        val via: String,
    )

    /**
     * 为画质探测解析一条线路的**播放地址与 header** —— **与播放走同一条路**。
     *
     * <p>⚠️ **先问爬虫,爬虫给不出才退回 `series.url`（2026-10-10 真机坐实,别改回去）**。
     *
     * <p>老实现是"`series.url` 是 http 就直接返回它,根本不问爬虫"。真机反例 ——
     * **同一条「高清4K」线路、同一秒钟**:
     * <pre>
     *   播放 goPlayUrl    = v9-vllqsv.ixigua.com/…/o8Gac…/?…mime_type=video_mp4    ← 爬虫给的真实流,内核报 1728×720
     *   探测 probe-failed = fengbao12.com/video/…/571a558145d2/index.m3u8           ← series.url,404
     * </pre>
     * **两者根本不是同一个地址。** 站点在 `series.url` 放的是自己那套 m3u8 镜像,而真正能播的流由爬虫
     * 现算(带签名/防盗链,并携带 `user-agent`、`referer` 等)。跳过爬虫 ⇒ 探测的既不是播放地址、
     * 也没有播放用的 header —— 这就是"**播放正常、探测永远失败**"的真正来源。
     *
     * <p>退回直链**只**在爬虫给不出 url 时发生,与播放的兜底口径完全一致:播放也是
     * "先 `playerContent`,返回空且地址是 http 才回退直连"（见 `PlayLoader.shouldDirectPlay`）。
     * ⚠️ 这条兜底**必须保留**:`ffzy/lf/yzzyvip` 这类源的 `series.url` 本身就是真实 m3u8,
     * 真机上 100 次探测成功**全部**来自它们 —— 不能因为"直链不可信"就一刀砍掉。
     *
     * @return `目标(成功)` 或 `null(失败)`;第二个元素是失败原因,仅用于日志 ——
     *         旧实现把"爬虫实例没拿到 / 爬虫返回空 / 爬虫没给 url / 地址非 http"全记成 `no-url`,
     *         真机排障无法区分"爬虫没加载"与"爬虫确实给不出"(2026-10-10 加,勿删)。
     */
    private suspend fun resolveTargetForProbe(flagName: String): Pair<ProbeTarget?, String> {
        // ⚠️ **整个函数必须在 IO 线程**(2026-10-10 深夜真机坐实,这是"探测全军覆没"的真正元凶):
        // `playerContent` 内部做网络 I/O,在主线程调用会抛 `NetworkOnMainThreadException`;
        // 旧代码在 catch(Throwable) 里把它吞成 null ⇒ 日志一直记成 "no-url",
        // 看起来像"爬虫给不出地址",其实是"我们根本没让它跑起来"。
        // 播放之所以正常,是因为 PlayLoader 把同一个调用丢进了 SPIDER_POOL 后台线程。
        // `getCSP` 首次加载爬虫 jar 同样可能触网,所以一起搬进来,而不是只包 playerContent。
        return try {
            withContext(Dispatchers.IO) {
                val list = vodInfo?.seriesMap?.get(flagName)
                    ?: return@withContext (null to "vod-gone")
                val series = list.getOrNull(probeIndexOf(vodInfo ?: return@withContext (null to "vod-gone")))
                    ?: return@withContext (null to "index-oob")
                val raw = series.url?.trim().orEmpty()
                if (raw.isEmpty()) return@withContext (null to "series-empty")
                // 爬虫给不出时的兜底:与播放的 shouldDirectPlay 同口径(只有 http 地址才回退直连)
                val fallback = if (isHttpProbeTarget(raw)) ProbeTarget(raw, emptyMap(), "direct") else null
                // ① 爬虫:与播放同一条路(播放对每条线路都先跑 playerContent)
                val bean = ApiConfig.get().getSource(sourceKey)
                val spider = bean?.let { ApiConfig.get().getCSP(it) }
                if (spider == null) {
                    return@withContext (fallback to if (fallback != null) "" else "spider-missing")
                }
                val json = spider.playerContent(flagName, raw, ApiConfig.get().getVipParseFlags())
                if (json.isNullOrEmpty()) {
                    return@withContext (fallback to if (fallback != null) "" else "spider-empty")
                }
                val obj = runCatching { org.json.JSONObject(json) }.getOrNull()
                val url = obj?.optString("url", "").orEmpty()
                if (url.isEmpty()) {
                    return@withContext (fallback to if (fallback != null) "" else "spider-no-url")
                }
                // header 用与播放**同一个解析器**,避免两套口径:
                // PlayerHelper.extractPlayHeaders 兼容 header/headers 两种形态与嵌套 JSON 文本
                val playHeaders = runCatching {
                    com.github.tvbox.osc.util.PlayerHelper.extractPlayHeaders(obj)
                }.getOrNull()
                (ProbeTarget(url, playHeaders ?: emptyMap(), "spider") to "")
            }
        } catch (t: Throwable) {
            (null to ("exception:" + t.javaClass.simpleName))
        }
    }

    private fun isHttpProbeTarget(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    /**
     * 探测请求的 header = **爬虫头 ∪ 源配置头**(爬虫优先,键名大小写不敏感)。
     *
     * <p>与播放的合并口径(`PlayLoader.mergeSiteHeaders`)同序:先放播放会用的头,源配置**只补缺**。
     * 反过来会让源配置把站点的防盗链 UA 顶掉 —— 真机上"播放正常、探测 404"最早就是这么来的。
     */
    private fun mergeProbeHeaders(
        site: Map<String, String>,
        playHeaders: Map<String, String>,
    ): Map<String, String> {
        if (playHeaders.isEmpty()) return site
        val out = HashMap<String, String>(playHeaders)
        site.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank() && out.keys.none { it.equals(k, ignoreCase = true) }) {
                out[k] = v
            }
        }
        return out
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
        if (event.type == RefreshEvent.TYPE_PLAYBACK_PICTURE_UP) {
            revokeBoardMarkWithPicture()
            return
        }
        if (event.type == RefreshEvent.TYPE_PLAYBACK_FIRST_FRAME) {
            onPlaybackFirstFrame()
            return
        }
        // 实测画质写进记忆了:重算一次「线路N · 1080P」。不发这个事件的话,
        // 标签本次会话永远刷不出来 —— 探测跑在独立作用域上,这是它唯一的回传途径。
        if (event.type == RefreshEvent.TYPE_LINE_QUALITY_MEASURED) {
            publishLineQualityHeights()
            refreshCrossSourceHint()
            return
        }
        // 自适应画质(2026-10-10):播放中网络富余但记忆里没有更高实测档 → 补探测未测线路。
        // probeMissingLineQualities 幂等(已测的跳过)且有预算上限,频繁触发也只会白查一次记忆。
        if (event.type == RefreshEvent.TYPE_PROBE_MISSING_LINES) {
            probeMissingLineQualities()
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
    /**
     * 用**画面凭据**撤销这一场该源的 🟡/隐藏（2026-10-16 全方位审核 S1）。
     *
     * <p>为什么值得单独一条通路：换源板块标记回答的是"这个源现在能不能放"，
     * 而它以前挂在 `TYPE_PLAYBACK_STARTED` 上 —— 那是"看过了 ⇒ 落历史"的信号，不看画面。
     * 现在凭据来自播放层的"本条内容真的渲染出一帧"（判不了画面的内核不否决，退给连续推进 60 秒兜底），
     * 于是"有声无画"的源不会自己洗白，而正常出画的源几秒内就放回面板。
     *
     * <p>一致性守卫沿用 [onPlaybackStarted] 那一套四元组(内容 id + 源 + 线路 + 集)：
     * 换源/切集交错的瞬间，迟到的确认属于**上一内容**，不许给当前这一行改状态。
     * `recordSuccess` 自身只在"确实有标记可清"时返回 true，重复信号不会连发刷新。
     */
    private fun revokeBoardMarkWithPicture() {
        val info = vodInfo ?: return
        val playing = App.getInstance().vodInfo ?: return
        if (playing.id != info.id || playing.sourceKey != info.sourceKey) return
        if (playing.playFlag != info.playFlag || playing.playIndex != info.playIndex) return
        if (sourceBoard.recordSuccess(sourceKey)) {
            LOG.i("echo-source-board ord=" + sourceBoard.ordinalOf(sourceKey) + " mark=NONE by=picture-ok")
            publishSourceChips()
        }
    }

    /**
     * 首帧已渲染:这是"起播成功"**最早也最准**的时点(media3 `onRenderedFirstFrame`)。
     *
     * <p>## 为什么单独一条通路
     * 挂在这里的两件事此前都挂在 [onPlaybackStarted] 上,而那个函数由
     * `TYPE_PLAYBACK_STARTED` 驱动 —— 它的判据是播放头推进到
     * [com.github.tvbox.osc.util.WatchProgressRules.MIN_RESUME_MS](30 秒)。
     * 于是:
     * <ul>
     *   <li>源内画质扫描要等 30 秒才启动 ⇒ 看短剧/看几分钟就走的用户,画质标签永远出不来;</li>
     *   <li>后台聚合搜索要等 30 秒才让路 ⇒ 首帧与起播缓冲期完全没人让,那正是最该让的时段。</li>
     * </ul>
     * 迁移到首帧后,两者都提前到约 1~3 秒。
     *
     * <p>## 一致性
     * 守卫沿用四元组(内容 id + 源 + 线路 + 集):换源/切集交错的瞬间,
     * 迟到的首帧属于**上一内容**,不许给当前这一行改状态。
     */
    private fun onPlaybackFirstFrame() {
        val info = vodInfo ?: return
        val playing = App.getInstance().vodInfo ?: return
        if (playing.id != info.id || playing.sourceKey != info.sourceKey) return
        if (playing.playFlag != info.playFlag || playing.playIndex != info.playIndex) return
        LOG.i(
            "echo-play-first-frame key=$sourceKey id=$vodId" +
                " flag=${info.playFlag} idx=${info.playIndex}"
        )
        // 画面已出 ⇒ 允许"打开线路面板时按需探爬虫型线路"这类后置探测
        playbackStartedHere = true
        publishLineQualityHeights()
        // 源内有界扫描:每片每会话一次(内部自带闩与取消域,幂等)
        sweepSourceLinesForQuality()
        // 画面已经出来了 ⇒ 后台聚合搜索到此为止。
        // 是"冻结"不是"取消":在途的照收(证据不丢);这条源一旦放不出来,
        // 换源链会重新发起一轮新搜索([ensureSourceSearchRunning] 只看 sourcesSearching)。
        // 用户主动换源期间由 [freezeFallbackSearch] 内部的 manualSwitchActive 早退豁免。
        freezeFallbackSearch()
    }

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
        // ⚠️ 这里**不再**撤销这一场的 🟡/隐藏(2026-10-16 全方位审核 S1)。
        // TYPE_PLAYBACK_STARTED 的语义是"看过了 ⇒ 该落观看历史",判据是播放头推进 + 进度阈值,
        // **一次都不看画面**;把它当"这源能放"的证据 ⇒ 真机那种整段黑屏只有声音的源,
        // 一次就把自己洗白,而源加分却要真播 60 秒(隐藏严、撤销松,状态被抖来抖去)。
        // 撤销现在只走 [revokeBoardMarkWithPicture] —— 凭据是画面真的上过屏。
        //
        // ⚠️ 源内画质扫描与后台搜索冻结**不在这里**做(2026-10-18 迁移)。
        // 本函数由 TYPE_PLAYBACK_STARTED 触发,而它的判据是播放头推进到
        // WatchProgressRules.MIN_RESUME_MS(30 秒) —— 把"该让路"和"该探画质"挂在这上面,
        // 等于两者都要等 30 秒才启动。现在它们改由 [onPlaybackFirstFrame] 驱动(首帧即触发)。
        //
        // 这里补一次 [playbackStartedHere] 是**兜底**:纯音频 / 判不了画面的内容永远等不到
        // 首帧事件,不补的话"打开线路面板时按需探爬虫型线路"对它们会永久失效。
        playbackStartedHere = true
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
         * 打开线路面板时按需探测的条数上限（爬虫型线路，每条解析 1~3 秒、串行）。
         * 面板一屏能看到的线路本就有限，再多探的只是"看不到的那几条"，没有收益却持续占带宽。
         */
        private const val PANEL_PROBE_LINES = 6
        /** 单个候选站的同名搜索超时。原 30s:聚合订阅动辄数百站,单站卡住会拖垮整轮候选收集 */
        private const val SOURCE_SEARCH_TIMEOUT_MS = 8_000L

        /**
         * 命中回存的**合并窗口**(2026-10-13)。
         *
         * <p>命中是逐源到达的(最坏 120 次),而每次落盘都要序列化整张命中表 + 遍历 MMKV 键表;
         * 逐条写等于在主线程上跑 120 次全表序列化。合并到 2s 一次,把写次数压到个位数,
         * 而"提前退出/换片"造成的尾部丢失由边界上的 flush 兜住(见 flushFallbackHitsSave)。
         */
        private const val HIT_CACHE_SAVE_MERGE_MS = 2_000L

        /**
         * 加载体验预算:停在 Loading 超过它就必须给确定画面。
         * 与"首屏一批 24 源"配套(12 并发 × 8s ⇒ 最坏 16s),见 [onLoadingBudgetExpired]。
         */
        private const val LOADING_BUDGET_MS = 15_000L

        // i18n: keep —— 源侧"没有数据"的哨兵值;误翻会把空结果判成源报错,详情页提示后自动关闭
        private const val SOURCE_EMPTY_MSG = "数据列表"

        internal fun isSourceErrorMsg(msg: String?): Boolean =
            !msg.isNullOrEmpty() && msg != SOURCE_EMPTY_MSG
    }
}
