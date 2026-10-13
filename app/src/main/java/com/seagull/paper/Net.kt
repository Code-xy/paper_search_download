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

    @Volatile
    private var directHttp: OkHttpClient = build(null, -1)

    /** 镜像轮询专用：连不上就赶紧下一个，别让 12 秒超时把一次搜索拖死。 */
    @Volatile
    private var httpFast: OkHttpClient = buildFast(null, -1)

    @Volatile
    private var directFast: OkHttpClient = buildFast(null, -1)

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
        directHttp = build(null, -1)
        directFast = buildFast(null, -1)
        if (s.isEmpty()) {
            http = directHttp
            httpFast = directFast
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

    /** 必须过节点的域名（国内直连必挂）。 */
    private val NEED_PROXY = listOf(
        "google.", "gstatic.", "googleusercontent.", "sci-hub.", "sci.bban.top", "doi.org",
        "unpaywall.org", "scholar.", "annas-archive.", "onlinelibrary.wiley.com", "sciencedirect.",
        "springer.com", "nature.com", "ieeexplore.", "dl.acm.org", "tandfonline.", "jstor.",
        "sagepub.", "aps.org", "iopscience.", "pubs.acs.org", "rsc.org", "cell.com", "thelancet."
    )

    /** 国内直连可达的域名：走直连更快，也不受梯子抖动影响。 */
    private val PREFER_DIRECT = listOf(
        "openalex.org", "crossref.org", "semanticscholar.org", "doaj.org", "ebi.ac.uk",
        "arxiv.org", "biorxiv.org", "medrxiv.org", "ncbi.nlm.nih.gov", "europepmc.org",
        "pubmed.ncbi.nlm.nih.gov", "cnki.net", "wanfangdata.com.cn", "ncpssd.org"
    )

    private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host.orEmpty()

    /** 这条请求该走代理还是直连。 */
    private fun useProxyFor(url: String): Boolean {
        if (!proxyOn) return false
        val h = hostOf(url)
        if (NEED_PROXY.any { h.contains(it, true) }) return true
        if (PREFER_DIRECT.any { h.contains(it, true) }) return false
        return true
    }

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
        502, 503, 504 -> "HTTP $code · 上游/代理不可用" + (if (proxyOn) "（梯子节点挂了？去 Clash 里测延迟换节点）" else "")
        500 -> "服务端故障(HTTP 500)"
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

    private fun exec(client: OkHttpClient, url: String, referer: String?, accept: String?, ua: String?): String {
        client.newCall(request(url, referer, accept, ua)).execute().use { r ->
            if (!r.isSuccessful) throw IOException(msg(r.code, url))
            return r.body?.string().orEmpty()
        }
    }

    /** 期望的响应类型：接口类要校验真的是 JSON/XML，否则算失败换链路重试。 */
    enum class Expect { ANY, JSON, XML }

    private fun looksRight(body: String, expect: Expect): Boolean {
        val b = body.trimStart()
        return when (expect) {
            Expect.ANY -> true
            Expect.JSON -> b.startsWith("{") || b.startsWith("[")
            Expect.XML -> b.startsWith("<?xml") || b.startsWith("<feed") || b.startsWith("<")
        }
    }

    /**
     * 带链路策略的 GET：必须翻的域名走代理，国内可达的走直连；
     * 连接失败、或者返回内容明显不对（被劫持成 HTML 门户页），都自动换另一种链路再试。
     * 国内网络下这招能救回一大半源。
     */
    fun get(
        url: String,
        referer: String? = null,
        accept: String? = null,
        uaOverride: String? = null,
        expect: Expect = Expect.ANY
    ): String {
        val wantProxy = useProxyFor(url)
        val first = if (wantProxy) http else directHttp
        val alt = if (wantProxy) directHttp else http
        var body0: String? = null
        var err0: IOException? = null
        try {
            body0 = exec(first, url, referer, accept, uaOverride)
            if (looksRight(body0, expect)) return body0
        } catch (e: IOException) {
            err0 = e
        }
        if (!proxyOn || alt === first) {
            throw err0 ?: IOException("返回的不是接口数据：" + snippet(body0.orEmpty()))
        }
        val body = exec(alt, url, referer, accept, uaOverride)
        if (!looksRight(body, expect)) {
            throw IOException("返回的不是接口数据：" + snippet(body))
        }
        return body
    }

    /** 出错时把响应开头带上，方便一眼看出是谁在返回垃圾。 */
    fun snippet(body: String, n: Int = 60): String =
        body.replace(Regex("\\s+"), " ").trim().take(n)

    fun getJson(url: String, referer: String? = null): String =
        get(url, referer, "application/json, text/plain, */*", expect = Expect.JSON)

    fun getXml(url: String, referer: String? = null): String =
        get(url, referer, "application/atom+xml, application/xml, text/xml, */*", expect = Expect.XML)

    /** 短超时 GET：给多镜像轮询用，同样带链路策略。 */
    fun getFast(url: String, referer: String? = null, uaOverride: String? = null): String {
        val accept = "text/html,application/xhtml+xml,*/*;q=0.8"
        val wantProxy = useProxyFor(url)
        val first = if (wantProxy) httpFast else directFast
        return try {
            exec(first, url, referer, accept, uaOverride)
        } catch (e: IOException) {
            if (!proxyOn) throw e
            val alt = if (wantProxy) directFast else httpFast
            if (alt === first) throw e
            exec(alt, url, referer, accept, uaOverride)
        }
    }

    /** 下载/探测用：返回未消费的响应，调用方负责 close。 */
    private fun openWith(client: OkHttpClient, url: String, referer: String?, ua: String?): Response {
        val r = client.newCall(request(url, referer, accept = "application/pdf,*/*", uaOverride = ua)).execute()
        if (!r.isSuccessful) {
            val c = r.code
            r.close()
            throw IOException(msg(c, url))
        }
        return r
    }

    fun open(url: String, referer: String? = null, uaOverride: String? = null): Response {
        val wantProxy = useProxyFor(url)
        val first = if (wantProxy) http else directHttp
        return try {
            openWith(first, url, referer, uaOverride)
        } catch (e: IOException) {
            if (!proxyOn) throw e
            val alt = if (wantProxy) directHttp else http
            if (alt === first) throw e
            openWith(alt, url, referer, uaOverride)
        }
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
        val wantProxy = useProxyFor(url)
        val cl = if (wantProxy) http else directHttp
        return try {
            cl.newCall(request(url, accept = accept, uaOverride = uaOverride)).execute().use { r ->
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
