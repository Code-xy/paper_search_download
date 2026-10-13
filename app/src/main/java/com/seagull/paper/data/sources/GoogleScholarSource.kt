package com.seagull.paper.data.sources

import android.util.Log
import com.seagull.paper.Net
import com.seagull.paper.data.PaperItem
import com.seagull.paper.data.PaperSource
import com.seagull.paper.data.RemoteConfig
import com.seagull.paper.data.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.IOException

/**
 * Google 学术网页抓取。反爬很凶：一个镜像被打就换下一个（镜像列表来自远程配置）。
 * 全挂时抛错，UI 会提示「去网页看」——用 WebView 直接看结果页，绕开抓取。
 */
object GoogleScholarSource : PaperSource {

    override val id: String = "gscholar"
    override val name: String = "Google 学术"
    override val group: String = "综合索引"
    override val siteUrl: String = "https://scholar.google.com"
    override val note: String = "最全，但会有验证码；失败就用网页模式"

    private const val PER_PAGE = 10

    /** 上次抓成功的镜像，下次优先用：能少浪费十几秒。 */
    @Volatile
    private var lastGoodHost: String = ""

    private fun looksCaptcha(html: String): Boolean {
        val s = html.lowercase()
        return s.contains("gs_captcha") ||
                s.contains("unusual traffic") ||
                s.contains("not a robot") ||
                s.contains("我们的系统检测到") ||
                s.contains("请进行人机") ||
                (s.contains("captcha") && !s.contains("gs_ri"))
    }

    override suspend fun search(query: String, page: Int): List<PaperItem> = withContext(Dispatchers.IO) {
        val conf = RemoteConfig.active.scholarMirrors
        val mirrors = if (lastGoodHost.isNotEmpty() && conf.contains(lastGoodHost)) {
            listOf(lastGoodHost) + conf.filter { it != lastGoodHost }
        } else conf
        var lastErr = "没有可用的学术镜像"

        fun urlOf(host: String): String = host.trimEnd('/') + "/scholar?hl=zh-CN&as_sdt=0%2C5&q=" +
                Util.encPath(query) + "&start=" + (page - 1) * PER_PAGE

        Log.w("PaperSearch", "GS start, mirrors=" + mirrors.size)
        // 第一轮：普通 HTTP（快、省电），最多试 3 个镜像，别把预算耗光
        for (host in mirrors.take(3)) {
            try {
                val html = Net.getFast(urlOf(host), referer = host + "/", uaOverride = Net.DESKTOP_UA)
                if (looksCaptcha(html)) {
                    lastErr = "Google 学术触发人机验证，换镜像或稍后再试"
                    continue
                }
                val items = parse(html, host)
                Log.w("PaperSearch", "GS http " + Util.host(host) + " -> " + items.size + " items, len=" + html.length)
                if (items.isNotEmpty()) {
                    lastGoodHost = host
                    return@withContext items
                }
                lastErr = "镜像没出结果：" + Util.host(host)
            } catch (e: Exception) {
                Log.w("PaperSearch", "GS http " + Util.host(host) + " FAIL: " + e.message)
                lastErr = (e.message ?: "请求失败") + " · " + Util.host(host)
            }
        }

        // 第二轮：WebView 引擎抓。现在的 Scholar 对陌生客户端会返回「JS 空壳页」，
        // 纯 HTTP 永远解析不到结果；WebView 是真浏览器，会跑 JS、指纹真实，
        // 还能复用你在阅读器里过验证码后留下的 Cookie。
        val app = com.seagull.paper.AppRef.get
        Log.w("PaperSearch", "GS http 全挂 → 上 WebView, app=" + (app != null))
        if (app != null) {
            for (host in mirrors.take(2)) {
                val t0 = System.currentTimeMillis()
                val html = com.seagull.paper.web.WebFetcher.fetchHtml(
                    app, urlOf(host), settleMs = 1800L, timeoutMs = 18000L
                )
                Log.w(
                    "PaperSearch",
                    "GS webview " + Util.host(host) + " -> len=" + html.length +
                            " in " + (System.currentTimeMillis() - t0) + "ms"
                )
                if (html.isBlank()) {
                    lastErr = "WebView 打不开：" + Util.host(host)
                    continue
                }
                if (looksCaptcha(html)) {
                    lastErr = "Google 学术要人机验证：设置页点「打开学术搜索」过一次验证码，之后就能自动抓（Cookie 会留着）"
                    continue
                }
                val items = parse(html, host)
                if (items.isNotEmpty()) {
                    lastGoodHost = host
                    return@withContext items
                }
                lastErr = "WebView 里也没解析出结果（多半是被人机验证拦了）：" + Util.host(host)
            }
        }
        Log.w("PaperSearch", "GS all failed: " + lastErr)
        throw IOException(lastErr)
    }

    private fun parse(html: String, host: String): List<PaperItem> {
        val doc = Jsoup.parse(html, host)
        val out = ArrayList<PaperItem>()
        // 一条结果的外层是 div.gs_r，标题/作者在 gs_ri 里，PDF 链接在兄弟节点 gs_ggs 里，
        // 所以必须从外层的 gs_r 里找 PDF，不能只看 gs_ri。
        for (h3 in doc.select("h3.gs_rt")) {
            val ri = h3.parent() ?: continue
            val box = ri.parent() ?: ri
            val a = h3.selectFirst("a")
            val title = h3.text()
                .replace(Regex("^\\[(PDF|HTML|BOOK|引用|CITATION)\\]\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+"), " ").trim()
            if (title.length < 4) continue

            val link = a?.attr("href")?.trim().orEmpty()
            // gs_ggs 里第一个 <a> 可能是 javascript:void(0)（保存/引用按钮），
            // 所以要遍历所有链接，挑真正像 PDF 的那个。
            val sideLinks = box.select("div.gs_ggs a").map { it.attr("href").trim() } +
                    box.select("div.gs_or_ggsm a").map { it.attr("href").trim() }
            val ggs = sideLinks.firstOrNull { Util.looksPdf(it) }.orEmpty()

            val meta = (ri.selectFirst("div.gs_a") ?: box.selectFirst("div.gs_a"))?.text().orEmpty()
            val parts = meta.split(" - ")
            val authors = parts.getOrNull(0)?.trim().orEmpty()
            val venueYear = parts.getOrNull(1)?.trim().orEmpty()
            val publisher = parts.getOrNull(2)?.trim().orEmpty()

            val cites = Util.intOf((ri.selectFirst("div.gs_fl") ?: box.selectFirst("div.gs_fl"))?.text())
            val snippet = (ri.selectFirst("div.gs_rs") ?: box.selectFirst("div.gs_rs"))?.text().orEmpty()

            var pdf: String? = null
            if (ggs.isNotEmpty()) pdf = ggs
            else if (Util.looksPdf(link)) pdf = link

            val landing = when {
                link.isBlank() -> null
                pdf != null && link == pdf -> null
                else -> link
            }
            out.add(
                PaperItem(
                    title = title,
                    authors = authors,
                    venue = listOf(venueYear, publisher).filter { it.isNotBlank() }.joinToString(" · "),
                    year = Util.yearOf(venueYear),
                    abstractText = snippet,
                    doi = "",
                    pdfUrl = pdf,
                    landingUrl = landing,
                    oa = pdf != null,
                    citations = cites,
                    sourceId = id,
                    sourceName = name,
                    extra = Util.host(host)
                )
            )
        }
        return out
    }
}
