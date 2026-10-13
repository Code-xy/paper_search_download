package com.seagull.paper.data.sources

import com.seagull.paper.Net
import com.seagull.paper.data.PaperItem
import com.seagull.paper.data.PaperSource
import com.seagull.paper.data.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** DOAJ：纯开放获取期刊目录，全是能下的。 */
object DoajSource : PaperSource {

    override val id: String = "doaj"
    override val name: String = "DOAJ"
    override val group: String = "开放获取"
    override val siteUrl: String = "https://doaj.org"
    override val note: String = "全开放获取期刊，必然能下"

    private const val PAGE = 20

    override suspend fun search(query: String, page: Int): List<PaperItem> = withContext(Dispatchers.IO) {
        val url = "https://doaj.org/api/search/articles/" + Util.encPath(query) +
                "?pageSize=" + PAGE + "&page=" + page
        val root = JSONObject(Net.getJson(url))
        val arr = root.optJSONArray("results") ?: return@withContext emptyList()
        val out = ArrayList<PaperItem>()
        for (i in 0 until arr.length()) {
            val bj = arr.optJSONObject(i)?.optJSONObject("bibjson") ?: continue
            val title = bj.optString("title").trim()
            if (title.isEmpty()) continue

            var doi = ""
            val ids = bj.optJSONArray("identifier")
            if (ids != null) {
                for (k in 0 until ids.length()) {
                    val ido = ids.optJSONObject(k) ?: continue
                    if (ido.optString("type") == "doi") doi = Util.normDoi(ido.optString("id"))
                }
            }

            val authors = ArrayList<String>()
            bj.optJSONArray("author")?.let { a ->
                for (k in 0 until minOf(a.length(), 8)) {
                    a.optJSONObject(k)?.optString("name")?.takeIf { it.isNotBlank() }?.let { authors.add(it) }
                }
            }

            var pdf: String? = null
            var landing = ""
            bj.optJSONArray("link")?.let { ls ->
                for (k in 0 until ls.length()) {
                    val lo = ls.optJSONObject(k) ?: continue
                    val u = lo.optString("url").trim()
                    val ct = lo.optString("content_type")
                    if (!u.startsWith("http")) continue
                    if (ct.contains("pdf", true) || Util.looksPdf(u)) pdf = u
                    else if (landing.isEmpty()) landing = u
                }
            }
            val journal = bj.optJSONObject("journal")?.optString("title").orEmpty()
            out.add(
                PaperItem(
                    title = title,
                    authors = authors.joinToString(", "),
                    venue = journal,
                    year = bj.optString("year").toIntOrNull() ?: 0,
                    abstractText = bj.optString("abstract").trim(),
                    doi = doi,
                    pdfUrl = pdf,
                    landingUrl = when {
                        landing.startsWith("http") -> landing
                        doi.isNotEmpty() -> Util.doiUrl(doi)
                        else -> null
                    },
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
