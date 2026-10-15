package com.github.tvbox.osc.player

import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.util.LOG
import org.greenrobot.eventbus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 画质探测的第二条入口:**复用取流解析的结果**,见 [probeAsync]。
 *
 * <p>与 [DetailViewModel] 里的"打开面板时预探测"是**互补**而非替代关系:
 * 预探测只碰直连型线路(几百毫秒的小请求),而爬虫型线路的地址必须先跑
 * `playerContent` 才有 —— 那一步 1~3 秒且 [com.github.tvbox.osc.sourcedata.PlayLoader]
 * **完全没有缓存**,主动去爬等于凭空多付 4 次解析。这个入口改在"解析反正要跑"的那一刻
 * 顺手测:该跑的解析照跑(总请求数不增加),只多挂一个 ≤256KB 的小请求,
 * 且完全发生在起播之后,不占用起播时间。
 *
 * <p>所以画质标签的诚实口径变成:直连型线路当场就有;爬虫型线路**看过一次之后**才有,
 * 第二次打开这部片时全部线路都带画质。这不是妥协 —— 没解析过的线路就是没数据,
 * 编一个出来只会让用户点进去发现不对。
 *
 * <p>独立作用域而非页面作用域:这次探测与播放会话无关,页面销毁也该跑完,
 * 否则"退出详情页就没画质"会让积累永远攒不起来。
 */
object ResolvedUrlQualityProbe {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 这条小探测的预算(= 传给 [VideoQualityProbe.probe] 的 budgetMs,同时也是外层 withTimeout)。
     *
     * <p>原来是 `PROBE_TIMEOUT_MS + 400 = 1.2 秒`,而真机实测**单次探测**要 2~3.5 秒
     * (v1.1.35 日志里 4 条线路的 spent 分别是 2071 / 2439 / 3043 / 3504 毫秒)
     * ⇒ 这条路径基本每次都被自己的超时掐死,难怪实测档一直攒不起来。
     * 现在给足"一次完整尝试 + 余量",仍然比详情页深探(6 秒)便宜,
     * 且它跑在独立作用域、起播之后,不占任何起播时间。
     */
    private const val BUDGET_MS = 4_000L

    /**
     * 异步测一次 [url] 的画质并写进 [VideoQualityMemory]。**任何失败都静默** ——
     * 它纯属锦上添花,绝不能影响正在进行的播放。
     *
     * <p>调用点:[com.github.tvbox.osc.player.PlaybackFetch] 拿到取流结果、真正要起播的那一刻。
     *
     * @param headers 该线路的取流请求头(部分 CDN 缺 UA 直接 403);可为 null
     */
    @JvmStatic
    fun probeAsync(
        siteKey: String?,
        vodId: String?,
        flag: String?,
        url: String?,
        headers: Map<String, String>?,
    ) {
        // 参数全用可空类型:唯一调用方是 Java(PlaybackFetch),那边 `String flag` 可能为
        // null(info 里没 flag 时 optString 返回空串、而 flag 字段本身可缺省),
        // 非空 Kotlin 参数遇到 Java 传来的 null 会直接抛 NPE,在播放链路里炸掉。
        val site = siteKey.orEmpty()
        val vod = vodId.orEmpty()
        val line = flag.orEmpty()
        val target = url.orEmpty()
        if (site.isEmpty() || vod.isEmpty() || line.isEmpty()) return
        // 探测自身也会校验,这里先挡掉非 http 的地址(解析链里的中间地址、数据 URI 等),省一次协程调度
        if (!target.startsWith("http://") && !target.startsWith("https://")) return
        scope.launch {
            try {
                // 内核已实测过就别探了:探出来的值最多只是同档声明,不值得覆盖文件里的真值
                val known = VideoQualityMemory.lookup(site, vod, line)
                if (known != null && known.confidence == VideoQualityPolicy.Confidence.MEASURED) return@launch
                val measured = withTimeoutOrNull(BUDGET_MS) {
                    // 把自己的预算告诉探测本身:它据此决定"还试不试下一档身份",
                    // 不然阶梯会跑到一半被上面的 withTimeout 整条取消,连第一次的结果都拿不到
                    VideoQualityProbe().probe(target, headers ?: emptyMap(), BUDGET_MS)
                } ?: return@launch
                if (!measured.known) return@launch
                VideoQualityMemory.record(site, vod, measured.copy(flag = line))
                LOG.i("echo-quality resolved: " + line + " -> " + measured.width + "x" + measured.height)
                // ⚠️ 这条广播不能少:本对象是**独立作用域**(页面销毁也跑完,见类 KDoc),
                // 写完记忆若不通知详情页,「线路N · 1080P」在本次会话里就刷不出来 ——
                // 详情页的线路标签只读 [DetailViewModel.lineQualityHeights],
                // 而那条 StateFlow 只在 publishLineQualityHeights() 时才重算。
                // v1.0.26 真机实测踩过:记忆里有值、界面仍是纯「线路1」。
                EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_LINE_QUALITY_MEASURED))
            } catch (t: Throwable) {
                LOG.d("ResolvedUrlQualityProbe", "probe failed: " + t.message)
            }
        }
    }
}