package com.seagull.paper.data

import com.seagull.paper.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/** 一个可下载的候选链接，附命中来源和该带上的 Referer（有些 PDF 站防盗链）。 */
data class Candidate(val url: String, val provider: String, val referer: String? = null)

data class ResolveStep(
    val provider: String,
    val ok: Boolean,
    val detail: String,
    /** 命中源自身的地址，用作下载 Referer。 */
    val src: String? = null
)

data class ResolveResult(val candidates: List<Candidate>, val steps: List<ResolveStep>) {
    val best: Candidate? get() = candidates.firstOrNull()
    val ok: Boolean get() = candidates.isNotEmpty()
}

/**
 * 全文解析链：论文元数据里没有 PDF 直链时，按顺序试——
 *   直链 → Unpaywall → OpenAlex → Europe PMC → 预印本改写 → Sci-Hub 镜像 → 落地页解析
 * 每步都留日志，UI 上能看见到底卡在哪一环。
 */
object PdfResolver {

    private suspend fun step(onStep: ((String) -> Unit)?, s: String) {
        withContext(Dispatchers.Main) { onStep?.invoke(s) }
    }

    suspend fun resolve(
        item: PaperItem,
        cfg: ResolverConfig = RemoteConfig.active,
        onStep: ((String) -> Unit)? = null
    ): ResolveResult = withContext(Dispatchers.IO) {
        val cands = LinkedHashMap<String, Candidate>()
        val steps = ArrayList<ResolveStep>()

        fun add(url: String?, provider: String, referer: String? = null) {
            val u = url?.trim().orEmpty()
            if (u.startsWith("http") && !cands.containsKey(u)) cands[u] = Candidate(u, provider, referer)
        }

        // 0) 源里已经带了直链
        if (item.hasPdf) {
            add(item.pdfUrl, item.sourceName.ifEmpty { "源直链" })
            steps.add(ResolveStep("源直链", true, item.pdfUrl.orEmpty()))
            return@withContext ResolveResult(cands.values.toList(), steps)
        }

        val doi = Util.normDoi(item.doi)
        val routes = cfg.routes.ifEmpty { ResolverConfig.DEFAULT_ROUTES }

        // 多收几个候选：第一个候选挂了还能换下一个（出版社防盗链/重定向循环很常见）
        val maxCandidates = 3
        for (route in routes) {
            if (cands.size >= maxCandidates) break
            when (route) {
                "unpaywall" -> if (doi.isNotEmpty()) {
                    step(onStep, "Unpaywall 查开放获取…")
                    val r = tryUnpaywall(doi, cfg)
                    steps.add(r)
                    if (r.ok) add(r.detail, "Unpaywall", r.src)
                    if (!r.ok) {
                        val alt = tryUnpaywallAll(doi, cfg)
                        alt.forEach { add(it, "Unpaywall", null) }
                    }
                }

                "openalex" -> if (doi.isNotEmpty()) {
                    step(onStep, "OpenAlex 查 OA 链接…")
                    val r = tryOpenAlex(doi, cfg)
                    steps.add(r)
                    if (r.ok) add(r.detail, "OpenAlex", r.src)
                }

                "europepmc" -> {
                    step(onStep, "Europe PMC 查全文…")
                    val r = tryEuropePmc(item, doi)
                    steps.add(r)
                    if (r.ok) add(r.detail, "Europe PMC", r.src)
                    // 备用形态：Europe PMC render 页 + NCBI PMC
                    val pmcid = r.detail.substringAfterLast('/', "").ifEmpty { r.src.orEmpty() }
                    val id = Regex("PMC\\d+").find(r.detail)?.value.orEmpty()
                    if (id.isNotEmpty()) {
                        add("https://europepmc.org/articles/" + id + "?pdf=render", "Europe PMC", "https://europepmc.org/")
                        add("https://www.ncbi.nlm.nih.gov/pmc/articles/" + id + "/pdf/", "PMC", "https://www.ncbi.nlm.nih.gov/")
                    }
                }

                "preprint" -> {
                    step(onStep, "试预印本直链…")
                    val r = tryPreprint(doi, item.title)
                    steps.add(r)
                    if (r.ok) add(r.detail, "预印本", r.src)
                }

                "scihub" -> if (doi.isNotEmpty() && cfg.scihubMirrors.isNotEmpty()) {
                    step(onStep, "Sci-Hub 镜像轮询…")
                    val r = trySciHub(doi, cfg, onStep)
                    steps.add(r)
                    if (r.ok) add(r.detail, "Sci-Hub", r.src)
                }

                "landing" -> {
                    step(onStep, "解析落地页找 PDF…")
                    val r = tryLanding(item, doi)
                    steps.add(r)
                    if (r.ok) add(r.detail, "落地页", r.src)
                }
            }
        }

        // 注意：不要把 doi.org 当候选——它永远返回落地页 HTML，
        // 只会让用户看到「DOI 返回的不是 PDF」这种假失败。抓不到就是抓不到。
        if (cands.isEmpty()) {
            steps.add(
                ResolveStep(
                    "结论", false,
                    if (doi.isEmpty()) "这条没有 DOI，只能靠落地页，页面里也没找到 PDF"
                    else "该文在各环节都没有免费全文（出版社付费墙，且 Sci-Hub 库里没有/镜像不可用）"
                )
            )
        }

        ResolveResult(cands.values.toList(), steps)
    }

    // ── 各环实现 ─────────────────────────────────────────────

    private fun tryUnpaywall(doi: String, cfg: ResolverConfig): ResolveStep {
        return try {
            val url = "https://api.unpaywall.org/v2/" + Util.encPath(doi) + "?email=" + Util.encPath(cfg.email)
            val o = JSONObject(Net.getJson(url))
            val best = o.optJSONObject("best_oa_location")
            val u = best?.optString("url_for_pdf").orEmpty().ifEmpty { best?.optString("url").orEmpty() }
            if (u.startsWith("http")) ResolveStep("Unpaywall", true, u)
            else {
                val alts = o.optJSONArray("oa_locations")
                var found = ""
                if (alts != null) {
                    for (i in 0 until alts.length()) {
                        val p = alts.optJSONObject(i)?.optString("url_for_pdf").orEmpty()
                        if (p.startsWith("http")) {
                            found = p
                            break
                        }
                    }
                }
                if (found.isNotEmpty()) ResolveStep("Unpaywall", true, found)
                else ResolveStep("Unpaywall", false, "没有开放获取版本")
            }
        } catch (e: Exception) {
            ResolveStep("Unpaywall", false, e.message ?: "失败")
        }
    }

    /** Unpaywall 的 oa_locations 里所有 PDF 链接都收进来，当备用候选。 */
    private fun tryUnpaywallAll(doi: String, cfg: ResolverConfig): List<String> {
        return try {
            val url = "https://api.unpaywall.org/v2/" + Util.encPath(doi) + "?email=" + Util.encPath(cfg.email)
            val o = JSONObject(Net.getJson(url))
            val arr = o.optJSONArray("oa_locations") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val u = arr.optJSONObject(i)?.optString("url_for_pdf").orEmpty()
                if (u.startsWith("http")) out.add(u)
            }
            out.take(4)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun tryOpenAlex(doi: String, cfg: ResolverConfig): ResolveStep {
        return try {
            val url = "https://api.openalex.org/works/" + Util.encPath(Util.doiUrl(doi)) +
                    "?mailto=" + Util.encPath(cfg.email)
            val o = JSONObject(Net.getJson(url))
            val best = o.optJSONObject("best_oa_location")?.optString("pdf_url").orEmpty()
            if (best.startsWith("http")) return ResolveStep("OpenAlex", true, best)
            val locs = o.optJSONArray("locations")
            if (locs != null) {
                for (i in 0 until locs.length()) {
                    val p = locs.optJSONObject(i)?.optString("pdf_url").orEmpty()
                    if (p.startsWith("http")) return ResolveStep("OpenAlex", true, p)
                }
            }
            ResolveStep("OpenAlex", false, "没有 OA 直链")
        } catch (e: Exception) {
            ResolveStep("OpenAlex", false, e.message ?: "失败")
        }
    }

    private fun tryEuropePmc(item: PaperItem, doi: String): ResolveStep {
        return try {
            val q = if (doi.isNotEmpty()) "doi:" + doi else item.title
            val url = "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=" +
                    Util.encPath(q) + "&format=json&resultType=core&pageSize=3"
            val arr = JSONObject(Net.getJson(url)).optJSONObject("resultList")?.optJSONArray("result")
                ?: return ResolveStep("Europe PMC", false, "无记录")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pmcid0 = o.optString("pmcid").trim()
                // PMC 的官方 REST 全文接口最稳（不带 CF 挑战、不校验 Referer、不 403）
                if (pmcid0.isNotEmpty()) {
                    return ResolveStep(
                        "Europe PMC", true,
                        "https://www.ebi.ac.uk/europepmc/webservices/rest/" + pmcid0 + "/fullTextPDF",
                        "https://europepmc.org/"
                    )
                }
                val list = o.optJSONObject("fullTextUrlList")?.optJSONArray("fullTextUrl")
                if (list != null) {
                    for (k in 0 until list.length()) {
                        val u = list.optJSONObject(k) ?: continue
                        val uu = u.optString("url").trim()
                        if (u.optString("documentStyle") == "pdf" && uu.startsWith("http")) {
                            return ResolveStep("Europe PMC", true, uu, "https://europepmc.org/")
                        }
                    }
                }
                val pmcid = pmcid0
                if (pmcid.isNotEmpty()) {
                    // 官方 REST 全文接口最稳（不带 Cloudflare 挑战、不校验 Referer）
                    return ResolveStep(
                        "Europe PMC", true,
                        "https://www.ebi.ac.uk/europepmc/webservices/rest/" + pmcid + "/fullTextPDF",
                        "https://europepmc.org/"
                    )
                }
            }
            ResolveStep("Europe PMC", false, "没有免费全文")
        } catch (e: Exception) {
            ResolveStep("Europe PMC", false, e.message ?: "失败")
        }
    }

    /** 预印本 DOI 直接改成 PDF：bioRxiv/medRxiv 的 10.1101，arXiv 的 10.48550。 */
    private fun tryPreprint(doi: String, title: String): ResolveStep {
        if (doi.isEmpty()) return ResolveStep("预印本", false, "没有 DOI")
        return when {
            doi.startsWith("10.1101/") -> ResolveStep(
                "预印本", true, "https://www.biorxiv.org/content/" + doi + "v1.full.pdf",
                "https://www.biorxiv.org/"
            )

            doi.contains("10.48550/arxiv", true) -> {
                val id = doi.substringAfterLast("arxiv.", "").ifEmpty { doi.substringAfter("arXiv/") }
                if (id.isNotEmpty()) ResolveStep("预印本", true, "https://arxiv.org/pdf/" + id)
                else ResolveStep("预印本", false, "arXiv ID 解析失败")
            }

            else -> ResolveStep("预印本", false, "不是预印本 DOI")
        }
    }

    private fun trySciHub(doi: String, cfg: ResolverConfig, onStep: ((String) -> Unit)?): ResolveStep {
        val errs = ArrayList<String>()
        for (m in cfg.scihubMirrors) {
            val base = m.trimEnd('/')
            val variants = listOf(base + "/" + doi, base + "/" + Util.doiUrl(doi))
            for (v in variants) {
                try {
                    val html = Net.getFast(v, referer = base + "/", uaOverride = Net.DESKTOP_UA)
                    val low = html.lowercase()
                    if (low.contains("article not found") || low.contains("статья не найдена")) {
                        errs.add(Util.host(base) + " 库里没有")
                        break
                    }
                    if (low.contains("are you a robot") || low.contains("altcha")) {
                        errs.add(Util.host(base) + " 要人机验证")
                        break
                    }
                    val pdf = findPdfIn(html, v)
                    if (pdf != null) return ResolveStep("Sci-Hub", true, pdf, base + "/")
                    errs.add(Util.host(base) + " 页面没解析出 PDF")
                    break
                } catch (e: Exception) {
                    errs.add(Util.host(base) + " " + (e.message ?: "失败"))
                }
            }
        }
        return ResolveStep("Sci-Hub", false, errs.joinToString("；").ifEmpty { "没有可用镜像" })
    }

    private fun tryLanding(item: PaperItem, doi: String): ResolveStep {
        val urls = LinkedHashSet<String>()
        item.landingUrl?.takeIf { it.startsWith("http") }?.let { urls.add(it) }
        if (doi.isNotEmpty()) urls.add(Util.doiUrl(doi))
        val errs = ArrayList<String>()
        for (u in urls) {
            try {
                if (Util.looksPdf(u)) return ResolveStep("落地页", true, u)
                val html = Net.get(u, uaOverride = Net.DESKTOP_UA)
                val pdf = findPdfIn(html, u)
                if (pdf != null) return ResolveStep("落地页", true, pdf, u)
                errs.add(Util.host(u) + " 页面里没有 PDF 链接")
            } catch (e: Exception) {
                errs.add(Util.host(u) + " " + (e.message ?: "失败"))
            }
        }
        return ResolveStep("落地页", false, errs.joinToString("；").ifEmpty { "没有落地页" })
    }

    /** 通用页面 PDF 抓取：meta 优先，其次 iframe/embed，最后正文链接。 */
    fun findPdfIn(html: String, base: String): String? {
        val metas = listOf(
            "citation_pdf_url",
            "eprints.document_url",
            "DC.identifier",
            "bepress_citation_pdf_url",
            "fulltext_pdf_url"
        )
        for (name in metas) {
            val re = Regex(
                "<meta[^>]+(?:name|property)\\s*=\\s*[\"']" + Regex.escape(name) +
                        "[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']",
                RegexOption.IGNORE_CASE
            )
            val m = re.find(html)
            if (m != null) {
                val u = Util.abs(base, m.groupValues[1])
                if (u != null && Util.looksPdf(u)) return u
                if (u != null && m.groupValues[1].contains("pdf", true)) return u
            }
        }

        val embedRe = Regex(
            "<(?:iframe|embed)[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        )
        for (m in embedRe.findAll(html)) {
            val raw = m.groupValues[1]
            if (raw.contains("pdf", true)) {
                val u = Util.abs(base, raw) ?: continue
                return u
            }
        }

        val linkRe = Regex("<a[^>]+href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.{0,120}?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (m in linkRe.findAll(html)) {
            val href = m.groupValues[1]
            val text = m.groupValues[2].replace(Regex("<[^>]+>"), " ").lowercase()
            val hit = Util.looksPdf(href) ||
                    href.contains("/pdf/", true) ||
                    href.contains("download", true) && text.contains("pdf") ||
                    text.contains("pdf") && href.contains("download", true)
            if (hit) {
                Util.abs(base, href)?.let { return it }
            }
        }

        val jsRe = Regex(
            "(?:location\\.href|window\\.location|location\\.replace)\\s*[=(]\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        )
        for (m in jsRe.findAll(html)) {
            val u = Util.abs(base, m.groupValues[1]) ?: continue
            if (Util.looksPdf(u)) return u
        }
        return null
    }

    /** 任意网页解析（阅读器里的「解析本页 PDF」）。 */
    suspend fun fromPageUrl(
        pageUrl: String,
        cfg: ResolverConfig = RemoteConfig.active,
        onStep: ((String) -> Unit)? = null
    ): ResolveResult = withContext(Dispatchers.IO) {
        val steps = ArrayList<ResolveStep>()
        if (Util.looksPdf(pageUrl)) {
            return@withContext ResolveResult(listOf(Candidate(pageUrl, "当前链接")), steps)
        }
        step(onStep, "抓取页面…")
        try {
            val html = Net.get(pageUrl, uaOverride = Net.DESKTOP_UA)
            val pdf = findPdfIn(html, pageUrl)
            if (pdf != null) {
                steps.add(ResolveStep("页面解析", true, pdf))
                return@withContext ResolveResult(listOf(Candidate(pdf, "页面解析")), steps)
            }
            steps.add(ResolveStep("页面解析", false, "页面里没找到 PDF 链接"))
        } catch (e: Exception) {
            steps.add(ResolveStep("页面解析", false, e.message ?: "抓取失败"))
        }

        // 页面上有 DOI 就当论文再跑一遍完整解析链
        val doi = extractDoi(pageUrl)
        if (doi.isNotEmpty()) {
            step(onStep, "发现 DOI：" + doi + "，跑完整解析链…")
            val r = resolve(
                PaperItem(title = doi, doi = doi, landingUrl = pageUrl),
                cfg, onStep
            )
            return@withContext ResolveResult(r.candidates, steps + r.steps)
        }
        ResolveResult(emptyList(), steps)
    }

    fun extractDoi(text: String): String {
        val m = Regex("10\\.\\d{4,9}/[-._;()/:a-zA-Z0-9]+").find(text) ?: return ""
        return Util.normDoi(m.value)
    }

    class NotPdfException(msg: String) : IOException(msg)
}
