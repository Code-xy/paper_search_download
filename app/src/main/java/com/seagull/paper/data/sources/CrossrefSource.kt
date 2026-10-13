package com.seagull.paper.data.sources

import com.seagull.paper.Net
import com.seagull.paper.data.PaperItem
import com.seagull.paper.data.PaperSource
import com.seagull.paper.data.RemoteConfig
import com.seagull.paper.data.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Crossref：DOI 注册库，覆盖面最广；给的多是落地页，PDF 交给解析链。 */
object CrossrefSource : PaperSource {

    override val id: String = "crossref"
    override val name: String = "Crossref"
    override val group: String = "DOI 注册库"
    override val siteUrl: String = "https://search.crossref.org"
    override val note: String = "覆盖最广，多为落地页需解析"

    private const val PAGE = 20
    private const val SELECT =
        "DOI,title,author,issued,container-title,link,abstract,is-referenced-by-count,URL,type"

    override suspend fun search(query: String, page: Int): List<PaperItem> = withContext(Dispatchers.IO) {
        val url = "https://api.crossref.org/works?query.bibliographic=" + Util.encPath(query) +
                "&rows=" + PAGE + "&offset=" + (page - 1) * PAGE +
                "&select=" + SELECT + "&mailto=" + Util.encPath(RemoteConfig.active.email)
        val root = JSONObject(Net.getJson(url))
        val arr = root.optJSONObject("message")?.optJSONArray("items") ?: return@withContext emptyList()
        val out = ArrayList<PaperItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optJSONArray("title")?.optString(0).orEmpty().trim()
            if (title.isEmpty()) continue
            val doi = Util.normDoi(o.optString("DOI"))
            val authors = ArrayList<String>()
            o.optJSONArray("author")?.let { a ->
                for (k in 0 until minOf(a.length(), 8)) {
                    val au = a.optJSONObject(k) ?: continue
                    val nm = (au.optString("given") + " " + au.optString("family")).trim()
                    if (nm.isNotEmpty()) authors.add(nm)
                }
            }
            val yr = o.optJSONObject("issued")?.optJSONArray("date-parts")
                ?.optJSONArray(0)?.optInt(0, 0) ?: 0
            val venue = o.optJSONArray("container-title")?.optString(0).orEmpty()
            out.add(
                PaperItem(
                    title = title,
                    authors = authors.joinToString(", "),
                    venue = venue,
                    year = yr,
                    abstractText = stripTags(o.optString("abstract")),
                    doi = doi,
                    pdfUrl = pickPdf(o.optJSONArray("link")),
                    landingUrl = o.optString("URL").trim().ifEmpty { if (doi.isEmpty()) null else Util.doiUrl(doi) },
                    oa = false,
                    citations = o.optInt("is-referenced-by-count", -1),
                    sourceId = id,
                    sourceName = name
                )
            )
        }
        out
    }

    private fun pickPdf(links: JSONArray?): String? {
        if (links == null) return null
        for (i in 0 until links.length()) {
            val l = links.optJSONObject(i) ?: continue
            val ct = l.optString("content-type")
            val url = l.optString("URL").trim()
            if (!url.startsWith("http")) continue
            if (ct.contains("pdf", true) || Util.looksPdf(url)) return url
        }
        return null
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
}
