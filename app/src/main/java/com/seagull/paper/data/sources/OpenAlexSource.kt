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

/** OpenAlex：2.5 亿条元数据，免费无限量，OA 链接给得最实在。 */
object OpenAlexSource : PaperSource {

    override val id: String = "openalex"
    override val name: String = "OpenAlex"
    override val group: String = "综合索引"
    override val siteUrl: String = "https://openalex.org"
    override val note: String = "元数据最全，OA 直链命中率高"

    private const val PAGE = 20

    override suspend fun search(query: String, page: Int): List<PaperItem> = withContext(Dispatchers.IO) {
        val url = "https://api.openalex.org/works?search=" + Util.encPath(query) +
                "&per-page=" + PAGE + "&page=" + page +
                "&mailto=" + Util.encPath(RemoteConfig.active.email)
        val root = JSONObject(Net.getJson(url))
        val arr = root.optJSONArray("results") ?: return@withContext emptyList()
        val out = ArrayList<PaperItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = (o.optString("title").ifEmpty { o.optString("display_name") }).trim()
            if (title.isEmpty()) continue

            val authors = ArrayList<String>()
            o.optJSONArray("authorships")?.let { a ->
                for (k in 0 until minOf(a.length(), 8)) {
                    a.optJSONObject(k)?.optJSONObject("author")?.optString("display_name")
                        ?.takeIf { it.isNotBlank() }?.let { authors.add(it) }
                }
            }

            val best = o.optJSONObject("best_oa_location") ?: o.optJSONObject("primary_location")
            val pdf = best?.optString("pdf_url")?.trim().orEmpty()
                .ifEmpty { firstPdfUrl(o.optJSONArray("locations")) }
            val venue = o.optJSONObject("primary_location")
                ?.optJSONObject("source")?.optString("display_name").orEmpty()
            val doi = Util.normDoi(o.optString("doi"))
            val oa = o.optJSONObject("open_access")?.optBoolean("is_oa", false) ?: false
            val landing = o.optString("primary_location").let { _ ->
                o.optJSONObject("primary_location")?.optString("landing_page_url").orEmpty()
            }

            out.add(
                PaperItem(
                    title = title,
                    authors = authors.joinToString(", "),
                    venue = venue,
                    year = o.optInt("publication_year", 0),
                    abstractText = rebuildAbstract(o.optJSONObject("abstract_inverted_index")),
                    doi = doi,
                    pdfUrl = pdf.ifEmpty { null },
                    landingUrl = when {
                        landing.startsWith("http") -> landing
                        doi.isNotEmpty() -> Util.doiUrl(doi)
                        else -> null
                    },
                    oa = oa || pdf.isNotEmpty(),
                    citations = o.optInt("cited_by_count", -1),
                    sourceId = id,
                    sourceName = name
                )
            )
        }
        out
    }

    private fun firstPdfUrl(locations: JSONArray?): String {
        if (locations == null) return ""
        for (i in 0 until locations.length()) {
            val u = locations.optJSONObject(i)?.optString("pdf_url").orEmpty().trim()
            if (u.startsWith("http")) return u
        }
        return ""
    }

    /** OpenAlex 的摘要是个倒排索引，拼回来给用户看片段。 */
    private fun rebuildAbstract(idx: JSONObject?): String {
        if (idx == null || idx.length() == 0) return ""
        val pairs = ArrayList<Pair<Int, String>>()
        val keys = idx.keys()
        while (keys.hasNext()) {
            val w = keys.next()
            val pos = idx.optJSONArray(w) ?: continue
            for (i in 0 until pos.length()) pairs.add(pos.optInt(i, 0) to w)
        }
        pairs.sortBy { it.first }
        val sb = StringBuilder()
        for (p in pairs) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(p.second)
        }
        return sb.toString()
    }
}
