package com.github.tvbox.osc.ui.components

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.github.tvbox.osc.util.LOG
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient

object VodImages {

    private const val PIC_HTTP_CACHE_MB = 250L
    private var picCacheDir: File? = null

    fun init(context: Context) {
        picCacheDir = File(context.cacheDir, "pic_http_cache")
        // 海报只留一层磁盘缓存(OkHttp 250MB):Coil 自带的磁盘缓存默认会另存一份,
        // 此处全局禁用它的磁盘写读,网络层统一走 picClient 的 OkHttp 缓存。
        // 用 CachePolicy(请求级默认策略)而非 Builder.diskCache(null):前者是 Coil 3 公开稳定的请求 API,
        // 后者依赖 Builder 具体签名,版本差异易编译失败。内存缓存保留,划屏流畅度不受影响。
        SingletonImageLoader.setSafe { appContext ->
            ImageLoader.Builder(appContext)
                .components {
                    add(com.github.tvbox.osc.util.CoilBridge.okhttpFetcher { picClient() })
                }
                .diskCachePolicy(CachePolicy.DISABLED)
                .crossfade(true)
                .build()
        }
    }

    private fun picClient(): OkHttpClient = OkHttpClient.Builder()
        .cache(picCacheDir?.let { Cache(it, PIC_HTTP_CACHE_MB * 1024L * 1024L) })
        .addInterceptor(picHeaderInterceptor)
        .addNetworkInterceptor(picForceCacheInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val picForceCacheInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val cc = response.header("Cache-Control")
        if (cc != null && (cc.contains("no-store") || cc.contains("no-cache") || cc.contains("max-age=0"))) {
            response.newBuilder()
                .removeHeader("Cache-Control")
                .removeHeader("Pragma")
                .header("Cache-Control", "public, max-age=604800")
                .build()
        } else {
            response
        }
    }

    private val picHeaderInterceptor = Interceptor { chain ->
        val request = chain.request()
        val parsed = parseVodPicUrl(request.url.toString())
        if (parsed == null) {
            chain.proceed(request)
        } else {
            val (url, headers) = parsed
            val newRequest = request.newBuilder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            chain.proceed(newRequest)
        }
    }

    private fun parseVodPicUrl(raw: String): Pair<String, Map<String, String>>? {
        if (raw.startsWith("data:") || !raw.contains('@')) return null

        fun grab(key: String): String? =
            if (raw.contains("$key=")) raw.split("$key=")[1].split("@")[0] else null

        val url = raw.split("@")[0]
        if (url.isEmpty()) return null

        val headers = LinkedHashMap<String, String>()
        fun put(k: String, v: String?) {
            if (!v.isNullOrEmpty()) headers[k] = v
        }
        grab("@Headers")?.let { json ->
            try {
                val decoded = URLDecoder.decode(json, "UTF-8")
                val obj = Gson().fromJson(decoded, JsonObject::class.java)
                for (key in obj.keySet()) put(key, obj.get(key).asString)
            } catch (ignored: Throwable) {
                LOG.d("VodImages", "pic url @Headers decode failed, skip extra headers")
            }
        }
        put("Cookie", grab("@Cookie"))
        put("User-Agent", grab("@User-Agent"))
        put("Referer", grab("@Referer"))
        return url to headers
    }
}
