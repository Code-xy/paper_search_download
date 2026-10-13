package com.seagull.paper.data.sources

import com.seagull.paper.Net
import com.seagull.paper.data.PaperItem
import com.seagull.paper.data.PaperSource
import com.seagull.paper.data.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Europe PMC：生物医学免费全文大户，PMC 里很多是 PDF 直链。 */
object EuropePmcSource : PaperSource {

    override val id: String = "epmc"
    override val name: String = "Europe PMC"
    override val group: String = "生命科学"
    override val siteUrl: String = "https://europepmc.org"
    override val note: String = "生物医学全文，PDF 直接下"

    private const val PAGE = 20

    override suspend fun search(query: String, page: Int): List<PaperItem> = withContext(Dispatchers.IO) {
        val url = "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=" +
                Util.encPath(query) + "&format=json&resultType=core&pageSize=" + PAGE + "&page=" + page
        val root = JSONObject(Net.getJson(url))
        val arr = root.optJSONObject("resultList")?.optJSONArray("result")
            ?: return@withContext emptyList()
        val out = ArrayList<PaperItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            if (title.isEmpty()) continue
            val doi = Util.normDoi(o.optString("doi"))
            val pmcid = o.optString("pmcid").trim()
            val landing = when {
                o.optString("source").equals("PMC", true) && pmcid.isNotEmpty() ->
                    "https://www.ncbi.nlm.nih.gov/pmc/articles/" + pmcid + "/"
                doi.isNotEmpty() -> Util.doiUrl(doi)
                else -> null
            }
            out.add(
                PaperItem(
                    title = title,
                    authors = o.optString("authorString").trim(),
                    venue = o.optString("journalTitle").trim(),
                    year = o.optString("pubYear").toIntOrNull() ?: 0,
                    abstractText = o.optString("abstractText").trim(),
                    doi = doi,
                    pdfUrl = pickPdf(o, pmcid),
                    landingUrl = landing,
                    oa = o.optString("isOpenAccess").equals("Y", true) || pmcid.isNotEmpty(),
                    citations = o.optInt("citedByCount", -1),
                    sourceId = id,
                    sourceName = name
                )
            )
        }
        out
    }

    private fun pickPdf(o: JSONObject, pmcid: String): String? {
        val list = o.optJSONObject("fullTextUrlList")?.optJSONArray("fullTextUrl")
        if (list != null) {
            for (i in 0 until list.length()) {
                val u = list.optJSONObject(i) ?: continue
                val style = u.optString("documentStyle")
                val url = u.optString("url").trim()
                if (style == "pdf" && url.startsWith("http")) return url
            }
        }
        if (pmcid.isNotEmpty()) return "https://europepmc.org/articles/" + pmcid + "?pdf=render"
        return null
    }
}
