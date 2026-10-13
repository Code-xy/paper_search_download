package com.seagull.paper

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
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

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

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
        client.newCall(request(url, referer, accept, uaOverride)).execute().use { r ->
            if (!r.isSuccessful) throw IOException(msg(r.code, url))
            return r.body?.string().orEmpty()
        }
    }

    fun getJson(url: String, referer: String? = null): String =
        get(url, referer, "application/json, text/plain, */*")

    /** 下载/探测用：返回未消费的响应，调用方负责 close。 */
    fun open(url: String, referer: String? = null, uaOverride: String? = null): Response {
        val r = client.newCall(request(url, referer, accept = "application/pdf,*/*", uaOverride = uaOverride)).execute()
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
            val r = client.newCall(request(url, referer, accept = "application/pdf,*/*")).execute()
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
