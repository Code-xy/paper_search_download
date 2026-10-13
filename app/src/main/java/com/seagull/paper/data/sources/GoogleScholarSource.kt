package com.seagull.paper.data.sources

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
        val mirrors = RemoteConfig.active.scholarMirrors
        var lastErr = "没有可用的学术镜像"
        for (host in mirrors) {
            val url = host.trimEnd('/') + "/scholar?hl=zh-CN&as_sdt=0%2C5&q=" +
                    Util.encPath(query) + "&start=" + (page - 1) * PER_PAGE
            try {
                val html = Net.get(url, referer = host + "/", uaOverride = Net.DESKTOP_UA)
                if (looksCaptcha(html)) {
                    lastErr = "Google 学术触发人机验证，换镜像或稍后再试"
                    continue
                }
                val items = parse(html, host)
                if (items.isNotEmpty()) return@withContext items
                lastErr = "镜像没出结果：" + Util.host(host)
            } catch (e: Exception) {
                lastErr = (e.message ?: "请求失败") + " · " + Util.host(host)
            }
        }
        throw IOException(lastErr)
    }

    private fun parse(html: String, host: String): List<PaperItem> {
        val doc = Jsoup.parse(html, host)
        val out = ArrayList<PaperItem>()
        for (el in doc.select("div.gs_ri, div.gs_r")) {
            val h3 = el.selectFirst("h3.gs_rt") ?: continue
            val a = h3.selectFirst("a")
            val title = h3.text()
                .replace(Regex("^\\[(PDF|HTML|BOOK|引用|CITATION)\\]\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+"), " ").trim()
            if (title.isEmpty() || title.length < 4) continue
            val link = a?.attr("href")?.trim().orEmpty()

            // 右侧 gs_ggs 里是 [PDF]/[HTML] 另一份链接
            val ggs = el.selectFirst("div.gs_ggs a")?.attr("href")?.trim().orEmpty()

            val meta = el.selectFirst("div.gs_a")?.text().orEmpty()
            val parts = meta.split(" - ")
            val authors = parts.getOrNull(0)?.trim().orEmpty()
            val venueYear = parts.getOrNull(1)?.trim().orEmpty()
            val publisher = parts.getOrNull(2)?.trim().orEmpty()

            val cites = Util.intOf(el.selectFirst("div.gs_fl")?.text())
            val snippet = el.selectFirst("div.gs_rs")?.text().orEmpty()

            var pdf: String? = null
            if (Util.looksPdf(ggs)) pdf = ggs
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
