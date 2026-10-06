package com.github.tvbox.osc.player

import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.OkGoHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                    if (!response.isSuccessful) return@use null
                    val stream = response.body?.byteStream() ?: return@use null
                    val buffer = readCapped(stream) ?: return@use null
                    // 1) MP4:文件里写死的尺寸,唯一完全可信的来源
                    val mp4 = Mp4BoxReader.readVideoSize(buffer, buffer.size)
                    if (mp4 != null) {
                        return@use VideoQualityPolicy.Variant(
                            width = mp4.first,
                            height = mp4.second,
                            bitrate = 0,
                            confidence = VideoQualityPolicy.Confidence.MEASURED,
                        )
                    }
                    // 2) m3u8 master:站点声明的清晰度,比 flag 名可信但不是文件真值
                    val text = String(buffer, 0, buffer.size, Charsets.UTF_8)
                    VideoQualityPolicy.parseHlsMaster(text)
                }
            } catch (t: Throwable) {
                LOG.d("VideoQualityProbe", "probe failed: " + t.message)
                null
            }
        }
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

    /** 只读前 [budgetBytes] 个字节就停 —— 这是"不支持 Range 的站不会下整个文件"的唯一保证 */
    private fun readCapped(stream: InputStream): ByteArray? {
        val buffer = ByteArray(budgetBytes)
        var filled = 0
        while (filled < budgetBytes) {
            val n = stream.read(buffer, filled, budgetBytes - filled)
            if (n <= 0) break
            filled += n
        }
        return if (filled > 0) buffer else null
    }
}
