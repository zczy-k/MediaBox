package com.github.tvbox.osc.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.github.tvbox.osc.base.App
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 版本更新检查 + 下载安装。
 *
 * <p>数据源是 GitHub Releases 的 latest 接口(无需 token,公开仓库即可)。只做"提示 + 拉起系统
 * 安装器",不静默安装;网络不通时由调用方给出统一文案。
 */
object UpdateChecker {

    /** 发布仓库;以后换仓库只改这一处 */
    const val REPO = "zczy-k/MediaBox"

    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    private const val UA = "MediaBox"

    private const val APK_NAME = "MediaBox_update.apk"

    data class Release(val version: String, val apkUrl: String, val pageUrl: String)

    /** onResult(null) = 已是最新;onError = 网络/解析失败 */
    fun check(onResult: (Release?) -> Unit, onError: (Throwable) -> Unit) {
        val client = OkGoHelper.getDefaultClient() ?: OkHttpClient()
        val request = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", UA)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onError(e)

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            onError(IOException("HTTP ${resp.code}"))
                            return
                        }
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val tag = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
                        val page = json.optString("html_url")
                        var apkUrl = ""
                        val assets = json.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val item = assets.optJSONObject(i) ?: continue
                                if (item.optString("name").endsWith(".apk", ignoreCase = true)) {
                                    apkUrl = item.optString("browser_download_url")
                                    break
                                }
                            }
                        }
                        val current = DefaultConfig.getAppVersionName(App.getInstance()).orEmpty()
                        val newer = tag.isNotEmpty() && isNewer(tag, current)
                        onResult(if (newer) Release(tag, apkUrl, page) else null)
                    }
                } catch (t: Throwable) {
                    onError(t)
                }
            }
        })
    }

    /** 逐段数字比较;任一段解析不出数字就按 0 处理,避免把无法识别的 tag 误判成新版本 */
    fun isNewer(remote: String?, current: String?): Boolean {
        if (remote.isNullOrBlank()) return false
        val a = remote.split('.')
        val b = (current ?: "").split('.')
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (x != y) return x > y
        }
        return false
    }

    /** 下载到应用缓存目录;任何 IO/HTTP 异常都走 onFailed(调用方据此提示网络错误) */
    fun download(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit,
        onFailed: (Throwable?) -> Unit,
        onReady: (File) -> Unit,
    ) {
        if (url.isBlank()) {
            onFailed(IOException("no apk asset"))
            return
        }
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, APK_NAME)
        if (target.exists()) target.delete()
        val client = OkGoHelper.getDefaultClient() ?: OkHttpClient()
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onFailed(e)

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            onFailed(IOException("HTTP ${resp.code}"))
                            return
                        }
                        val body = resp.body
                        if (body == null) {
                            onFailed(IOException("empty body"))
                            return
                        }
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var written = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    written += read
                                    if (total > 0) onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
                                }
                                output.flush()
                            }
                        }
                        if (target.length() <= 0L) {
                            onFailed(IOException("empty file"))
                        } else {
                            onReady(target)
                        }
                    }
                } catch (t: Throwable) {
                    onFailed(t)
                }
            }
        })
    }

    /** 拉起系统安装器(需 REQUEST_INSTALL_PACKAGES,清单已声明) */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
