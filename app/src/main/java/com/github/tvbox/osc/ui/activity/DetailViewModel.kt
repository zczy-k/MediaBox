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
import com.github.tvbox.osc.player.CrossSourceUpgradePolicy
import com.github.tvbox.osc.player.DeviceCapability
import com.github.tvbox.osc.player.LineQualitySelector
import com.github.tvbox.osc.player.LineHealthMemory
import com.github.tvbox.osc.player.MiniProbeBudget
import com.github.tvbox.osc.player.MiniProbeGate
import com.github.tvbox.osc.player.PlaybackFailureMemory
import com.github.tvbox.osc.player.PlaybackSession
import com.github.tvbox.osc.player.QualityGovernor
import com.github.tvbox.osc.player.VideoQualityMemory
import com.github.tvbox.osc.player.VideoQualityPolicy
import com.github.tvbox.osc.player.VideoQualityProbe
import com.github.tvbox.osc.util.EpisodeTotals
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.HistoryWriter
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

    /**
     * 聚合搜索进度(S6 分批递进,2026-10-09):详情页 Loading 态据实显示"正在搜索片源 N/M"。
     * 修复前无候选时用户对着转圈最长 ~59s 零反馈(120 源 × 单源 8s 超时 ÷ 并发 12),
     * 搜索本身不变,只是把"系统在动"这件事告诉用户;搜索结束/换代/链收口时置 null。
     */
    data class SearchProgress(val done: Int, val total: Int, val candidates: Int)
    val searchProgress = MutableStateFlow<SearchProgress?>(null)
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
     * 起播后"源内有界扫描"的每片会话闩(键 = 站点|片id)。
     *
     * <p>同一部片每会话只扫一次:扫描会真实解析爬虫线路(一次 API 调用/条),
     * 反复扫既浪费又会触发源站风控。换片自然失效(键不同)。
     */
    private var sourceSweepDoneKey = ""

    /** 当前片当前源是否已收敛(sweep 已出 verdict、不会再自动升档)。跨源提示只在收敛后出场。 */
    private var sourceConverged = false

    /**
     * **跨源升清的待办 S**(2026-10-13 真机修复):播放侧可能在**源内收敛之前**就发来跨源请求
     * (它只看 `untriedHigher==0`,不等 sweep)。此时不能丢弃(丢弃 = 该集永不重发,链路失效),
     * 而是把当前实测 S 挂在这里,等 [markSourceConverged] 时补执行一次。
     *
     * <p>0 = 无待办。换片/换源时清除([resetEngineState])。
     */
    private var pendingCrossSourceS = 0

    /**
     * 标记当前源已收敛并**补执行挂起的跨源升清**(若有)。
     *
     * <p>为什么收敛点和补执行要绑在一起:跨源升清的前置条件正是"源内已到头"
     * (`sourceConverged`),而收敛是异步 sweep 的产物。把"置真"与"补执行"收在一处,
     * 避免漏掉某个收敛分支(本项目已有多处 `sourceConverged = true`)。
     */
    private fun markSourceConverged() {
        sourceConverged = true
        replayPendingCrossSourceUpgrade("converge")
    }

    /**
     * 补执行挂起的跨源升清(若有待办)。两个触发时机共用:
     * <ol>
     *   <li>源内收敛([markSourceConverged] —— 播放侧可能在收敛前就发了请求);</li>
     *   <li>聚合搜索命中到达([fallbackCandidates] 填充后 —— 候选就绪才谈得上跨源择优)。</li>
     * </ol>
     *
     * @param reason 仅用于日志,便于在真机上区分"是哪条时机触发的补执行"
     */
    private fun replayPendingCrossSourceUpgrade(reason: String) {
        val held = pendingCrossSourceS
        if (held <= 0) return
        pendingCrossSourceS = 0
        LOG.i("echo-xsource replay held S=" + held + " after " + reason)
        tryCrossSourceUpgrade(held)
    }

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
        val mode = DeviceCapability.QualityMode.current()
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
            .filter { (k, _) -> k != sourceKey && !usedSourceKeys.contains(k) }
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

    /**
     * 跨源升清(2026-10-13,P0-1):播放侧报告"本源已到头"(携带当前实测 S),
     * 详情页判断**是否值得跨源切到画质更高的候选源**,值得则自动执行。
     *
     * <p>## 为什么这是"升清不能跨源"的解法
     * 现有升档([DetailViewModel.switchLineForQuality]、PlaybackRetryDelegate.handleNetworkPlentiful)
     * 的候选被 `sourceKey` 锁死 —— 源A 最高 480P、源B 有 1080P 时系统看不到源B。
     * 本方法把决策半径从"当前源的线路"扩到"所有候选源",复用详情页**已有的**跨源实测数据
     * ([VideoQualityMemory.lookupBestBatch],零网络)。
     *
     * <p>## 与 [refreshCrossSourceHint] 的关系(同一份数据,两种动作)
     * 提示是"问用户要不要切",本方法是"系统自己切"。两者**共用同一判据**
     * ([CrossSourceUpgradePolicy] 与 [CrossSourceHintPolicy] 的 θ/ΔS 完全同口径),
     * 所以不会出现"系统觉得值但只提示不切"的割裂。**已闩掉提示的片不再自动切**:用户明确
     * 表达过"不想被这部片打扰",自动切会违背该意图。
     *
     * <p>## 三条护栏
     * <ol>
     *   <li>**源内优先**:只在 [sourceConverged](本源 sweep 已出结论)后才跨源,
     *       源内还有更高档没试完时不越过(源内换线代价远小于跨源重取详情);</li>
     *   <li>**只升不降 + 拿不准不切**:判据在 [CrossSourceUpgradePolicy],未知尺寸直接不切;</li>
     *   <li>**用户意图优先**:本片已闩掉跨源提示 ⇒ 不自动切。</li>
     * </ol>
     *
     * @param currentSharpness 播放内核报告的当前实测等效清晰度 S;非正 ⇒ 忽略
     */
    private fun tryCrossSourceUpgrade(currentSharpness: Int) {
        if (currentSharpness <= 0) return
        val info = vodInfo ?: return
        val titleKey = searchTitle.ifEmpty { vodName }.ifEmpty { vodId }
        // 与提示同一批前置闸:源内未收敛 / 本片已闩掉 / 正在换源 都不自动切。
        //
        // ⚠️ 2026-10-13 真机修复(时序竞态):播放侧在**源内收敛之前**就会发跨源请求
        // (只要 `untriedHigher==0` 即发,而 sweep 要几秒后才收敛)。若这里直接 return,
        // 请求被丢弃、而播放侧的 `crossSourceRequested` 闩已置真 ⇒ **本集再也不发** ⇒
        // 跨源升清整条链路从不生效(真机取证:20:22:10 发出请求,20:22:35 才收敛,
        // 全程无 echo-xsource decide)。
        // 修法:**未收敛时挂起待办**,等 [markSourceConverged] 时补执行一次。
        if (!sourceConverged) {
            pendingCrossSourceS = currentSharpness
            LOG.i("echo-xsource defer: not converged yet, hold S=" + currentSharpness)
            return
        }
        if (crossHintDismissedKey == titleKey || fallbackActive || fallbackLoadingCandidate) {
            LOG.i("echo-xsource skip: dismissed=" + (crossHintDismissedKey == titleKey) +
                " fallback=" + fallbackActive)
            return
        }
        pendingCrossSourceS = 0
        doCrossSourceUpgrade(currentSharpness, info)
    }

    /**
     * 跨源升清的实际执行体(前置闸已在 [tryCrossSourceUpgrade] 判过)。
     *
     * @param currentSharpness 当前实测清晰度 S
     * @param info             当前详情(避免重复取 [vodInfo])
     */
    private fun doCrossSourceUpgrade(currentSharpness: Int, info: com.github.tvbox.osc.bean.VodInfo) {
        val mode = DeviceCapability.effectiveMode()
        val qualityFirst = mode == DeviceCapability.QualityMode.QUALITY_FIRST
        // 用当前 S 构造一个最小 current Variant:S 是唯一参与判据的字段(见 CrossSourceUpgradePolicy),
        // 宽高只为 known 判定服务 —— 这里已知 S>0,故给一个保守的"已知"占位(1×S² 不可能,用 S×S)。
        val current = VideoQualityPolicy.Variant(
            width = currentSharpness, height = currentSharpness,
            bitrate = 0, confidence = VideoQualityPolicy.Confidence.MEASURED, flag = info.playFlag.orEmpty(),
        )
        // 候选 = 其他源各自的最佳实测(零网络批量查询),过滤口径与 publishSourceChips **完全一致**
        // (排除当前源 + 已用过的源),否则会切到一个不在 chips 里的源,用户看到"凭空换源"
        val candidates = synchronized(fallbackCandidates) { fallbackCandidates.toList() }
            .mapNotNull { video ->
                val k = video.sourceKey.orEmpty()
                val id = video.id.orEmpty()
                if (k.isEmpty() || id.isEmpty()) null else k to id
            }
            .filter { (k, _) -> k != sourceKey && !usedSourceKeys.contains(k) }
            .distinct()
        // ⚠️ 2026-10-13 真机修复(问题 10):候选列表空时**不能静默返回**。
        //
        // 根因:`fallbackCandidates` 只在**聚合搜索回包**时填充,而它是"换源链触发"的产物。
        // 跨源升清是**播放中主动**行为 —— 播放正常时换源链从不触发 ⇒ 候选列表恒空 ⇒
        // 决策链在 `candidates.isEmpty()` 处静默消失(真机取证:三次 replay 后均无 decide)。
        //
        // 修法:候选为空 ⇒ **主动启动聚合搜索**,并把当前 S 重新挂起([pendingCrossSourceS])。
        // 聚合搜索命中到达后(见聚合回包处的 `fallbackCandidates.addAll` 分支)会再次调
        // [tryCrossSourceUpgrade] 决策 —— 那时候选已就绪。
        //
        // 为什么不在本函数里自己造候选:跨源择优必须知道"**对端源该片的 id**"(不同源 id 不同),
        // 这个映射只能由聚合搜索产出。自造候选等于凭空猜测 id,必然查不到画质记忆。
        if (candidates.isEmpty()) {
            LOG.i("echo-xsource skip: no candidates yet, kick aggregate search curS=" + currentSharpness)
            pendingCrossSourceS = currentSharpness
            ensureSourceSearchRunning()
            return
        }
        val bestByTitle = VideoQualityMemory.lookupBestBatch(candidates)
        val pool = ArrayList<CrossSourceUpgradePolicy.Candidate>(candidates.size)
        candidates.forEach { (candKey, candId) ->
            val cand = bestByTitle["$candKey|$candId"] ?: return@forEach
            pool.add(CrossSourceUpgradePolicy.Candidate(candKey, cand))
        }
        // 2026-10-13 真机修复(问题 15/16):候选有、但**全部无实测画质**(pool 空)⇒ 无法择优。
        // 此时触发跨源探测(用硬盘的迷你探测通道把对端源的最佳画质补上),并把 S 挂起 ——
        // 探测回填后 [runMiniProbeForCrossSource] 结尾会 replay 一次决策。真机取证:三次 decide
        // 全是 `candidates=N pool=0 verdict=none`,就是缺这一步。
        // ⚠️ 只在 pool 为空时触发:非空说明数据够了,再探是浪费。
        if (pool.isEmpty()) {
            LOG.i("echo-xsource skip: no measured quality yet, kick probe curS=" + currentSharpness)
            pendingCrossSourceS = currentSharpness
            runMiniProbeForCrossSource()
            return
        }
        val app = App.getInstance()
        val capHeight = if (app == null) 0 else DeviceCapability.capHeight(app)
        val picked = CrossSourceUpgradePolicy.pickCrossSourceUpgrade(
            candidates = pool,
            current = current,
            qualityFirst = qualityFirst,
            sourceConverged = true,
            deviceCapHeight = capHeight,
        )
        LOG.i(
            "echo-xsource decide curS=" + currentSharpness + " candidates=" + candidates.size +
                " pool=" + pool.size + " cap=" + capHeight +
                " verdict=" + (picked?.sourceKey ?: "none") +
                " targetS=" + (picked?.let { VideoQualityPolicy.sharpness(it.variant) } ?: -1)
        )
        // 问题 11:每个返回点都必须有日志(项目铁律"诊断日志不该有守卫")—— 否则决策链
        // 在返回点无声消失,排查只能靠推测。此前这里三处 return 全无日志,本次真机就吃了亏。
        if (picked == null) {
            return
        }
        val video = candidateForKey(picked.sourceKey)
        if (video == null) {
            LOG.i("echo-xsource skip: picked key not resolvable key=" + picked.sourceKey)
            return
        }
        // 跨源切换走既有 switchSource 链路(重搜索 + 重取详情),与用户手动换源完全同路 ——
        // 不新造第二条切换路径,避免两套进度继承/状态复位逻辑漂移。
        switchSource(video)
    }

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

    /**
     * 本轮换源搜索的**同名命中**(源 key → 列表),用于回存 [SearchResultCache](2026-10-13)。
     *
     * <p>只收同名条目:缓存是给"这部片的换源候选"用的,塞进同名无关条目会让下次进入灌进错误候选。
     * 只在主线程读写(事件回调与 viewModelScope 协程都在 Main),无需并发容器。
     */
    private val fallbackSearchHits = HashMap<String, List<Movie.Video>>()

    // P-完整性方案(2026-10-09):当前片的权威参照系与起播/纠偏状态
    // completenessTitleKey/OwnCount 由 recordCompleteness 在详情就绪时写入
    private var completenessTitleKey = ""
    private var completenessOwnSet: Set<Int> = emptySet()
    /** 起播跳过当前源(记忆已知严重不全)后等待换源链结果;链收口无果回退起播当前源 */
    private var pendingCompletenessSkip = false
    /** 播放中纠偏每部片只触发一次,防止候选事件反复引发切换 */
    private var completenessCorrected = false
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

    /**
     * **加载体验预算**:页面停在 Loading 超过这个时长,就必须给用户一个确定画面 ——
     * 要么起播已找到的候选,要么明说"暂无片源"。绝不允许无限转圈。
     *
     * <p>它**独立于网络与看门狗**:看门狗(20~45s)按"站点限时"算,比聚合搜索还短,
     * 且 [onDetailResult] 只要收到本代回包就撤防 —— 哪怕回包是解析失败的空详情
     * (真机实测:js_douban 缺 list 字段那次,看门狗布防 2 次从未触发)。
     * 预算计时器则**不因回包而撤**,只在离开 Loading 时自然失效(到点后先查 pageState)。
     *
     * <p>取 15s 的依据:详情侧并发 12、单源 8s ⇒ 首屏一批 24 源的最坏耗时 ≈ 16s,
     * 预算取整对齐;再短会让"慢但能用"的源来不及回话。
     */
    private val loadingBudget = Runnable { onLoadingBudgetExpired() }

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
        sendCommand(PlaybackCommand.StopForContentSwitch)
        sendCommand(PlaybackCommand.SetEpisodeSheetOpen(false))
        fromCollect = target.fromCollect
        vodName = target.title
        vodPicture = target.picture
        publishHeader()
        loadDetail(target.vodId, target.sourceKey)
        LOG.i("echo-detail-open collect=$fromCollect key=$sourceKey id=$vodId")
        // ⚠️ 2026-10-12:这里**不再**无条件 `startSourceSearch()`。
        //
        // 原实现是 `if (vodName.isNotEmpty()) startSourceSearch()` —— 即"点开卡片的同时
        // 立刻用 12 并发去搜几百个源"。后果(真机实测):
        //   - 详情取数(用户真正在等的那一个请求)要跟几百个搜索请求抢线程与带宽;
        //   - 日志里能看到 `echo-srcban pool kept=350/360` 与大量 SEARCH_TIMEOUT 刷屏,
        //     全部发生在详情还没出来的时候;
        //   - 用户感受就是"点卡片进去变慢了"。
        //
        // 改为走既有的 [prewarmSourceSearch](延迟 1.5s):绝大多数详情在 1.5s 内就 Ready,
        // 于是这轮搜索**根本不会发**;只有当前源确实取不到(或很慢)时,才轮到它兜底 ——
        // 而那条路径本来就是它设计的用途。判定逻辑一行没改,只是把"立刻发"改成"按需发"。
        prewarmSourceSearch()
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
        // 流量节省开启 ⇒ 不发起批量补探测(口径见《选线机制设计》附录 F.6):
        // 升档已被门控关死(见 QualityGovernor.canSwitchUp 的 trafficSaver 参数),
        // 而这条批量探测(N × ≤256KB)的服务对象正是"填升档候选",此时纯属白花流量。
        if (DeviceCapability.trafficSaverOn()) {
            LOG.i("echo-line-probe skip: traffic saver on")
            return
        }
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
                // 走得到这一行却 targets 为空,唯一可能就是 no-direct-url ——
                // 其余分支(lines<=1 / mode-off / all-remembered)都在本行**之前**已经 return。
                // 旧文案把四个原因并列打印,读日志的人无法对号入座(2026-10-10 修正)。
                " (empty ⇒ reason=no-direct-url)"
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
                    probe = { url -> probe.probe(url, headers) },
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
     * <p>**不阻塞首帧**:只在 `TYPE_PLAYBACK_STARTED`(起播成功)之后跑,每片每会话最多一次。
     * 切入时间也不早于 [QualityGovernor.MIN_REMEDY_DELAY_MS] —— 刚看到画面就黑屏一次最刺眼。
     *
     * <p>⚠️ 尚未实现"让路":播放卡顿时暂停扫描,需要把看门狗的劣质信号广播到 VM
     * (现有两条探测路径同样没有,属同类欠账)。
     */
    private fun sweepSourceLinesForQuality() {
        val info = vodInfo ?: return
        val siteKey = sourceKey
        val vod = vodId
        if (siteKey.isEmpty() || vod.isEmpty()) return
        val sweepKey = "$siteKey|$vod"
        if (sourceSweepDoneKey == sweepKey) return
        // 速度优先:用户明确不要这类额外请求(与起播前探测同一口径)
        val mode = DeviceCapability.effectiveMode()
        if (!mode.shouldProbeOnFirstWatch) return
        // 流量节省:本函数的动作**全是向上**的(扫出更高画质就切),整段跳过
        if (DeviceCapability.trafficSaverOn()) {
            LOG.i("echo-quality sweep skip: traffic saver on")
            return
        }
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
        // 达标且非画质优先 ⇒ 不折腾(与"够好即停"同一口径);当前未知则继续扫(要填记忆)
        val currentBefore = remembered.firstOrNull { it.flag == currentFlag }
        if (VideoQualityPolicy.meetsAnchor(currentBefore, anchor) &&
            mode != DeviceCapability.QualityMode.QUALITY_FIRST
        ) {
            LOG.i("echo-quality sweep skip: already meets anchor")
            return
        }
        val nowMs = System.currentTimeMillis()
        // 候选 = 无实测记忆 且 不在负冷却中。
        //
        // **不再有任何条数上限**（旧代码这里 `.take(maxLines)`、深探处 `.take(deepProbeMax)`
        // —— 两个魔法数字合起来把 7 条砍成 2 条）。唯一约束是 SourceSweepBudget.totalMs。
        //
        // 顺序改为**按标签先验降序**（4K/蓝光 这类先探）：预算有限时先花在最可能有收益的线路上，
        // 而不是按站点原序从头取 —— 那样很可能先把预算烧在一堆标清上。
        // ⚠️ 标签先验在这里**只决定"先探谁"**，绝不参与"是否切换"的判定（判定只用实测 S）。
        val missing = siteOrder
            .filter { flag ->
                remembered.none { it.flag == flag } && !probeNegativeCooldownActive(flag, nowMs)
            }
            .sortedByDescending { QualityLabelPolicy.priorityFromLabel(it) }
        if (missing.isEmpty()) {
            LOG.i("echo-quality sweep skip: nothing missing")
            return
        }
        sourceSweepDoneKey = sweepKey
        // 不再按 currentFlag 取集号:统一解析路径自己定位集号(resolveTargetForProbe → probeIndexOf),
        // 因为探测地址与"当前线路"无关(旧代码用当前线路的 index 去取别条线路的 series.url,本身就是近似)
        val headers = probeHeaders(siteKey)
        val probe = VideoQualityProbe()
        val token = detailBuildToken
        LOG.i(
            "echo-quality sweep start missing=" + missing.size + " lines=" + siteOrder.size +
                " cap=" + cap + " mode=" + mode.name
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
            LOG.i("echo-quality deep-probe plan=" + missing.size + " (budget-bound, no quota)")
            val deepProbed = ArrayList<VideoQualityPolicy.Variant>()
            var viaSpider = 0
            var viaDirect = 0
            for (i in missing.indices) {
                val flag = missing[i]
                if (System.currentTimeMillis() - startedAt > SourceSweepBudget.totalMs) {
                    LOG.i("echo-quality deep-probe budget-out skipped=" + (missing.size - i))
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
                if (via == "direct") viaDirect++ else viaSpider++
                LOG.i(
                    "echo-quality deep-probe ok flag=" + flag +
                        " size=" + measured.width + "x" + measured.height + " via=" + via
                )
                deepProbed.add(measured.copy(flag = flag))
            }
            LOG.i("echo-quality deep-probe via spider=" + viaSpider + " direct=" + viaDirect)
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
            val reject = QualityGovernor.rejectReasonForRemedy(mode, currentNow, best, cap, false)
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
                markSourceConverged()
                refreshCrossSourceHint()
                // 提示要有数据才出得来:对端没记忆时按闸门去探一次(默认不发请求,见其 KDoc)
                if (crossSourceHint.value == null) runMiniProbeForCrossSource()
                return@launch
            }
            if (best.flag.isEmpty() || best.flag == currentFlag) {
                markSourceConverged()
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
        // 换片/换源:源内收敛结论与跨源提示都属于上一个会话,必须清零,否则会串到新片
        sourceConverged = false
        pendingCrossSourceS = 0
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
        // ⚠️ 这里**可能什么都不打** —— 那不是"预热没生效",而是搜索早已被别的入口启动过了
        // (例如详情看门狗先判失败、或 [onDetailUnavailable] 已收口)。
        // 那种情况下 [ensureSourceSearchRunning] 的 `if (sourcesSearching.value) return`
        // 守卫直接短路 —— 不打日志,也不重复搜索。
        // 所以判断预热是否生效**不能只看这个前缀**,还要看 [startSourceSearch] 的
        // `echo-source-search start` 时间戳相对 `echo-detail-watchdog-arm` 的差值。
        //
        // 2026-10-12 起本预热是**所有**进入详情路径的统一启动点:
        // [applyTarget] 原先无条件 `startSourceSearch()`,已改为调本方法 ——
        // 于是"点卡片就惊动几百个源"变成"1.5s 内详情就绪则根本不搜"。
        // 详见 [applyTarget] 处的说明。
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
                recordCompleteness(info)
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
                // P-完整性起播决策:记忆已知当前源"严重不全"(如 1/50)⇒ 不起播它,留在 Loading
                // 走换源链(候选已按完整度排序,最全候选先试);链收口无果时回退起播当前源
                // (见 finishFallbackWithoutResult 的 pendingCompletenessSkip 分支)。
                // 权威未知 / 非"严重不全"档:照常秒开,聚合在播放中并行,不受影响。
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
        // ⚠️ 回存必须在**改写 searchTitle 之前**:命中表马上会被清空,而落盘的键取自
        // searchTitle —— 晚一步就写错键(把上一部的命中挂到这一部名下)。
        flushFallbackHitsSave()
        searchTitle = title
        // 新一轮搜索 ⇒ 解冻(上一轮可能因为起播成功被冻结过;那条源后来放不出来时还得靠它)
        searchFrozen = false
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
        //
        // 降权(2026-10-13):上一轮搜索里超时的源排到队尾 —— 此前换源链**只滤封禁、不读降权**,
        // 于是同一个"刚超时 5 次的慢源"在搜索页被排到队尾,在这里却照旧占着前几批的并发额度,
        // 而换源链恰恰是用户已经在等画面的那条路。
        //
        // ⚠️ 2026-10-13 真机修复(问题 3):口径从**二值降权**(penalizedKeys)升级为**连续权重**
        // (SourceWeightMemory,与搜索页 splitFastRoundSourcesByWeight 同一台账)—— 此前两处口径
        // 不一致,会出现"搜索页已把这源降到队尾、换源却仍优先切过去"。判据仍只有一份(权重台账)。
        //
        // ⚠️ 顺序保持"**先 take 截断、再排序**"不变:反过来会让低权重源被上限直接砍掉,
        // 降权就退化成屏蔽(与 SearchBatchPolicy.orderByWeight 的 KDoc 一致,池大小不因本次改动变化)。
        val weightKeys = candidatePool.map { it.key }
        val weightScores = SourceWeightMemory.scoresOf(weightKeys)
        val sources = SearchBatchPolicy.orderByWeight(
            SourceHealthFilter.filter(candidatePool)
                .sortedBy { it.key != home.key }
                .take(fallbackPoolCap),
            homeKey = home.key,
            weightOf = { weightScores[it] ?: SourceWeightPolicy.NEUTRAL },
        )
        // 诊断包靠这行判断"预热到底有没有触发、候选池多大" —— 真机日志被 ROM 屏蔽时,
        // 文件日志(仅 diag 包落盘)是唯一可观测点。前缀 echo-source- 已在 LOG 白名单里。
        // weight-known=N 是"候选池里有多少源已积累权重",与搜索页 echo-searchplan 同口径。
        LOG.i(
            "echo-source-search start title=" + title + " pool=" + sources.size +
                " weight-known=" + weightScores.size + " token=$myToken"
        )
        // ⚠️ 必须先取消旧搜索 job(2026-10-09 真机数据修正):分批后旧 job 要跑完全部批才自然结束,
        // 快速换片时 30 个会话里 27 个在 semaphore(12) 队列里陪跑 —— 它们的回包本就被
        // currentTokenStr() 守卫丢弃,留在队列里纯粹白占并发、拖慢新搜索。cancel 后排队中的
        // async 在挂起点被取消、许可即刻释放(在途网络请求不可中断,但结果会被丢弃,无害)。
        // 批间 abort-stale 检查保留:兜"旧 job 已进批内在途"的情况。
        searchJob?.cancel()
        sourcesSearching.value = sources.isNotEmpty()
        relatedVideos.value = emptyList()
        searchProgress.value = if (sources.isEmpty()) null else SearchProgress(0, sources.size, 0)
        // 历史命中先灌一批候选(2026-10-13)。
        // ⚠️ 必须在调用方(startFallbackIfNeeded → loadNextFallbackCandidate)之前:
        //    它随后取候选时就能直接命中,不必等聚合搜索的首批收口(实测 9.8~10.8s)。
        fallbackSearchHits.clear()
        seedCandidatesFromCache(title)
        if (sources.isEmpty()) return
        searchJob = viewModelScope.launch {
            val doneCount = java.util.concurrent.atomic.AtomicInteger()
            // ⚡ 2026-10-13:**去掉批次屏障**(原 `chunked(SEARCH_BATCH_SIZE)` + 每批 awaitAll)。
            //
            // 真正限制并发的是信号量(12),所以批次大小并不改变"先发谁" —— 前 12 个源先跑;
            // 但第 13 个源必须等**整批(30 个)全部收口**才开始,而慢源要等满 8s 超时
            // ⇒ 命中在 13 名之后的源时,"第一个候选"被硬生生推后 8~16s。
            // (真机实测:点卡片 → 第一个候选 +10.8s/+9.8s,而取流出画面只要 2.6~2.9s。)
            //
            // 现在:全部源一次性入队,由信号量控并发 —— 任一源答话/超时释放额度,排队的下一个
            // **立刻**顶上。候选到达因此只受"最快答话的源"约束,不受批次边界约束。
            // 覆盖不变(池里几个源就搜几个,一个不少),只是不再互相排队等整批。
            //
            // 让路能力不靠批次保留:每个源在**拿额度前后各查一次**[searchActive] ——
            // 换代(换片/换源/重试)与"起播成功冻结"都在这两处生效,排队的任务一到期就自己退出,
            // 不会白打请求(比"等整批跑完"更早让路)。
            coroutineScope {
                sources.mapIndexed { index, bean ->
                    async {
                        if (!searchActive(tokenStr)) return@async
                        semaphore.withPermit {
                            if (!searchActive(tokenStr)) return@withPermit
                            // 优先级最高的前 N 个源用快探超时:它们是为"首屏候选"下注的那批,
                            // 让额度更快回收 ⇒ 后续源更早被投出去。结果晚到仍会经事件通道计入(不丢证据)。
                            val sourceTimeoutMs = SearchBatchPolicy.detailSourceTimeoutMs(
                                index,
                                SearchBatchPolicy.DETAIL_PROBE_SIZE,
                                SearchBatchPolicy.DETAIL_PROBE_TIMEOUT_MS,
                                SOURCE_SEARCH_TIMEOUT_MS,
                            )
                            val done = CompletableDeferred<Unit>()
                            pendingSearchDone.put(bean.key, done)?.complete(Unit)
                            try {
                                withTimeoutOrNull(sourceTimeoutMs) {
                                    withContext(Dispatchers.IO) {
                                        searchCaller.getSearch(bean.key, title, tokenStr)
                                    }
                                    done.await()
                                }
                            } finally {
                                pendingSearchDone.remove(bean.key)
                                // 取消/换代/冻结后的旧 job 不回写进度:免得旧计数覆盖新搜索的 N/M 显示
                                if (searchActive(tokenStr)) {
                                    publishSearchProgress(doneCount.incrementAndGet(), sources.size)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            LOG.i(
                "echo-source-search all-done done=${doneCount.get()}" +
                    " total=${sources.size} frozen=$searchFrozen token=$myToken"
            )
            // 全池/在途波次收口再回存一次(过程中已按合并窗口增量回存,这里兜底)
            if (tokenStr == currentTokenStr() && fallbackSearchHits.isNotEmpty()) {
                saveFallbackHitsToCache()
            }
            // 冻结时状态已由 [freezeFallbackSearch] 收口,这里**不能**再走接管/空态收口:
            // 那两件事是"给还没起播的页面找内容"用的,而冻结的前提正是"画面已经出来了"。
            if (tokenStr == currentTokenStr() && !searchFrozen) {
                sourcesSearching.value = false
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

    /** 搜索进度上报(源级完成即发;候选数带上一份,Loading 态能顺带显示"已找到 K 个来源") */
    private fun publishSearchProgress(done: Int, total: Int) {
        val candidates = synchronized(fallbackCandidates) { fallbackCandidates.size }
        searchProgress.value = SearchProgress(done, total, candidates)
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
            recordFallbackSourceAnswered(data)
            val videos = data.movie?.videoList.orEmpty()
            // 命中回存(2026-10-13):只收**同名**条目,供"同一部片再次进入"秒出候选。
            // 按源覆盖:同一源后到的结果更全(查询变体可能回包多次)。
            val hitSourceKey = data.sourceKey
            if (!hitSourceKey.isNullOrEmpty() && videos.isNotEmpty()) {
                val sameTitle = videos.filter {
                    !it.id.isNullOrEmpty() && TitleMatcher.isSameTitle(it.name, searchTitle)
                }
                if (sameTitle.isNotEmpty()) {
                    fallbackSearchHits[hitSourceKey] = sameTitle
                    // 合并写(见 hitsSaveJob):逐源到达就逐源落盘会让主线程反复序列化整张表;
                    // 但**不落盘**就等于"这次搜过的命中白搜"—— 上一版只在搜索全跑完时写一次,
                    // 用户提前退出/换片就把整轮命中丢了,"同一部片再次进入依旧慢"于是复发。
                    scheduleFallbackHitsSave()
                }
            }
            val fresh = videos.filter {
                !it.id.isNullOrEmpty() && TitleMatcher.isSameTitle(it.name, searchTitle)
                        && !usedSourceKeys.contains(it.sourceKey)
                        && it.sourceKey != sourceKey
            }.filter { candidateKeys.add(candidateKey(it)) }
            if (fresh.isNotEmpty()) {
                synchronized(fallbackCandidates) { fallbackCandidates.addAll(fresh) }
                sortFallbackCandidatesByCompleteness()
                maybeCorrectCompleteness()
                publishSourceChips()
                refreshCrossSourceHint()
                // 跨源升清的候选就绪 ⇒ 补执行挂起的决策(2026-10-13 问题 10 修复:
                // 播放中换源链不触发时,候选只能靠这里"主动启动的聚合搜索"补齐)。
                replayPendingCrossSourceUpgrade("candidates-ready")
                if (shouldAutoTakeOver()) loadNextFallbackCandidate()
            }
            val related = videos.filter {
                !it.id.isNullOrEmpty() && !TitleMatcher.isSameTitle(it.name, searchTitle)
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
    }

    private fun currentTokenStr(): String = "detail_$searchToken"

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
        // 判据抽成纯函数:它很容易被改成"无条件冻结",而那会伤到"起播失败要靠搜索救命"的场景
        if (!SearchBatchPolicy.shouldFreezeOnPlaybackStarted(true, sourcesSearching.value)) return
        if (searchFrozen) return
        searchFrozen = true
        LOG.i("echo-source-search freeze token=$searchToken title=$searchTitle")
        // 状态即时收口:进度条随之下线(在途源的回写已被 [searchActive] 挡住,不会再让它冒出来)
        sourcesSearching.value = false
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
    private fun seedCandidatesFromCache(title: String) {
        val normalized = SearchSettings.normalizedTitle(title)
        if (normalized.isEmpty()) return
        val cached = SearchResultCache.load(normalized)
        if (cached.isEmpty()) return
        val fresh = ArrayList<Movie.Video>()
        for ((_, videos) in cached) {
            for (v in videos) {
                if (v.id.isNullOrEmpty()) continue
                if (!TitleMatcher.isSameTitle(v.name, title)) continue
                if (usedSourceKeys.contains(v.sourceKey)) continue
                if (v.sourceKey == sourceKey) continue
                if (!candidateKeys.add(candidateKey(v))) continue
                fresh.add(v)
            }
        }
        if (fresh.isEmpty()) return
        synchronized(fallbackCandidates) { fallbackCandidates.addAll(fresh) }
        sortFallbackCandidatesByCompleteness()
        publishSourceChips()
        LOG.i("echo-source-search cache-seed title=" + title + " n=" + fresh.size)
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
        if (fallbackSearchHits.isEmpty()) return
        val normalized = SearchSettings.normalizedTitle(searchTitle)
        if (normalized.isEmpty()) return
        SearchResultCache.save(normalized, HashMap(fallbackSearchHits))
    }

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
        // ⚠️ 读 effectiveMode() 而非 current():流量节省开启时按**自动档**口径探测
        // (预算 1s / 3 条),而不是画质优先的 2s / 4 条 —— 见《选线机制设计》附录 F.5。
        val mode = DeviceCapability.effectiveMode()
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
        return EpisodeSetMath.missing(own, CompletenessMemory.confirmedSet(completenessTitleKey))
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
     * 按完整度排序候选(P-完整性方案,2026-10-09):
     * 排序分 = [SourceCompletenessPolicy.rankScore](权威已知时按档位+先验集数,未知时中性)。
     * 稳定排序:同分保留到达序。取候选前重排一次 —— 换源链推进中权威随详情加载单调上涨,
     * 后面的候选会随着信息变多获得更准的排序。
     *
     * <p>2026-10-13(优化方案 C):在"完整度 → 标称画质"之后追加**实测源权重**作最末次键。
     * 与 [SearchBatchPolicy.splitFastRoundSourcesByWeight] 同一份权重台账([SourceWeightMemory]),
     * 使"换源链"与"搜索首轮"对同一个源的判断口径一致 —— 否则会出现
     * "搜索时已把这源降到队尾,换源却仍优先切过去"的自相矛盾。
     *
     * <p>⚠️ 权重**只作最末次键**:完整度(能不能看全)与标称画质(清不清晰)都比"这源历史快不快"
     * 更贴近用户当下诉求;权重只在两者都并列时起 tie-break 作用。且**只重排不剔除** ——
     * 低权重源仍会被试到(与《源与线路的权重模型》"降权≠屏蔽"一致)。
     */
    private fun sortFallbackCandidatesByCompleteness() = synchronized(fallbackCandidates) {
        val snap = CompletenessMemory.snapshot(completenessTitleKey)
        val confirmed = EpisodeSetMath.confirmed(snap.values)
        fallbackCandidates.sortWith(
            compareByDescending<Movie.Video> { video ->
                // 优先用已记录的正片集合(覆盖/连续性口径),无集合退回 note 先验(数量口径)
                val set = snap[video.sourceKey]
                val prior = SourceCompletenessPolicy.priorCountFromNote(video.note)
                if (set != null && confirmed.isNotEmpty()) {
                    SourceCompletenessPolicy.rankScore(
                        SourceCompletenessPolicy.tier(set, confirmed),
                        set.size,
                    )
                } else {
                    SourceCompletenessPolicy.rankScore(
                        SourceCompletenessPolicy.tier(prior, confirmed.size),
                        prior,
                    )
                }
                // P-质量维度 tie-break:同完整度分内,标称高清/4K 的源优先,TC 沉底;
                // 电影(权威未知,全员同分)由此获得画质排序——多集剧集数仍主导
            }.thenByDescending { video ->
                QualityLabelPolicy.priorityFromLabel(video.note)
                // 最末次键:实测源权重(与搜索首轮同一台账),同分并列时让"历史更靠谱的源"优先
            }.thenByDescending { video ->
                SourceWeightMemory.scoreOf(video.sourceKey)
            }
        )
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
            val video = synchronized(fallbackCandidates) {
                if (fallbackCandidates.isEmpty()) null else fallbackCandidates.removeAt(0)
            } ?: break
            val cKey = candidateKey(video)
            if (usedSourceKeys.contains(video.sourceKey) || !triedKeys.add(cKey)) continue
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
        searchProgress.value = null
        // 换源/重扫:新源尚未有收敛结论,跨源提示也必须先撤下,否则会拿着上一个源的结论弹提示
        sourceConverged = false
        pendingCrossSourceS = 0
        crossSourceHint.value = null
        pendingCompletenessSkip = false
        completenessCorrected = false
        // 2026-10-13(问题 17c):切换内容键。**仅当片名真的变了才清**短期失败记忆 ——
        // 换源时片名不变,记忆必须保留(否则"跳过本会话失败过的源"立刻失效);
        // 换片时片名变,记忆清空(另一部片在源A失败不该牵连这一部)。见 PlaybackFailureMemory.beginContent。
        PlaybackFailureMemory.beginContent(searchTitle.ifEmpty { vodName })
        cancelDetailTimeout()
        triedKeys.clear()
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
        val mode = DeviceCapability.QualityMode.current()
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
            withContext(Dispatchers.Main) {
                refreshCrossSourceHint()
                // 2026-10-13 真机修复(问题 15/16):跨源探测回填后**补执行跨源升清决策**。
                // 真机取证(千与千寻等):`echo-xsource decide ... pool=0` —— 候选源有,但都没实测画质,
                // 无法择优。探测(本函数)正是填这个数据的,回填后必须再决策一次,否则永远 pool=0。
                replayPendingCrossSourceUpgrade("probe-done")
            }
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
        // 跨源升清(2026-10-13,P0-1):本源已到头,询问是否跨源自动切到画质更高的候选源。
        // obj = 当前实测 S(int);非正视为拿不到画质,直接忽略(拿不准不切)。
        if (event.type == RefreshEvent.TYPE_CROSS_SOURCE_UPGRADE) {
            val curS = (event.obj as? Int) ?: return
            tryCrossSourceUpgrade(curS)
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
        // 起播成功后启动"源内有界扫描"(见《选线机制设计》附录 G)。**放在这里而非起播前**,
        // 是为了不占用首帧:起播前只做 ≤1~2s 的直链轻探,重活(全源扫描 + 爬虫深探)全部后置。
        // 每片每会话只跑一次(内部有闩),幂等且自带取消域。
        sweepSourceLinesForQuality()
        // 画面已经出来了 ⇒ 后台聚合搜索到此为止(2026-10-13,P1):不再为"可能需要的候选"
        // 把剩下上百个源搜完 —— 那些请求正在跟刚开始缓冲的播放流抢带宽。
        // 是"冻结"不是"取消":在途的照收(证据不丢),而且这条源一旦放不出来,
        // 换源链会重新发起一轮新搜索([ensureSourceSearchRunning] 只看 sourcesSearching)。
        freezeFallbackSearch()
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
