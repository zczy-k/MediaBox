package com.github.tvbox.osc.util

import java.io.IOException
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

/**
 * 播放直链的连接预热:解析出直链后对目标 host 发一次 HEAD,把 TCP+TLS 连接放进 ItvClient 连接池,
 * 起播(播放数据源用同一个池)时直接复用。只建连不下载数据;失败静默。
 */
object Preconnect {
    private const val MAX_TRACKED_HOSTS = 64

    /** 已预热 host:同一 host 只发一次,避免选集来回切时产生重复请求 */
    private val warmedHosts: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val warmedCount = AtomicInteger()

    @JvmStatic
    fun warm(url: String?, headers: Map<String, String>?) {
        val link = url?.takeIf { it.isNotBlank() } ?: return
        val host = hostOf(link) ?: return
        if (!warmedHosts.add(host)) return
        if (warmedCount.incrementAndGet() > MAX_TRACKED_HOSTS) {
            warmedHosts.clear()
            warmedCount.set(1)
            warmedHosts.add(host)
        }
        val client = OkGoHelper.getItvClient() ?: return
        try {
            val builder = Request.Builder().url(link).head()
            headers?.forEach { (name, value) ->
                if (!name.isNullOrBlank() && !value.isNullOrBlank()) builder.header(name, value)
            }
            client.newCall(builder.build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // 预热失败不影响起播(起播时自会再建连)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
            LOG.i("echo-preconnect: $host")
        } catch (t: Throwable) {
            LOG.e("echo-preconnect failed: " + t.message)
        }
    }

    private fun hostOf(url: String): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return try {
            val host = URI(url).host ?: return null
            if (host == "127.0.0.1" || host == "localhost" || host == "::1") null else host
        } catch (t: Throwable) {
            null
        }
    }
}
