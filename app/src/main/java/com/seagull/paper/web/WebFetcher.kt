package com.seagull.paper.web

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.seagull.paper.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 用 App 内置 WebView 引擎抓页面。
 *
 * 为什么必须这么干：Google 学术这类站对陌生 HTTP 客户端会返回「JS 空壳页」——
 * HTML 里根本没有结果，必须真的执行 JS 才渲染出来；而且它认浏览器指纹和 Cookie。
 * WebView 是同一台手机上的真 Chromium：会跑 JS、指纹真实，而且能和阅读器共用
 * Cookie —— 你在阅读器里手动过一次人机验证，之后这里就能自动抓了。
 */
object WebFetcher {

    private val main = Handler(Looper.getMainLooper())

    suspend fun fetchHtml(
        app: Application,
        url: String,
        settleMs: Long = 2200L,
        timeoutMs: Long = 25000L,
        desktopUa: Boolean = true
    ): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val wv = WebView(app)
            var done = false

            fun finish(html: String) {
                if (done) return
                done = true
                try {
                    wv.stopLoading()
                    wv.destroy()
                } catch (e: Exception) {
                    // ignore
                }
                if (cont.isActive) cont.resume(html)
            }

            try {
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.databaseEnabled = true
                wv.settings.loadsImagesAutomatically = false
                wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
                if (desktopUa) wv.settings.userAgentString = Net.DESKTOP_UA
            } catch (e: Exception) {
                // 老设备上个别设置项会失败，忽略
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u: String) {
                    view.postDelayed({
                        try {
                            view.evaluateJavascript(
                                "(function(){try{return document.documentElement?document.documentElement.outerHTML:''}catch(e){return ''}})()"
                            ) { result ->
                                finish(decodeJsString(result))
                            }
                        } catch (e: Exception) {
                            finish("")
                        }
                    }, settleMs)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) finish("")
                }
            }

            main.postDelayed({ finish("") }, timeoutMs)
            cont.invokeOnCancellation { main.post { finish("") } }

            try {
                wv.loadUrl(url)
            } catch (e: Exception) {
                finish("")
            }
        }
    }

    /** evaluateJavascript 回的是 JSON 字符串字面量，要还原成原文。 */
    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return try {
            val v = JSONTokener(raw).nextValue()
            v as? String ?: ""
        } catch (e: Exception) {
            raw.trim('"')
        }
    }
}
