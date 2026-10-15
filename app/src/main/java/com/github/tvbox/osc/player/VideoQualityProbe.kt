package com.github.tvbox.osc.player

import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.parser.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream

/**
 * 轻量画质探测:一次小请求读回**真实**分辨率,不做完整 prepare、不等首帧。
 *
 * <p>成本:一次 [budgetBytes] 以内的读取 + 一次纯解析。10Mbps 下 3~5 条并行也就几十毫秒,
 * 比"串行跑爬虫解析(每条 1~3 秒)"便宜一个数量级 —— 这是"第一次播放就上真实高画质"的关键。
 *
 * <p>⚠️ **安全约束(踩了就是下整个文件)**:有的站不支持 Range,会返回 `200` + 完整响应体。
 * 所以固定只读 [budgetBytes] 就 `close()`;`use{}` 保证异常路径也会关。**绝不能**为了读文件尾部的
 * moov 去 Range 到尾部 —— 那就变成下几 GB 电影了(非 faststart 的 mp4 直接按"探测不到"降级)。
 *
 * <p>双通道读同一个字节流:先按 MP4 解析,不像 MP4 再按 m3u8 文本解析。
 * 嗅探出来的地址常常没有后缀,这样一份数据两条路都能走。
 *
 * <p>## 2026-10-15 改成智能探测
 * 原来只试一种身份、失败就打一行 `probe-failed http=403` 然后放弃。真机代价摆在那儿:
 * 《玩具总动员5》那个源 8 条线路,探测 18 次全 403(日志里 `ua=none`),
 * 实测档一条都攒不出来 ⇒ 画质升级判定永远拿到 `untriedHigher=0` ⇒ 看着像"源内不换线"。
 * 现在按失败性质分别应对(阶梯与冷却见 [ProbeStrategy]):
 * <ul>
 *   <li>403/401 ⇒ 只在**被拒之后**才补头,先补 Referer、仍被拒再连 UA 一起补
 *       (源站已配 Referer/UA 时跳过那一档,绝不拿同一个头再打一次);</li>
 *   <li>5xx / 超时 ⇒ 换头无用,不重试,只给站点一个短冷却(可能只是抖动);</li>
 *   <li>404/410 ⇒ 这条直链过期了,一次都不多试,但**不给站点贴标签**;</li>
 *   <li>200 回 HTML / 没有分辨率 ⇒ 内容不可用,同样不冷却站点;</li>
 *   <li>哪个档成功过就记住,下次同站直接从那一档开始(省掉注定失败的往返)。</li>
 * </ul>
 * 默认档仍是"与播放完全同源的头" —— 这条不能破:探测与播放不同命,整个画质决策就建在
 * 一个播放时根本不会出现的假象上(真机踩过 `UA.random()` 导致探测稳定 404 而播放正常)。
 */
class VideoQualityProbe(private val budgetBytes: Int = DEFAULT_BUDGET_BYTES) {

    companion object {
        /** 256KB:够装下 m3u8 master,也能覆盖绝大多数 faststart mp4 的 moov */
        const val DEFAULT_BUDGET_BYTES = 256 * 1024

        /**
         * 单次探测的硬超时:超时按"探测不到"降级,绝不能拖住起播。
         *
         * <p>⚠️ 名字叫 TIMEOUT,但它**不是** OkHttp 的 callTimeout(这里没往客户端上设),
         * 而是"起播后那条异步小探测"的总预算 —— 由 {@link ResolvedUrlQualityProbe} 用
         * `withTimeoutOrNull(BUDGET_MS)` 实施。真机实测单次探测要 2~3.5 秒,
         * 别把它当成"一次 HTTP 的上限"来用,否则任何重试都会被自己掐死(v1.1.35 就踩了)。
         */
        const val PROBE_TIMEOUT_MS = 800L

        /**
         * 详情页深探那条路的**单条线路预算**(ms):外层是 `SourceSweepBudget.deepProbeTimeoutMs = 6s`,
         * 这里留 500ms 余量给自己收尾,免得阶梯跑到一半被协程取消、连已拿到的结果都丢。
         */
        const val DEFAULT_BUDGET_MS = 5_500L

        /**
         * **分片内探**的读取预算(2026-10-10)。media playlist 里没有分辨率,得去首个分片里解
         * H.264 的 SPS —— 真机实测 SPS 位于视频 payload 第 29 字节,**读 96KB 已足够**;
         * fMP4 的 init 段(moov/stsd)同样在头部几十 KB 内。取 128KB 留余量,
         * 仍比"整片下载"便宜三个数量级。
         */
        private const val SEGMENT_BUDGET_BYTES = 128 * 1024
    }

    /** 一次尝试的产出:要么拿到画质,要么给出"该往哪个方向再试"的分类 */
    private class Attempt(
        val variant: VideoQualityPolicy.Variant?,
        val outcome: ProbeOutcome,
        val httpCode: Int = 0,
    )

    /**
     * @param budgetMs 这一条线路允许花掉的**总**时间(含所有重试档)。默认按详情页深探的预算;
     *        起播后那条小探测要传自己更小的值,别拿默认值去撑爆自己的 withTimeout。
     */
    suspend fun probe(
        url: String?,
        headers: Map<String, String>,
        budgetMs: Long = DEFAULT_BUDGET_MS,
    ): VideoQualityPolicy.Variant? {
        if (url.isNullOrBlank() || !url.startsWith("http")) return null
        return withContext(Dispatchers.IO) {
            // 同一条地址已经有别的探测在跑就退出:两条路径(起播后小探 / 详情页深探)会撞车,
            // 而探测是和播放抢带宽的,做两遍只是多烧一次
            if (!ProbeHostMemory.beginProbe(url)) {
                LOG.i("echo-quality probe-skip reason=in-flight host=" + hostOf(url))
                return@withContext null
            }
            try {
                probeWithinBudget(url, headers, budgetMs)
            } finally {
                ProbeHostMemory.endProbe(url)
            }
        }
    }

    private suspend fun probeWithinBudget(
        url: String,
        headers: Map<String, String>,
        budgetMs: Long,
    ): VideoQualityPolicy.Variant? {
        return withContext(Dispatchers.IO) {
            val client = OkGoHelper.getItvClient() ?: return@withContext null
            val host = hostOf(url)
            val now = android.os.SystemClock.elapsedRealtime()
            if (ProbeHostMemory.isCooling(host, now)) {
                // 冷却中:一条请求都不发。省的是用户带宽,也让"这一波探不通"不去拖累下一波
                LOG.i("echo-quality probe-skip reason=host-cooldown host=$host")
                return@withContext null
            }
            var tactic = ProbeStrategy.firstTactic(ProbeHostMemory.lastSuccessful(host))
            var outcome = ProbeOutcome.NETWORK
            var tries = 0
            var lastAttemptMs = 0L
            while (tries < ProbeStrategy.MAX_ATTEMPTS) {
                tries++
                val effective = applyTactic(headers, tactic, url)
                val attemptStartedAt = android.os.SystemClock.elapsedRealtime()
                val attempt = attemptOnce(client, url, effective, tactic)
                lastAttemptMs = android.os.SystemClock.elapsedRealtime() - attemptStartedAt
                if (attempt.variant != null) {
                    ProbeHostMemory.rememberSuccess(host, tactic)
                    return@withContext attempt.variant
                }
                outcome = attempt.outcome
                val next = ProbeStrategy.nextTactic(
                    tactic,
                    outcome,
                    hasHeader(headers, "User-Agent"),
                    hasHeader(headers, "Referer"),
                ) ?: break
                // 时间闸按**实测的单次耗时**判,不按写死的常数:真机单次探测要 2~3.5 秒,
                // v1.1.35 就是拿 800ms 当上限,结果阶梯一次都没启动(全日志看不到一行 probe-retry)。
                // 按"再打一次是否还赶得上这条线路的预算"决定 ⇒ 慢站自然只跑一档,快站才值得换身份。
                val spentMs = android.os.SystemClock.elapsedRealtime() - now
                if (spentMs + lastAttemptMs > budgetMs) {
                    LOG.i(
                        "echo-quality probe-giveup reason=time-budget spent=" + spentMs +
                            " last=" + lastAttemptMs + " budget=" + budgetMs +
                            " host=" + host + " outcome=" + outcome.name
                    )
                    break
                }
                LOG.i(
                    "echo-quality probe-retry host=" + host + " http=" + attempt.httpCode +
                        " from=" + tactic.name + " to=" + next.name + " spent=" + spentMs
                )
                tactic = next
            }
            val cooldown = ProbeStrategy.coolDownMs(outcome, tries >= ProbeStrategy.MAX_ATTEMPTS)
            if (cooldown > 0L) {
                ProbeHostMemory.coolDown(host, cooldown, now)
                LOG.i("echo-quality probe-cooldown host=" + host + " outcome=" + outcome.name + " ms=" + cooldown)
            }
            null
        }
    }

    /** 单次请求 + 解析(原 probe() 的主体);失败时返回分类,不在这里做重试决定 */
    private fun attemptOnce(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        tactic: ProbeTactic,
    ): Attempt {
        val request = buildRequest(url, headers) ?: return Attempt(null, ProbeOutcome.CONTENT_UNUSABLE)
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // ua= 是必须的:源站常按 UA 放行,404 时必须知道"我们用的是哪个 UA"才判得下去。
                    // 真机踩过:同一地址一次 200、一次 404,而唯一的变量就是 UA(详见 DetailViewModel.probeHeaders)
                    // tactic= 是 2026-10-15 补的:同一个地址会被拿不同身份打几次,
                    // 不打这一档就分不清"源站本来不给"和"我们这一步还没换对头"。
                    //
                    // deny= 是**同一批 403 教出来的第二课**:我们连着两版按"缺 UA"猜,
                    // 真机日志证明补了 UA 仍然 403,而从 PC 拿同一地址不带任何头反倒 200
                    // ⇒ 光有状态码推不出原因。CDN 拒绝时几乎总会在头/体里写为什么
                    // (Server、X-Blocked、"Access denied…referer"),不读就永远在猜。
                    val fu = response.request.url
                    LOG.i(
                        "echo-quality probe-failed http=" + response.code +
                            " ua=" + uaTag(headers) + " tactic=" + tactic.name +
                            " server=" + response.header("Server").orEmpty() +
                            " deny=" + denyHint(response) +
                            " final=" + fu.host + fu.encodedPath
                    )
                    return Attempt(null, ProbeStrategy.classifyHttp(response.code), response.code)
                }
                val stream = response.body?.byteStream()
                if (stream == null) {
                    LOG.i("echo-quality probe-failed reason=null-body tactic=" + tactic.name)
                    return Attempt(null, ProbeOutcome.CONTENT_UNUSABLE, response.code)
                }
                val buffer = readCapped(stream, budgetBytes)
                if (buffer == null) {
                    LOG.i("echo-quality probe-failed reason=empty-body tactic=" + tactic.name)
                    return Attempt(null, ProbeOutcome.CONTENT_UNUSABLE, response.code)
                }
                // 1) MP4:文件里写死的尺寸,唯一完全可信的来源
                val mp4 = Mp4BoxReader.readVideoSize(buffer, buffer.size)
                if (mp4 != null) {
                    LOG.i("echo-quality probe-ok src=mp4 size=" + mp4.first + "x" + mp4.second +
                        " bytes=" + buffer.size)
                    return Attempt(
                        VideoQualityPolicy.Variant(
                            width = mp4.first,
                            height = mp4.second,
                            bitrate = 0,
                            confidence = VideoQualityPolicy.Confidence.MEASURED,
                        ),
                        ProbeOutcome.OK,
                        response.code,
                    )
                }
                // ⚠️ 内容**看起来是 MP4**却没解出尺寸 —— 必须单独留痕(2026-10-10)。
                //
                // 真机踩过:ixigua 一条线路内核从容器报出 1728x720(播放完全正常),
                // 探测却走到下面 m3u8 分支报 `probe-no-size`,日志里只能看到乱码的
                // `head=ftypisom…moov…`,完全看不出"为什么 moov 就在开头却解不出"。
                // 根因是读取器**只读了 tkhd**,而该文件尺寸只在 stsd 采样条目里
                // —— 已在 Mp4BoxReader 补上 stsd 回退(第二优先路径)。
                // 这条日志是为了万一还有别的结构差异时,能一眼看到 box 布局,不必再猜。
                if (Mp4BoxReader.looksLikeMp4(buffer, buffer.size)) {
                    LOG.i(
                        "echo-quality probe-mp4-miss boxes=" +
                            Mp4BoxReader.topLevelTypes(buffer, buffer.size) +
                            " bytes=" + buffer.size
                    )
                }
                // 2) m3u8 master:站点声明的清晰度,比 flag 名可信但不是文件真值
                val text = String(buffer, 0, buffer.size, Charsets.UTF_8)
                val hls = VideoQualityPolicy.parseHlsMaster(text)
                if (hls != null) {
                    LOG.i("echo-quality probe-ok src=hls size=" + hls.width + "x" + hls.height)
                    return Attempt(hls, ProbeOutcome.OK, response.code)
                }
                // 2.5) **分片内探**(2026-10-10):media playlist 里根本没有分辨率字段
                //      (HLS 规范:RESOLUTION 只写在 master 的 EXT-X-STREAM-INF 里),
                //      所以上面那条路**必然**返回 null。改去首个分片里解视频流:
                //      H.264 的 SPS 携带真实宽高,MPEG-TS 打包时它就在第一个视频包里。
                //      这是"整源都是 HLS 子列表"的片源唯一能拿到画质的非播放途径。
                //      带的是**同一套已经生效的头** —— 分片往往比列表更挑 Referer。
                val seg = probeSegmentForSize(
                    client, text, response.request.url.toString(), headers,
                )
                if (seg != null) return Attempt(seg, ProbeOutcome.OK, response.code)
                // ⚠️ 这条是"解析器跑了但没认出来"的唯一可观测点,别删。
                //
                // v1.0.44 扩充:真机实测发现 probe-no-size 有 16 次,而同时用 curl 拉同一个
                // `index.m3u8` 却是 **97 字节的 master、带 RESOLUTION=1920x1040**。
                // 两边对不上 ⇒ 必须把"探测真正请求到了什么"打出来才能判断:
                //   · final= 响应**经过重定向之后**的最终地址(OkHttp 会自动跟随)
                //   · streamInf= 内容里有没有 #EXT-X-STREAM-INF(master 的标志)
                //   · isMaster= 是不是 master(媒体播放列表没有 RESOLUTION,解析不出属正常)
                val finalUrl = response.request.url
                val hasStreamInf = text.contains("#EXT-X-STREAM-INF", ignoreCase = true)
                // orig= 取原地址去掉 query 后的尾部(不含签名参数),与 final= 对比即可看出有无重定向
                val origTail = url.substringBefore('?').takeLast(70)
                LOG.i(
                    "echo-quality probe-no-size src=hls bytes=" + buffer.size +
                        " tactic=" + tactic.name +
                        " streamInf=" + hasStreamInf +
                        " orig=…" + origTail +
                        " final=" + finalUrl.host + finalUrl.encodedPath +
                        " head=" + text.take(60).replace('\n', ' ')
                )
                // 回的是 HTML 页(加密壳 / 反爬页 / 错误页)与"是播放列表但没分辨率"
                // 要分开:前者再试一次同样的东西必然还是废的,后者至少说明地址是活的
                Attempt(null, if (looksLikeHtml(text)) ProbeOutcome.CONTENT_UNUSABLE else ProbeOutcome.NO_SIZE, response.code)
            }
        } catch (t: Throwable) {
            // ⚠️ 必须用 echo-quality 前缀 —— 它已登记进 LOG.FILE_LOG_PREFIXES。
            //
            // 原来这里是 LOG.d("VideoQualityProbe", "probe failed: ...")。坑在于:
            // LOG.d(tag, msg) 内部走 fileLog("D", msg),而落盘白名单是
            // `msg.startsWith(prefix)` —— msg 是 "probe failed: ...",**不以任何前缀开头**,
            // 于是被静默丢弃。真机文件日志里永远看不到探测为什么失败。
            //
            // 实测代价(v1.0.38 现场数据):echo-quality decide 的 probed 字段
            // **28/28 全是 0**,即探测一条都没测出来,而日志里查不到任何原因 ——
            // 只能靠猜。这条日志就是为了终结这种"猜"。
            LOG.i(
                "echo-quality probe-failed m3u8=" + url.contains(".m3u8", ignoreCase = true) +
                    " ex=" + t.javaClass.simpleName + " msg=" + t.message
            )
            // 异常一律按**网络类**处理:连不上/超时/被墙都不是站点的鉴权策略,
            // 换 UA 无用,也不该因此把这个站长期拉黑
            Attempt(null, ProbeOutcome.NETWORK)
        }
    }

    /**
     * 把某一档身份落到请求头上。
     *
     * <p>只**补缺**,绝不覆盖源站自己配的头 —— 源站配的那套才是"播放时真实会用的那套",
     * 把它换掉就等于在用另一个身份下结论(见类 KDoc 的默认档约束)。
     * 已经有的那一档返回原 map,调用方([ProbeStrategy.nextTactic])会直接跳过这一档。
     */
    private fun applyTactic(base: Map<String, String>, tactic: ProbeTactic, url: String): Map<String, String> {
        if (tactic == ProbeTactic.SOURCE_HEADERS) return base
        val out = LinkedHashMap(base)
        if (!hasHeader(out, "Referer") &&
            (tactic == ProbeTactic.REFERER || tactic == ProbeTactic.REFERER_PLUS_UA)
        ) {
            // Referer 取该地址自己的 origin:CDN 挡裸取时多数校验的就是这一项
            originOf(url)?.let { out["Referer"] = it }
        }
        if (!hasHeader(out, "User-Agent") && tactic == ProbeTactic.REFERER_PLUS_UA) {
            // 用一个**固定**的常见浏览器 UA(与字幕抓取同一常量),不用 UA.random():
            // 随机池里抽到什么取决于运气,探测结果就不可复现,失败也就无从对照
            out["User-Agent"] = Utils.UaWinChrome
        }
        return out
    }

    /**
     * **分片内探**:media playlist → 首个分片 → 解真实分辨率(2026-10-10)。
     *
     * <p>为什么非做不可:分辨率只写在 master playlist 的 `#EXT-X-STREAM-INF:RESOLUTION=` 里,
     * 而大量片源的线路直接指向子列表 ⇒ 老路径 100% 失败 ⇒ 自动升档对这类源整体失效。
     *
     * <p>顺序:①加密的**直接放弃**(AES-128 把整个分片包住,SPS 解不出,别白下一次)
     * ②有 `#EXT-X-MAP` 就取 init 段(fMP4,含 moov/stsd) ③否则取首个分片(多为 MPEG-TS)。
     */
    private fun probeSegmentForSize(
        client: OkHttpClient,
        playlist: String,
        playlistUrl: String,
        headers: Map<String, String>,
    ): VideoQualityPolicy.Variant? {
        if (!HlsSegmentProbe.isMediaPlaylist(playlist)) return null
        if (HlsSegmentProbe.isEncrypted(playlist)) {
            LOG.i("echo-quality probe-seg skip: encrypted")
            return null
        }
        val segUrl = HlsSegmentProbe.mapInitUri(playlist, playlistUrl)
            ?: HlsSegmentProbe.firstSegmentUri(playlist, playlistUrl)
            ?: return null
        val size = readSegmentHeader(client, segUrl, headers)
        if (size == null) {
            LOG.i("echo-quality probe-seg miss tail=" + segUrl.substringBefore('?').takeLast(60))
            return null
        }
        LOG.i(
            "echo-quality probe-ok src=segment size=" + size[0] + "x" + size[1] +
                " tail=" + segUrl.substringBefore('?').takeLast(46)
        )
        return VideoQualityPolicy.Variant(
            width = size[0],
            height = size[1],
            bitrate = 0,
            confidence = VideoQualityPolicy.Confidence.MEASURED,
        )
    }

    /** 读首个分片的头部 → `[width, height]`;任何异常/解不出都返回 null(调用方静默降级) */
    private fun readSegmentHeader(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
    ): IntArray? {
        val request = try {
            Request.Builder().url(url)
                // 只读头部:SPS/moov 都在最前面,没必要拖整个分片(分片可达数 MB)
                .header("Range", "bytes=0-${SEGMENT_BUDGET_BYTES - 1}")
                .apply {
                    headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) header(k, v) }
                }
                .get()
                .build()
        } catch (t: Throwable) {
            return null
        }
        return try {
            client.newCall(request).execute().use { response ->
                // 支持 Range 的站回 206;不支持的站直接 200 给全量,同样能用
                if (!response.isSuccessful && response.code != 206) return@use null
                val stream = response.body?.byteStream() ?: return@use null
                val buffer = readCapped(stream, SEGMENT_BUDGET_BYTES) ?: return@use null
                // fMP4 的 init 段(或分片本身是 mp4):moov/stsd 就是尺寸真值,与 MP4 探测同一条路
                val mp4 = Mp4BoxReader.readVideoSize(buffer, buffer.size)
                if (mp4 != null) return@use intArrayOf(mp4.first, mp4.second)
                // MPEG-TS:按**内容**依次试 H.264 / H.265 的 SPS(不信 PMT/URL 声明)
                HlsSegmentProbe.parseTsVideoSize(buffer, buffer.size)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 拒绝原因摘要:先取错误体第一行(CDN 常直接写 "Access denied...referer/banned/UA"),
     * 再看几个常见的拒绝头。**只读 200 字节**(`peekBody` 不动真实响应流),
     * 因为拒绝页可能是几 KB 的 HTML。
     *
     * <p>这条是给"别再猜"用的:前两版都按"缺 UA"猜方向,日志里只有状态码,
     * 结果改了一版仍不明白为什么 403。
     */
    private fun denyHint(response: okhttp3.Response): String {
        val body = try {
            response.peekBody(200).string().lineSequence()
                .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        } catch (t: Throwable) {
            ""
        }
        val heads = listOf(
            "X-Blocked", "X-Forbidden-Reason", "X-Tengine-Error", "WWW-Authenticate", "X-Powered-By",
        ).mapNotNull { name -> response.header(name)?.take(40)?.let { name + "=" + it } }.joinToString(" ")
        val text = if (body.isNotEmpty()) body else heads
        return text.replace('\n', ' ').replace('"', '\'').ifBlank { "-" }.take(90)
    }

    /**
     * UA 摘要:只取头 24 字符 —— 够区分"源站配的 UA"与"随机池抽的",又不至于刷屏。
     * 键名大小写不敏感(`UA.random()` 写 "User-Agent",部分源配置写 "user-agent")。
     */
    private fun uaTag(headers: Map<String, String>): String {
        val ua = headerValue(headers, "User-Agent")
        return if (ua.isNullOrBlank()) "none" else ua.take(24).replace(' ', '_')
    }

    private fun buildRequest(url: String, headers: Map<String, String>): Request? = try {
        Request.Builder().url(url)
            .apply {
                // m3u8 很小且未必支持 Range,直接整份取;其余一律只要文件头
                if (!url.contains(".m3u8", ignoreCase = true)) {
                    header("Range", "bytes=0-${budgetBytes - 1}")
                }
                headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) header(k, v) }
            }
            .get()
            .build()
    } catch (t: Throwable) {
        null
    }

    /** 只读前 [cap] 个字节就停 —— 这是"不支持 Range 的站不会下整个文件"的唯一保证 */
    private fun readCapped(stream: InputStream, cap: Int): ByteArray? {
        val buffer = ByteArray(cap)
        var filled = 0
        while (filled < cap) {
            val n = stream.read(buffer, filled, cap - filled)
            if (n <= 0) break
            filled += n
        }
        if (filled <= 0) return null
        // ⚠️ 必须裁到**真实读到的长度**再返回。
        //
        // 原来直接 `return buffer`:返回的是整个 budgetBytes(256KB)数组,而两个调用方
        // 用的都是 `buffer.size`(= 256KB),于是解析器拿到的是"真实数据 + 一大片未填充的 0 字节":
        //   · MP4 路径尤其致命 —— 它会顺着 0 字节继续往后当 box 读,尺寸基本解析不出来;
        //   · m3u8 路径同样会往文本尾部灌进 NUL,行解析可能被带偏。
        // 而 m3u8 master 通常只有几 KB,所以这个坑几乎每次探测都会命中。
        return if (filled == cap) buffer else buffer.copyOf(filled)
    }
}

/** 头名大小写不敏感(源配置里 "User-Agent" 与 "user-agent" 都有人写) */
private fun hasHeader(headers: Map<String, String>, name: String): Boolean =
    headerValue(headers, name) != null

private fun headerValue(headers: Map<String, String>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) && it.value.isNotBlank() }?.value

/** `https://v5.example.com/a/b/index.m3u8?sign=…` → `https://v5.example.com/` */
private fun originOf(url: String): String? = try {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) null else {
        val rest = url.substring(schemeEnd + 3)
        val cut = rest.indexOf('/').takeIf { it >= 0 } ?: rest.length
        val hostPart = rest.substring(0, cut).substringBefore('?').substringBefore('#')
        if (hostPart.isBlank()) null else url.substring(0, schemeEnd + 3) + hostPart + "/"
    }
} catch (t: Throwable) {
    null
}

/**
 * 只做主机名(冷却与"上次靠哪档成功"都按 host 记,签名参数每次都不一样)。
 * 手工切而不是用 HttpUrl:okhttp5 已把 `HttpUrl.get(String)` 标成废弃,而这里只要 host。
 */
private fun hostOf(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return "unknown"
    val rest = url.substring(schemeEnd + 3)
    val end = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
    return rest.substring(0, end).lowercase().ifBlank { "unknown" }.substringAfterLast('@')
}

/** 回的是 HTML(加密壳 / 反爬页 / 错误页)而不是播放列表或媒体头 */
private fun looksLikeHtml(text: String): Boolean {
    val head = text.take(256).trim().lowercase()
    if (!head.startsWith("<")) return false
    return head.contains("<html") || head.contains("<!doctype html") ||
        head.contains("<script") || head.contains("<body")
}
