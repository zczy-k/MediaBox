package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.theme.MediaBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import com.github.tvbox.osc.util.LOG

/**
 * 通用 Web 交互页:**承载 spider 自己生成的 HTML 页面**(典型用途 = 网盘扫码登录)。
 *
 * <p>## 为什么需要它
 * spider 是纯服务端逻辑,没有任何原生 UI 能力(见 `Spider.java` / `SpiderApi.java` —— 全部方法
 * 返回 String/JSONObject,唯一的"外部能力"钩子是 `webParse`)。
 * 但 spider **能通过 `proxy?do=xxx` 这个本地 HTTP 端点自己吐出一整个 HTML 页面** ——
 * 这就是 TVBox 生态网盘登录的标准做法:二维码是 **spider 用 HTML 画的**,不是 App 画的。
 *
 * <p>于是 App 侧唯一缺的、也是本页提供的:**一个显示该 HTML 的容器**。
 *
 * <p>## 调用方
 * [com.github.tvbox.osc.player.PlaybackFetch] —— 取流结果里带 `needWebPage` 标记时,
 * 不再把地址交给播放器,改为打开本页。判据与打标点见
 * [com.github.tvbox.osc.sourcedata.PlayLoader.normalizePlayerResult]。
 *
 * <p>## 网盘扫码必需的 WebView 配置(缺一不可)
 * <ul>
 *   <li>`javaScriptEnabled` —— 登录页大量用 JS 渲染二维码与轮询登录态;</li>
 *   <li>`domStorageEnabled` —— 页面用 localStorage 存中间态,关掉会白屏;</li>
 *   <li>**第三方 Cookie**(`setAcceptThirdPartyCookies`)—— 网盘登录态的载体;
 *       默认在 API 21+ 是关的,开了才能收到 Set-Cookie;</li>
 *   <li>`CookieManager.setAcceptCookie(true)` —— 同上。</li>
 * </ul>
 *
 * <p>## 登录态存在哪
 * **不由本页管**。spider 自己把 token/Cookie 写进它的存储(`KV` 的 `cache_` 前缀 ——
 * `KvDataKind.kt` 已把该前缀归类为 `USER` 数据、清缓存不删,正是为存登录态准备的)。
 * 本页只负责"把页面显示出来",这样与具体网盘解耦。
 */
class WebInteractionActivity : BaseActivity() {

    companion object {
        /** 传入要加载的地址(spider 的 proxy 端点,或任意 http(s) 页面) */
        private const val EXTRA_URL = "url"
        /** 顶部标题(可选);为空则用页面 title */
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, url: String, title: String? = null) {
            if (url.isEmpty()) return
            context.startActivity(
                Intent(context, WebInteractionActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TITLE, title)
            )
        }
    }

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    /** 本页要显示完整网页(含二维码),不隐藏系统栏 —— 用户需要看到地址与页面元素 */
    override fun hideSysBar() {}

    override fun init() {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (url.isEmpty()) {
            // 没有地址 = 无从加载:直接收口,不留一个空页面给用户
            LOG.i("echo-webpage open-abort reason=empty-url")
            finish()
            return
        }
        LOG.i("echo-webpage open title=" + title + " url=" + maskUrl(url))
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            MediaBoxTheme {
                WebInteractionPage(
                    url = url,
                    initialTitle = title,
                    onClose = { finish() },
                )
            }
        }
    }

    /**
     * 日志用的地址脱敏:proxy 端点常带 token / 账号参数,原样落盘会泄进日志文件。
     * 只保留 origin + path,query 整体抹掉。
     */
    private fun maskUrl(url: String): String = try {
        val u = Uri.parse(url)
        val path = u.path.orEmpty()
        val hasQuery = !u.query.isNullOrEmpty()
        (u.scheme ?: "?") + "://" + (u.host ?: "?") + path + (if (hasQuery) "?<masked>" else "")
    } catch (th: Throwable) {
        "<unparsable>"
    }
}

/**
 * 页面本体:一个顶部信息条 + 全屏 WebView。
 *
 * <p>顶部条存在的理由:登录页往往需要用户在**扫码后**看着提示(如"登录成功/请重试"),
 * 也需要一个明确的「关闭」出口 —— WebView 里如果页面自己没给返回按钮,用户会困在页里。
 * 同时显示当前域名,让用户能确认"我确实在网盘的官方登录页"。
 */
@Composable
private fun WebInteractionPage(
    url: String,
    initialTitle: String,
    onClose: () -> Unit,
) {
    var pageTitle by remember { mutableStateOf(initialTitle) }
    var progress by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val host = remember { Uri.parse(url).host.orEmpty() }

    // 系统返回:能回退就回退页面历史,否则关页(用户预期:返回键先退网页)
    BackHandler(enabled = true) {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- 顶部条:关闭 / 标题 / 域名 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.web_interaction_close)) }
        }
        if (host.isNotEmpty()) {
            Text(
                text = host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (pageTitle.isNotEmpty()) {
            Text(
                text = pageTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- WebView 本体 ----
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            // 网盘登录页常要求移动端 UA;用默认 UA 也可能被拒,故保留系统默认
                            cacheMode = WebSettings.LOAD_DEFAULT
                            // 允许混合内容:部分网盘页仍走 http 资源,不开会白屏
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }
                        // 第三方 Cookie 是网盘登录态的载体:API 21+ 默认关闭,必须显式打开
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                                progress = 1
                            }

                            override fun onPageFinished(view: WebView?, u: String?) {
                                progress = 100
                                view?.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                            }

                            // 保持在页内跳转(不交给外部浏览器) —— 登录流程的多次跳转不能中断
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = false
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, t: String?) {
                                t?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                            }
                        }
                        webView = this
                        loadUrl(url)
                    }
                },
                update = { wv -> webView = wv },
            )
            if (progress in 0..0) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}
