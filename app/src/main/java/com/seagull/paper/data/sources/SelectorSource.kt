package com.seagull.paper.data.sources

import com.seagull.paper.Net
import com.seagull.paper.data.PaperItem
import com.seagull.paper.data.PaperSource
import com.seagull.paper.data.SiteSpec
import com.seagull.paper.data.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 选择器驱动的通用源：sources.json 里加一段 CSS 选择器就能接一个新站，改版不用发版。
 */
class SelectorSource(private val spec: SiteSpec) : PaperSource {

    override val id: String get() = spec.id
    override val name: String get() = spec.name
    override val group: String get() = spec.group
    override val siteUrl: String get() = spec.siteUrl
    override val note: String get() = spec.note

    override suspend fun search(query: String, page: Int): List<PaperItem> = withContext(Dispatchers.IO) {
        if (spec.urlTemplate.isEmpty()) return@withContext emptyList()
        val url = spec.urlTemplate
            .replace("{q}", Util.encPath(query))
            .replace("{query}", Util.enc(query))
            .replace("{page}", page.toString())
        val html = Net.get(url, referer = spec.siteUrl.ifEmpty { null })
        val doc = Jsoup.parse(html, spec.siteUrl.ifEmpty { url })
        val out = ArrayList<PaperItem>()
        for (el in doc.select(spec.itemSelector)) {
            val title = pick(el, spec.titleSelector, spec.titleAttr) ?: continue
            if (title.length < 4) continue
            val pdfRaw = pick(el, spec.pdfSelector, spec.pdfAttr)
            val pdf = abs(pdfRaw)?.takeIf { Util.looksPdf(it) }
            val link = abs(pick(el, spec.linkSelector, spec.linkAttr))
            val doiRaw = pick(el, spec.doiSelector, "text").orEmpty()
            val doi = Util.normDoi(doiRaw.ifEmpty { link.orEmpty() })
            out.add(
                PaperItem(
                    title = title,
                    authors = pick(el, spec.authorSelector, "text").orEmpty(),
                    venue = pick(el, spec.venueSelector, "text").orEmpty(),
                    year = Util.yearOf(pick(el, spec.yearSelector, "text")),
                    abstractText = pick(el, spec.abstractSelector, "text").orEmpty(),
                    doi = if (doiRaw.isNotEmpty()) doi else "",
                    pdfUrl = pdf,
                    landingUrl = link,
                    oa = pdf != null,
                    citations = -1,
                    sourceId = id,
                    sourceName = name
                )
            )
        }
        out
    }

    private fun abs(u: String?): String? {
        if (u.isNullOrBlank()) return null
        if (u.startsWith("http")) return u
        if (u.startsWith("//")) return "https:" + u
        if (u.startsWith("/") && spec.siteUrl.isNotEmpty()) return spec.siteUrl.trimEnd('/') + u
        return u
    }

    private fun pick(el: Element, selector: String, attr: String): String? {
        if (selector.isEmpty()) return null
        val target: Element = if (selector == ":self") el else el.selectFirst(selector) ?: return null
        val v = if (attr.isEmpty() || attr == "text") target.text() else target.attr(attr)
        return v.trim().ifEmpty { null }
    }
}
