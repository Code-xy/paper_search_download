package com.seagull.paper

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 统一网络出口：一个 OkHttp，UA 轮换（很多学术站对单一 UA 限流），
 * 短超时保证多源并发体验，失败信息给人话。
 */
object Net {

    private val UAS = arrayOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    )

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun ua(): String = UAS[((System.currentTimeMillis() / 1000L) % UAS.size).toInt()]

    /** 会话 Cookie：出版社落地页/学术镜像常靠它维持状态。 */
    private val cookieJar = object : CookieJar {
        private val store = HashMap<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isNotEmpty()) store[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
    }

    private fun build(proxyHost: String?, proxyPort: Int): OkHttpClient {
        // 强制 HTTP/1.1：代理/中间设备对 h2 兼容性差，典型症状
        // 就是 "Required SETTINGS preface not received"，直接掐掉这类问题。
        val b = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(14, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .cookieJar(cookieJar)
        if (!proxyHost.isNullOrBlank() && proxyPort > 0) {
            b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
        }
        return b.build()
    }

    @Volatile
    private var http: OkHttpClient = build(null, -1)

    /** 镜像轮询专用：连不上就赶紧下一个，别让 12 秒超时把一次搜索拖死。 */
    @Volatile
    private var httpFast: OkHttpClient = buildFast(null, -1)

    private fun buildFast(proxyHost: String?, proxyPort: Int): OkHttpClient {
        val b = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(false)
            .cookieJar(cookieJar)
        if (!proxyHost.isNullOrBlank() && proxyPort > 0) {
            b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
        }
        return b.build()
    }

    @Volatile
    var proxySpec: String = ""
        private set

    /**
     * 设置代理。手机上的 Clash/V2Ray 大多是「HTTP 代理」而不是全局 VPN，
     * App 不显式走它就一直直连——国内直连 Google 学术/arXiv 基本必挂。
     * 接受 "127.0.0.1:7890" / "http://127.0.0.1:7890" / "socks5://127.0.0.1:7891"（socks 按 HTTP 代理端口也能通 Clash 的 mixed 端口）。
     */
    fun setProxyFromSpec(spec: String) {
        proxySpec = spec.trim()
        val s = proxySpec
            .removePrefix("http://").removePrefix("https://").removePrefix("socks5://").removePrefix("socks://")
            .trim()
        if (s.isEmpty()) {
            http = build(null, -1)
            httpFast = buildFast(null, -1)
            return
        }
        // Clash 用户最常见的输入就是端口号，只填端口默认走 127.0.0.1
        if (s.all { it.isDigit() }) {
            val port = s.toIntOrNull() ?: -1
            if (port in 1..65535) {
                http = build("127.0.0.1", port)
                httpFast = buildFast("127.0.0.1", port)
                return
            }
        }
        val parts = s.split(":")
        val host = parts.getOrNull(0).orEmpty().trim()
        val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: -1
        val useIt = host.isNotEmpty() && port > 0
        http = if (useIt) build(host, port) else build(null, -1)
        httpFast = if (useIt) buildFast(host, port) else buildFast(null, -1)
    }

    val proxyOn: Boolean
        get() = proxySpec.isNotEmpty() && http.proxy != Proxy.NO_PROXY

    /** 把链路上的怪错误翻译成人话。 */
    fun friendly(raw: String): String {
        val s = raw.lowercase()
        return when {
            s.contains("settings preface") || s.contains("protocol") && s.contains("http/2") ->
                "链路被中间设备打断（HTTP/2 不兼容，多半没走代理）"
            s.contains("connection reset") || s.contains("connection closed") || s.contains("unexpected end of stream") ->
                "连接被重置（被墙/被中间设备掐断，去设置里配代理）"
            s.contains("unable to resolve host") || s.contains("no address associated") ->
                "DNS 解析失败（域名被污染）"
            s.contains("timeout") || s.contains("timed out") -> "超时"
            s.contains("certificate") || s.contains("ssl") -> "证书错误（可能被中间人劫持）"
            else -> raw
        }
    }

    fun msg(code: Int, url: String): String = when (code) {
        401, 403 -> "被拦截(HTTP $code)，可能需要换源或代理"
        404 -> "接口不存在(404)"
        429 -> "请求太频繁(429)，稍后再试"
        500, 502, 503, 504 -> "服务端故障(HTTP $code)"
        else -> "HTTP $code · " + host(url)
    }

    fun host(url: String): String = try {
        url.toHttpUrlOrNull()?.host ?: url
    } catch (e: Exception) {
        url
    }

    fun request(url: String, referer: String? = null, accept: String? = null, uaOverride: String? = null): Request {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", uaOverride ?: ua())
            .header(
                "Accept",
                accept ?: "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.9,*/*;q=0.8"
            )
            .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Accept-Encoding", "gzip")
        if (!referer.isNullOrBlank()) b.header("Referer", referer)
        return b.build()
    }

    fun get(url: String, referer: String? = null, accept: String? = null, uaOverride: String? = null): String {
        http.newCall(request(url, referer, accept, uaOverride)).execute().use { r ->
            if (!r.isSuccessful) throw IOException(msg(r.code, url))
            return r.body?.string().orEmpty()
        }
    }

    fun getJson(url: String, referer: String? = null): String =
        get(url, referer, "application/json, text/plain, */*")

    /** 短超时 GET：给多镜像轮询用。 */
    fun getFast(url: String, referer: String? = null, uaOverride: String? = null): String {
        httpFast.newCall(request(url, referer, accept = "text/html,application/xhtml+xml,*/*;q=0.8", uaOverride = uaOverride))
            .execute().use { r ->
                if (!r.isSuccessful) throw IOException(msg(r.code, url))
                return r.body?.string().orEmpty()
            }
    }

    /** 下载/探测用：返回未消费的响应，调用方负责 close。 */
    fun open(url: String, referer: String? = null, uaOverride: String? = null): Response {
        val r = http.newCall(request(url, referer, accept = "application/pdf,*/*", uaOverride = uaOverride)).execute()
        if (!r.isSuccessful) {
            val c = r.code
            r.close()
            throw IOException(msg(c, url))
        }
        return r
    }

    /** 只取响应头，判断是不是真 PDF / 有没有被重定向到登录页。 */
    fun probe(url: String, referer: String? = null): ProbeResult {
        return try {
            val r = http.newCall(request(url, referer, accept = "application/pdf,*/*")).execute()
            r.use {
                ProbeResult(
                    ok = it.isSuccessful,
                    code = it.code,
                    contentType = it.header("Content-Type").orEmpty(),
                    length = it.header("Content-Length")?.toLongOrNull() ?: -1L,
                    finalUrl = it.request.url.toString()
                )
            }
        } catch (e: Exception) {
            ProbeResult(false, 0, "", -1L, url)
        }
    }

    /** 网络自检：GET 一个地址，返回状态、耗时和正文（截断），给「自检」页判断用。 */
    data class Diag(val ok: Boolean, val detail: String, val ms: Long, val body: String)

    fun diag(url: String, accept: String? = null, uaOverride: String? = null): Diag {
        val t0 = System.currentTimeMillis()
        return try {
            http.newCall(request(url, accept = accept, uaOverride = uaOverride)).execute().use { r ->
                val ms = System.currentTimeMillis() - t0
                val body = try {
                    r.peekBody(4096).string()
                } catch (e: Exception) {
                    ""
                }
                Diag(r.isSuccessful, "HTTP " + r.code + " · " + r.header("Content-Type").orEmpty().take(40), ms, body)
            }
        } catch (e: Exception) {
            Diag(false, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - t0, "")
        }
    }

    data class ProbeResult(
        val ok: Boolean,
        val code: Int,
        val contentType: String,
        val length: Long,
        val finalUrl: String
    ) {
        val isPdf: Boolean
            get() = contentType.contains("pdf", true) || finalUrl.lowercase().contains(".pdf")
    }
}
