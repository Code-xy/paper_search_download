package com.seagull.paper.data.sources

import com.seagull.paper.Net
import com.seagull.paper.data.PaperItem
import com.seagull.paper.data.PaperSource
import com.seagull.paper.data.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** arXiv Atom API：预印本，PDF 直链，最稳的一个源。 */
object ArxivSource : PaperSource {

    override val id: String = "arxiv"
    override val name: String = "arXiv"
    override val group: String = "预印本"
    override val siteUrl: String = "https://arxiv.org"
    override val note: String = "CS/物理/数学预印本，带 PDF 直链"

    private const val PAGE = 20

    override suspend fun search(query: String, page: Int): List<PaperItem> = withContext(Dispatchers.IO) {
        val start = (page - 1) * PAGE
        val url = "http://export.arxiv.org/api/query?search_query=all:" + Util.encPath(query) +
                "&start=" + start + "&max_results=" + PAGE + "&sortBy=relevance&sortOrder=descending"
        val xml = Net.get(url, accept = "application/atom+xml")
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val out = ArrayList<PaperItem>()
        for (e in doc.select("entry")) {
            val absId = e.selectFirst("id")?.text().orEmpty().trim()
            var title = e.selectFirst("title")?.text().orEmpty()
            title = title.replace(Regex("\\s+"), " ").trim()
            if (title.isEmpty()) continue
            var pdf: String? = null
            for (l in e.select("link")) {
                val type = l.attr("type")
                val ttl = l.attr("title")
                if (type.contains("pdf") || ttl == "pdf") {
                    pdf = l.attr("href").trim()
                }
            }
            val authors = e.select("author > name").map { it.text().trim() }.filter { it.isNotEmpty() }
            val cat = e.select("category").firstOrNull()?.attr("term").orEmpty()
            val published = e.selectFirst("published")?.text().orEmpty()
            val summary = e.selectFirst("summary")?.text().orEmpty()
            val landing = absId.replace("http://", "https://")
            out.add(
                PaperItem(
                    title = title,
                    authors = authors.take(8).joinToString(", "),
                    venue = if (cat.isNotEmpty()) "arXiv · " + cat else "arXiv",
                    year = Util.yearOf(published),
                    abstractText = summary.replace(Regex("\\s+"), " ").trim(),
                    doi = Util.normDoi(e.selectFirst("arxiv\\:doi")?.text()),
                    pdfUrl = if (pdf.isNullOrBlank()) null else pdf,
                    landingUrl = if (landing.isBlank()) null else landing,
                    oa = true,
                    citations = -1,
                    sourceId = id,
                    sourceName = name
                )
            )
        }
        out
    }
}
