package com.seagull.paper.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.seagull.paper.Net
import com.seagull.paper.data.Downloader
import com.seagull.paper.data.PdfResolver
import com.seagull.paper.data.RemoteConfig
import com.seagull.paper.data.Store
import com.seagull.paper.data.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内置阅读器：抓取失败的源、出版社落地页都能在这儿看。
 * 关键功能是「解析本页 PDF」——任意网页，现场扒 PDF 链接然后直接下。
 */
class ReaderActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SPLIT = "split"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        setContent {
            PaperTheme {
                var url by remember { mutableStateOf(startUrl) }
                var progress by remember { mutableFloatStateOf(0f) }
                var status by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                var web by remember { mutableStateOf<WebView?>(null) }
                val store = remember { Store(applicationContext) }

                fun runResolve(pageUrl: String) {
                    if (busy) return
                    busy = true
                    status = "解析页面，找 PDF…"
                    scope.launch {
                        val cfg = RemoteConfig.active.copy(
                            email = store.email.ifEmpty { RemoteConfig.active.email }
                        )
                        val res = PdfResolver.fromPageUrl(pageUrl, cfg) { s -> status = s }
                        if (!res.ok) {
                            status = "没解析到 PDF：" + res.steps.joinToString("；") { it.provider + ":" + it.detail }.take(160)
                            busy = false
                            return@launch
                        }
                        status = "抓到 PDF（" + (res.best?.provider ?: "") + "），开始下载…"
                        try {
                            val nameBase = Util.safeFileName(
                                pageUrl.substringAfterLast('/').ifEmpty { "paper" }
                            ).removeSuffix(".pdf")
                            val r = withContext(Dispatchers.IO) {
                                Downloader.download(
                                    this@ReaderActivity,
                                    res.candidates,
                                    nameBase + ".pdf",
                                    pageUrl
                                ) { d, t ->
                                    status = "下载中 " + Util.fmtSize(d) + if (t > 0) " / " + Util.fmtSize(t) else ""
                                }
                            }
                            status = "已保存：" + r.file.name + "（" + Util.fmtSize(r.bytes) + "）"
                            try {
                                startActivity(Downloader.openIntent(this@ReaderActivity, r.file))
                            } catch (e: Exception) {
                                Actions.toast(this@ReaderActivity, "已下载，去「下载」页看")
                            }
                        } catch (e: Exception) {
                            status = "下载失败：" + (e.message ?: "未知错误")
                        }
                        busy = false
                    }
                }

                Column(Modifier.fillMaxSize()) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                                }
                                Text(
                                    title.ifEmpty { Util.host(url).ifEmpty { "网页" } },
                                    modifier = Modifier.weight(1f),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (busy) {
                                    TextButton(onClick = {}) { Text("解析中…", fontSize = 12.5.sp) }
                                } else {
                                    TextButton(onClick = { runResolve(url) }) { Text("解析本页 PDF", fontSize = 12.5.sp) }
                                }
                                IconButton(onClick = { web?.reload() }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "刷新", modifier = Modifier.size(18.dp))
                                }
                            }
                            if (progress in 0.01f..0.99f) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().height(2.dp)
                                )
                            }
                        }
                    }

                    if (status.isNotEmpty()) {
                        Text(
                            status,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 3
                        )
                    }

                    Box(Modifier.fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                                    settings.userAgentString = Net.DESKTOP_UA
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            request: WebResourceRequest
                                        ): Boolean {
                                            val u = request.url.toString()
                                            if (Util.looksPdf(u)) {
                                                runResolve(u)
                                                return true
                                            }
                                            return false
                                        }

                                        override fun onPageFinished(view: WebView, u: String) {
                                            url = u
                                        }
                                    }
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView, p: Int) {
                                            progress = p / 100f
                                        }
                                    }
                                    if (startUrl.isNotEmpty()) loadUrl(startUrl)
                                    web = this
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
