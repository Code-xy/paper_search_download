package com.seagull.paper.web

import android.app.Application
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.seagull.paper.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 用 App 内置 WebView 引擎抓页面。
 *
 * 为什么必须这么干：Google 学术这类站对陌生 HTTP 客户端会返回「JS 空壳页」——
 * HTML 里没有结果，必须真的执行 JS 才渲染出来；它还认浏览器指纹和 Cookie。
 * WebView 就是同一台手机上的真 Chromium：会跑 JS、指纹真实，还能和阅读器共用
 * Cookie —— 你在阅读器里手动过一次人机验证，之后这里就能自动抓。
 *
 * 实现要点：WebView 只建一次并复用（建一次要几百毫秒），用 Mutex 串行化，
 * 任何异常都吞掉返回空串，绝不让它把进程带崩。
 */
object WebFetcher {

    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()

    @Volatile
    private var cached: WebView? = null

    suspend fun fetchHtml(
        app: Application,
        url: String,
        settleMs: Long = 2200L,
        timeoutMs: Long = 25000L,
        desktopUa: Boolean = true
    ): String = mutex.withLock {
        try {
            withContext(Dispatchers.Main) { load(app, url, settleMs, timeoutMs, desktopUa) }
        } catch (e: Throwable) {
            ""
        }
    }

    private suspend fun load(
        app: Application,
        url: String,
        settleMs: Long,
        timeoutMs: Long,
        desktopUa: Boolean
    ): String = suspendCancellableCoroutine { cont ->
        var web: WebView? = null
        var done = false

        fun dispose(keep: Boolean) {
            val w = web ?: return
            if (!keep) {
                try {
                    w.stopLoading()
                    w.destroy()
                    if (cached === w) cached = null
                } catch (e: Exception) {
                    cached = null
                }
            } else {
                try {
                    w.stopLoading()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        fun finish(html: String, keep: Boolean) {
            if (done) return
            done = true
            dispose(keep)
            if (cont.isActive) cont.resume(html)
        }

        try {
            web = cached
            if (web == null) {
                web = WebView(app)
                cached = web
            }
            val w = web
            Log.w("PaperSearch", "WF load " + url)
            try {
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.databaseEnabled = true
                w.settings.loadsImagesAutomatically = false
                w.settings.blockNetworkImage = true
                w.settings.cacheMode = WebSettings.LOAD_DEFAULT
                if (desktopUa) w.settings.userAgentString = Net.DESKTOP_UA
            } catch (e: Exception) {
                // 个别 ROM 上某些设置项会抛，忽略
            }

            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u: String) {
                    view.postDelayed({
                        try {
                            view.evaluateJavascript(
                                "(function(){try{return document.documentElement?document.documentElement.outerHTML:''}catch(e){return ''}})()"
                            ) { result -> finish(decodeJsString(result), true) }
                        } catch (e: Throwable) {
                            finish("", true)
                        }
                    }, settleMs)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) finish("", false)
                }
            }

            main.postDelayed({
                Log.w("PaperSearch", "WF timeout " + url)
                finish("", true)
            }, timeoutMs)
            cont.invokeOnCancellation { main.post { finish("", true) } }

            w.loadUrl(url)
        } catch (e: Throwable) {
            Log.w("PaperSearch", "WF throw " + e.message)
            finish("", false)
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
