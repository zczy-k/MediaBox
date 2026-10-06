package com.github.tvbox.osc.player

import com.github.tvbox.osc.util.LOG
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

    /** 硬超时:比探测自身的 [VideoQualityProbe.PROBE_TIMEOUT_MS] 多留一点余量给调度 */
    private const val BUDGET_MS = VideoQualityProbe.PROBE_TIMEOUT_MS + 400L

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
                    VideoQualityProbe().probe(target, headers ?: emptyMap())
                } ?: return@launch
                if (!measured.known) return@launch
                VideoQualityMemory.record(site, vod, measured.copy(flag = line))
                LOG.i("echo-quality resolved: " + line + " -> " + measured.width + "x" + measured.height)
            } catch (t: Throwable) {
                LOG.d("ResolvedUrlQualityProbe", "probe failed: " + t.message)
            }
        }
    }
}