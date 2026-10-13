package com.seagull.paper.data.sources

import com.seagull.paper.Net
import com.seagull.paper.data.PaperItem
import com.seagull.paper.data.PaperSource
import com.seagull.paper.data.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Semantic Scholar Graph API：字段干净，自带 openAccessPdf，值得一个 429 重试。 */
object SemanticScholarSource : PaperSource {

    override val id: String = "s2"
    override val name: String = "Semantic Scholar"
    override val group: String = "综合索引"
    override val siteUrl: String = "https://www.semanticscholar.org"
    override val note: String = "结构化最好，限流时自动重试"

    private const val PAGE = 20
    private const val FIELDS =
        "title,year,venue,abstract,externalIds,openAccessPdf,authors,citationCount,isOpenAccess,publicationTypes"

    override suspend fun search(query: String, page: Int): List<PaperItem> = withContext(Dispatchers.IO) {
        val offset = (page - 1) * PAGE
        val url = "https://api.semanticscholar.org/graph/v1/paper/search?query=" +
                Util.encPath(query) + "&limit=" + PAGE + "&offset=" + offset + "&fields=" + FIELDS
        var last: Exception? = null
        var body = ""
        for (i in 0 until 3) {
            try {
                body = Net.getJson(url)
                last = null
                break
            } catch (e: Exception) {
                last = e
                delay(1200L * (i + 1))
            }
        }
        if (last != null) throw last
        val root = JSONObject(body)
        val arr = root.optJSONArray("data") ?: return@withContext emptyList()
        val out = ArrayList<PaperItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            if (title.isEmpty()) continue
            val ext = o.optJSONObject("externalIds")
            val doi = Util.normDoi(ext?.optString("DOI"))
            val arx = ext?.optString("ArXiv").orEmpty()
            val pmid = ext?.optString("PubMed").orEmpty()
            var pdf = o.optJSONObject("openAccessPdf")?.optString("url")?.trim().orEmpty()
            if (pdf.isEmpty() && arx.isNotEmpty()) pdf = "https://arxiv.org/pdf/" + arx
            val authors = ArrayList<String>()
            o.optJSONArray("authors")?.let { a ->
                for (k in 0 until minOf(a.length(), 8)) {
                    a.optJSONObject(k)?.optString("name")?.takeIf { it.isNotBlank() }?.let { authors.add(it) }
                }
            }
            out.add(
                PaperItem(
                    title = title,
                    authors = authors.joinToString(", "),
                    venue = o.optString("venue").trim(),
                    year = o.optInt("year", 0),
                    abstractText = o.optString("abstract").trim(),
                    doi = doi,
                    pdfUrl = pdf.ifEmpty { null },
                    landingUrl = when {
                        doi.isNotEmpty() -> Util.doiUrl(doi)
                        pmid.isNotEmpty() -> "https://pubmed.ncbi.nlm.nih.gov/" + pmid + "/"
                        else -> null
                    },
                    oa = o.optBoolean("isOpenAccess", false) || pdf.isNotEmpty(),
                    citations = o.optInt("citationCount", -1),
                    sourceId = id,
                    sourceName = name
                )
            )
        }
        out
    }
}
