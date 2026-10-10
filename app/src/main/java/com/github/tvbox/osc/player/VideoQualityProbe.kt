package com.github.tvbox.osc.player

import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.OkGoHelper
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
 */
class VideoQualityProbe(private val budgetBytes: Int = DEFAULT_BUDGET_BYTES) {

    companion object {
        /** 256KB:够装下 m3u8 master,也能覆盖绝大多数 faststart mp4 的 moov */
        const val DEFAULT_BUDGET_BYTES = 256 * 1024

        /** 单次探测的硬超时:超时按"探测不到"降级,绝不能拖住起播 */
        const val PROBE_TIMEOUT_MS = 800L

        /**
         * **分片内探**的读取预算(2026-10-10)。media playlist 里没有分辨率,得去首个分片里解
         * H.264 的 SPS —— 真机实测 SPS 位于视频 payload 第 29 字节,**读 96KB 已足够**;
         * fMP4 的 init 段(moov/stsd)同样在头部几十 KB 内。取 128KB 留余量,
         * 仍比"整片下载"便宜三个数量级。
         */
        private const val SEGMENT_BUDGET_BYTES = 128 * 1024
    }

    /**
     * @return 实测画质;探测不到 / 不支持 / 异常一律 null(调用方静默降级)
     */
    suspend fun probe(url: String?, headers: Map<String, String>): VideoQualityPolicy.Variant? {
        if (url.isNullOrBlank() || !url.startsWith("http")) return null
        return withContext(Dispatchers.IO) {
            val client = OkGoHelper.getItvClient() ?: return@withContext null
            val request = buildRequest(url, headers) ?: return@withContext null
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // ua= 是必须的:源站常按 UA 放行,404 时必须知道"我们用的是哪个 UA"才判得下去。
                        // 真机踩过:同一地址一次 200、一次 404,而唯一的变量就是 UA(详见 DetailViewModel.probeHeaders)
                        val fu = response.request.url
                        LOG.i(
                            "echo-quality probe-failed http=" + response.code +
                                " ua=" + uaTag(headers) +
                                " final=" + fu.host + fu.encodedPath
                        )
                        return@use null
                    }
                    val stream = response.body?.byteStream()
                    if (stream == null) {
                        LOG.i("echo-quality probe-failed reason=null-body")
                        return@use null
                    }
                    val buffer = readCapped(stream, budgetBytes)
                    if (buffer == null) {
                        LOG.i("echo-quality probe-failed reason=empty-body")
                        return@use null
                    }
                    // 1) MP4:文件里写死的尺寸,唯一完全可信的来源
                    val mp4 = Mp4BoxReader.readVideoSize(buffer, buffer.size)
                    if (mp4 != null) {
                        LOG.i("echo-quality probe-ok src=mp4 size=" + mp4.first + "x" + mp4.second +
                            " bytes=" + buffer.size)
                        return@use VideoQualityPolicy.Variant(
                            width = mp4.first,
                            height = mp4.second,
                            bitrate = 0,
                            confidence = VideoQualityPolicy.Confidence.MEASURED,
                        )
                    }
                    // ⚠️ 内容**看起来是 MP4** 却没解出尺寸 —— 必须单独留痕(2026-10-10)。
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
                    // 2.5) **分片内探**(2026-10-10):media playlist 里根本没有分辨率字段
                    //      (HLS 规范:RESOLUTION 只写在 master 的 EXT-X-STREAM-INF 里),
                    //      所以上面那条路**必然**返回 null。改去首个分片里解视频流:
                    //      H.264 的 SPS 携带真实宽高,MPEG-TS 打包时它就在第一个视频包里。
                    //      这是"整源都是 HLS 子列表"的片源唯一能拿到画质的非播放途径 ——
                    //      真机实测对这类片源 9 条线路探出 0 条,自动升档完全失效。
                    if (hls == null) {
                        val seg = probeSegmentForSize(client, text, response.request.url.toString(), headers)
                        if (seg != null) return@use seg
                    }
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
                        "echo-quality probe-" + (if (hls == null) "no-size" else "ok") +
                            " src=hls bytes=" + buffer.size +
                            " streamInf=" + hasStreamInf +
                            " orig=…" + origTail +
                            " final=" + finalUrl.host + finalUrl.encodedPath +
                            " head=" + text.take(60).replace('\n', ' ')
                    )
                    hls
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
                null
            }
        }
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
                // MPEG-TS:解 PAT→PMT→视频 PID→SPS
                HlsSegmentProbe.parseTsH264Size(buffer, buffer.size)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * UA 摘要:只取头 24 字符 —— 够区分"源站配的 UA"与"随机池抽的",又不至于刷屏。
     * 键名大小写不敏感(`UA.random()` 写 "User-Agent",部分源配置写 "user-agent")。
     */
    private fun uaTag(headers: Map<String, String>): String {
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
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
