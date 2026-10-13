package com.seagull.paper.mcp

import android.app.Application
import com.seagull.paper.Net
import com.seagull.paper.data.Diagnostics
import com.seagull.paper.data.Downloader
import com.seagull.paper.data.PaperItem
import com.seagull.paper.data.PaperSource
import com.seagull.paper.data.PdfResolver
import com.seagull.paper.data.PdfText
import com.seagull.paper.data.RemoteConfig
import com.seagull.paper.data.SearchEngine
import com.seagull.paper.data.SourceCatalog
import com.seagull.paper.data.SourceEvent
import com.seagull.paper.data.Store
import com.seagull.paper.data.Util
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MCP 工具实现。全部复用 App 已有的源 / 解析链 / 下载器，
 * 语义对齐 openags/paper-search-mcp：search_* / resolve_* / download_* / read_*。
 */
class McpTools(private val app: Application) {

    private val store = Store(app)

    private fun email(): String = store.email.ifEmpty { RemoteConfig.active.email }

    private fun cfg() = RemoteConfig.active.copy(email = email())

    private fun catalog(): List<PaperSource> = SourceCatalog.build(store)

    // ── 工具声明 ─────────────────────────────────────────────

    private fun prop(type: String, desc: String) =
        JSONObject().put("type", type).put("description", desc)

    private fun strArr(desc: String) =
        JSONObject().put("type", "array").put("description", desc)
            .put("items", JSONObject().put("type", "string"))

    private fun tool(name: String, title: String, desc: String, props: JSONObject, required: Array<String>): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", props)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
        schema.put("additionalProperties", true)
        return JSONObject().put("name", name).put("title", title).put("description", desc).put("inputSchema", schema)
    }

    fun definitions(): JSONArray = JSONArray().apply {
        put(
            tool(
                "search_papers", "搜论文",
                "并发搜索多个学术源（arXiv / Google 学术 / Semantic Scholar / OpenAlex / Europe PMC / Crossref / DOAJ + 自定义源），" +
                        "返回标题、作者、年份、DOI、是否已有 PDF 直链和摘要片段。",
                JSONObject()
                    .put("query", prop("string", "关键词、论文标题或 DOI"))
                    .put("sources", strArr("只搜这些源 id（默认全部）。用 list_sources 看可用 id"))
                    .put("limit", prop("integer", "最多返回条数，默认 20，上限 50"))
                    .put("page", prop("integer", "页码，默认 1"))
                    .put("timeout_ms", prop("integer", "总超时毫秒，默认 25000")),
                arrayOf("query")
            )
        )
        put(
            tool(
                "list_sources", "列出源",
                "列出所有可用学术源（id、名称、分组、默认是否启用）。",
                JSONObject(), emptyArray()
            )
        )
        put(
            tool(
                "search_by_doi", "按 DOI 取元数据",
                "用 Crossref + OpenAlex 查一个 DOI 的完整元数据（标题、作者、期刊、年份、被引、OA 链接）。",
                JSONObject().put("doi", prop("string", "例如 10.1038/nature12373")),
                arrayOf("doi")
            )
        )
        put(
            tool(
                "resolve_pdf", "解析全文链接",
                "对一篇论文跑全文解析链：Unpaywall → OpenAlex → Europe PMC → 预印本改写 → Sci-Hub 镜像 → 落地页解析。" +
                        "返回候选 PDF 链接和每一步的结果（能看清卡在哪一环）。",
                JSONObject()
                    .put("doi", prop("string", "DOI（最准）"))
                    .put("url", prop("string", "落地页或 PDF 链接"))
                    .put("title", prop("string", "没有 DOI 时用标题兜底")),
                emptyArray()
            )
        )
        put(
            tool(
                "download_pdf", "下载 PDF",
                "解析并下载全文到本机（App 私有目录，可用 save_to_downloads 导出到系统下载目录）。返回本地路径、大小、命中来源。",
                JSONObject()
                    .put("doi", prop("string", "DOI"))
                    .put("url", prop("string", "PDF 直链或落地页"))
                    .put("title", prop("string", "标题（用于命名和兜底检索）"))
                    .put("filename", prop("string", "自定义文件名，可省")),
                emptyArray()
            )
        )
        put(
            tool(
                "list_downloads", "已下载清单",
                "列出本机已下载的 PDF（名称、大小、时间、绝对路径）。",
                JSONObject(), emptyArray()
            )
        )
        put(
            tool(
                "read_paper", "读论文正文",
                "读取已下载 PDF 的文字。mode=sections 时按标题切章节（Abstract/Introduction/Methods…），" +
                        "mode=text 时返回整篇；支持 offset/max_chars 分段读取大文件。",
                JSONObject()
                    .put("path", prop("string", "绝对路径或文件名"))
                    .put("doi", prop("string", "没给 path 时用 DOI/标题片段在已下载文件里找"))
                    .put("mode", prop("string", "text 或 sections，默认 text"))
                    .put("offset", prop("integer", "从第几个字符开始，默认 0"))
                    .put("max_chars", prop("integer", "最多返回多少字符，默认 12000")),
                emptyArray()
            )
        )
        put(
            tool(
                "get_citations", "引文关系",
                "用 OpenAlex 查这篇论文引用了谁 / 被谁引用。",
                JSONObject()
                    .put("doi", prop("string", "DOI"))
                    .put("direction", prop("string", "citing（被引）| referenced（参考文献）| both，默认 both"))
                    .put("limit", prop("integer", "每边最多条数，默认 15")),
                arrayOf("doi")
            )
        )
        put(
            tool(
                "save_to_downloads", "导出到系统下载目录",
                "把已下载的 PDF 导出到 /sdcard/Download/学术搜/，方便其他 App 访问。",
                JSONObject()
                    .put("path", prop("string", "绝对路径或文件名"))
                    .put("doi", prop("string", "用 DOI/标题片段匹配已下载文件")),
                emptyArray()
            )
        )
        put(
            tool(
                "diagnose", "网络自检",
                "逐个源测连通性：区分「没配代理 / 梯子节点挂了 / 被墙 / 人机验证 / 限流 / 返回被劫持」，排查搜不到结果的原因。",
                JSONObject(), emptyArray()
            )
        )
    }

    // ── 调用入口 ─────────────────────────────────────────────

    suspend fun call(name: String, args: JSONObject): JSONObject {
        McpHub.countCall()
        return try {
            val text = when (name) {
                "search_papers" -> searchPapers(args)
                "list_sources" -> listSources()
                "search_by_doi" -> searchByDoi(args)
                "resolve_pdf" -> resolvePdf(args)
                "download_pdf" -> downloadPdf(args)
                "list_downloads" -> listDownloads()
                "read_paper" -> readPaper(args)
                "get_citations" -> getCitations(args)
                "save_to_downloads" -> saveToDownloads(args)
                "diagnose" -> diagnoseTool()
                else -> throw IllegalArgumentException("未知工具：$name（用 tools/list 看可用工具）")
            }
            result(text, false)
        } catch (e: Exception) {
            McpHub.log("✗ $name：" + (e.message ?: e.javaClass.simpleName))
            result((e.message ?: e.javaClass.simpleName), true)
        }
    }

    private fun result(text: String, isError: Boolean): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", isError)

    // ── 工具实现 ─────────────────────────────────────────────

    private suspend fun searchPapers(args: JSONObject): String {
        val q = args.optString("query").trim()
        if (q.isEmpty()) throw IllegalArgumentException("query 不能为空")
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val page = args.optInt("page", 1).coerceAtLeast(1)
        val timeout = args.optLong("timeout_ms", 25000L).coerceIn(3000L, 60000L)
        val want = args.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

        val all = catalog()
        val list = if (want.isEmpty()) all else all.filter { it.id in want }
        if (list.isEmpty()) throw IllegalArgumentException("没有匹配的源：" + want.joinToString(","))

        val found = LinkedHashMap<String, PaperItem>()
        val stats = ArrayList<String>()
        var timedOut = false
        try {
            val r = withTimeoutOrNull(timeout) {
                SearchEngine.stream(list, q, page).collect { ev ->
                    when (ev) {
                        is SourceEvent.Done -> {
                            ev.items.forEach { if (!found.containsKey(it.key)) found[it.key] = it }
                            stats.add(ev.sourceName + " " + ev.items.size)
                        }

                        is SourceEvent.Failed -> stats.add(ev.sourceName + " 失败(" + ev.message.take(30) + ")")
                        else -> Unit
                    }
                }
                "done"
            }
            if (r == null) timedOut = true
        } catch (e: CancellationException) {
            // 慢源（比如 Google 学术走 WebView）超时会把收集协程取消掉，
            // 这里不能把整次调用判死——已经拿到的结果照样返回。
            timedOut = true
        }
        val sorted = found.values.sortedWith(
            compareByDescending<PaperItem> { it.hasPdf }.thenByDescending { it.citations }.thenByDescending { it.year }
        ).take(limit)

        val sb = StringBuilder()
        sb.append("关键词：").append(q).append("  第 ").append(page).append(" 页\n")
        sb.append("源统计：").append(if (stats.isEmpty()) "全部超时" else stats.joinToString(" · "))
        if (timedOut) sb.append("　（超时截断，已返回已拿到的部分）")
        sb.append('\n')
        sb.append("去重后取前 ").append(sorted.size).append(" 条（共 ").append(found.size).append(" 条）\n\n")
        sorted.forEachIndexed { i, p -> sb.append(paperLine(i + 1, p)).append('\n') }
        if (sorted.isEmpty()) sb.append("没有结果。建议先跑 diagnose 看链路，或换关键词。\n")
        return sb.toString()
    }

    private fun paperLine(i: Int, p: PaperItem): String {
        val sb = StringBuilder()
        sb.append(i).append(". ").append(p.title).append('\n')
        if (p.byline.isNotBlank()) sb.append("   ").append(p.byline).append('\n')
        sb.append("   源: ").append(p.sourceName)
        if (p.citeText.isNotEmpty()) sb.append("   被引: ").append(p.citeText)
        if (p.hasPdf) sb.append("   PDF: 直链可用") else if (p.oa) sb.append("   PDF: 开放获取（需解析）")
        else sb.append("   PDF: 无直链（需解析）")
        sb.append('\n')
        if (p.doi.isNotEmpty()) sb.append("   DOI: ").append(p.doi).append('\n')
        p.pdfUrl?.let { sb.append("   PDF链接: ").append(it).append('\n') }
        if (p.abstractText.isNotBlank()) sb.append("   摘要: ").append(p.abstractText.take(240)).append('\n')
        return sb.toString()
    }

    private fun listSources(): String {
        val enabled = store.disabledSources()
        val sb = StringBuilder("可用源（用 search_papers 的 sources 参数指定）：\n")
        for (s in catalog()) {
            sb.append("- ").append(s.id).append("  ").append(s.name)
                .append("  [").append(s.group).append(']')
                .append(if (s.id in enabled) "  已关闭" else "  已启用")
                .append(if (s.note.isNotBlank()) "  — " + s.note else "")
                .append('\n')
        }
        return sb.toString()
    }

    private suspend fun searchByDoi(args: JSONObject): String = withContext(Dispatchers.IO) {
        val doi = Util.normDoi(args.optString("doi"))
        if (doi.isEmpty()) throw IllegalArgumentException("doi 不能为空")
        val out = StringBuilder("DOI: ").append(doi).append("\n\n")
        try {
            val cr = JSONObject(Net.getJson("https://api.crossref.org/works/" + Util.encPath(doi) + "?mailto=" + Util.encPath(email())))
                .optJSONObject("message")
            if (cr != null) {
                out.append("Crossref\n")
                out.append("  标题: ").append(cr.optJSONArray("title")?.optString(0).orEmpty()).append('\n')
                out.append("  期刊: ").append(cr.optJSONArray("container-title")?.optString(0).orEmpty()).append('\n')
                out.append("  年份: ").append(cr.optJSONObject("issued")?.optJSONArray("date-parts")?.optJSONArray(0)?.optInt(0, 0) ?: 0).append('\n')
                out.append("  被引: ").append(cr.optInt("is-referenced-by-count", -1)).append('\n')
                out.append("  链接: ").append(cr.optString("URL")).append("\n\n")
            }
        } catch (e: Exception) {
            out.append("Crossref 查询失败：").append(e.message).append("\n\n")
        }
        try {
            val oa = JSONObject(
                Net.getJson("https://api.openalex.org/works/" + Util.encPath(Util.doiUrl(doi)) + "?mailto=" + Util.encPath(email()))
            )
            out.append("OpenAlex\n")
            out.append("  标题: ").append(oa.optString("title")).append('\n')
            out.append("  引用数: ").append(oa.optInt("cited_by_count", -1)).append('\n')
            val oal = oa.optJSONObject("open_access")
            out.append("  开放获取: ").append(oal?.optBoolean("is_oa", false) ?: false)
                .append("  状态: ").append(oal?.optString("oa_status").orEmpty()).append('\n')
            val best = oa.optJSONObject("best_oa_location")
            out.append("  OA PDF: ").append(best?.optString("pdf_url").orEmpty().ifEmpty { "无" }).append('\n')
            out.append("  落地页: ").append(best?.optString("landing_page_url").orEmpty().ifEmpty { "无" }).append('\n')
        } catch (e: Exception) {
            out.append("OpenAlex 查询失败：").append(e.message).append('\n')
        }
        out.toString()
    }

    private fun itemFrom(args: JSONObject): PaperItem {
        val doi = Util.normDoi(args.optString("doi"))
        val url = args.optString("url").trim()
        val title = args.optString("title").trim()
        if (doi.isEmpty() && url.isEmpty() && title.isEmpty()) {
            throw IllegalArgumentException("至少给一个：doi / url / title")
        }
        return PaperItem(
            title = title.ifEmpty { if (doi.isNotEmpty()) doi else url },
            doi = doi,
            pdfUrl = if (Util.looksPdf(url)) url else null,
            landingUrl = if (url.startsWith("http") && !Util.looksPdf(url)) url else null,
            sourceName = "MCP"
        )
    }

    private suspend fun resolvePdf(args: JSONObject): String {
        val item = itemFrom(args)
        val res = PdfResolver.resolve(item, cfg()) { }
        val sb = StringBuilder()
        sb.append("论文: ").append(item.title).append('\n')
        if (item.doi.isNotEmpty()) sb.append("DOI: ").append(item.doi).append('\n')
        sb.append("候选 PDF: ").append(res.candidates.size).append(" 个\n")
        res.candidates.forEachIndexed { i, c ->
            sb.append("  ").append(i + 1).append(". [").append(c.provider).append("] ").append(c.url).append('\n')
        }
        sb.append("\n解析过程:\n")
        for (s in res.steps) {
            sb.append(if (s.ok) "  [OK]   " else "  [FAIL] ").append(s.provider).append(" — ").append(s.detail).append('\n')
        }
        if (!res.ok) sb.append("\n没有拿到可下载的 PDF。可以：换镜像（改 sources.json）、配代理、或用 diagnose 看链路。\n")
        return sb.toString()
    }

    private suspend fun downloadPdf(args: JSONObject): String {
        val item = itemFrom(args)
        val res = PdfResolver.resolve(item, cfg()) { }
        if (!res.ok) {
            val detail = res.steps.joinToString("；") { it.provider + ":" + it.detail }.take(300)
            throw IllegalStateException("解析不到可下载的 PDF（$detail）")
        }
        val name = args.optString("filename").trim().ifEmpty { Util.paperFileName(item) }
        val r = Downloader.download(app, res.candidates, name, item.landingUrl) { _, _ -> }
        McpHub.log("下载完成 " + r.file.name + " (" + Util.fmtSize(r.bytes) + " · " + r.provider + ")")
        return buildString {
            append("已下载: ").append(r.file.name).append('\n')
            append("大小: ").append(Util.fmtSize(r.bytes)).append('\n')
            append("路径: ").append(r.file.absolutePath).append('\n')
            append("命中来源: ").append(r.provider).append('\n')
            append("链接: ").append(r.url).append('\n')
            append("提示: 可用 read_paper 读正文，或 save_to_downloads 导出到系统下载目录。")
        }
    }

    private fun listDownloads(): String {
        val files = Downloader.list(app)
        if (files.isEmpty()) return "还没有下载过 PDF。先用 download_pdf 下几篇。"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("共 " + files.size + " 个 PDF，合计 " + Util.fmtSize(files.sumOf { it.length() }) + "：\n")
        files.forEach { f ->
            sb.append("- ").append(f.name).append("  ").append(Util.fmtSize(f.length()))
                .append("  ").append(fmt.format(Date(f.lastModified()))).append('\n')
            sb.append("  路径: ").append(f.absolutePath).append('\n')
        }
        return sb.toString()
    }

    private fun resolveFile(args: JSONObject): File {
        val path = args.optString("path").trim()
        if (path.isNotEmpty()) {
            val f = File(path)
            if (f.isFile) return f
            Downloader.list(app).firstOrNull { it.name.equals(path, true) }?.let { return it }
        }
        val key = listOf(args.optString("doi"), args.optString("name"), path)
            .firstOrNull { it.isNotBlank() }?.trim()?.lowercase(Locale.US).orEmpty()
        if (key.isEmpty()) throw IllegalArgumentException("给 path，或用 doi / name 匹配已下载文件")
        val hit = Downloader.list(app).firstOrNull { it.name.lowercase(Locale.US).contains(key.take(40)) }
        return hit ?: throw IllegalArgumentException("没找到匹配的文件：" + key + "（先 list_downloads 看看有哪些）")
    }

    private suspend fun readPaper(args: JSONObject): String = withContext(Dispatchers.IO) {
        val f = resolveFile(args)
        val mode = args.optString("mode", "text").lowercase(Locale.US)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val maxChars = args.optInt("max_chars", 12000).coerceIn(500, 60000)
        val text = PdfText.extract(app, f)
        val head = "文件: " + f.name + "  总字符: " + text.length + "  mode: " + mode + "\n\n"

        if (mode == "sections") {
            val secs = PdfText.sections(text)
            val sb = StringBuilder(head)
            secs.forEachIndexed { i, (t, body) ->
                sb.append("### ").append(i + 1).append(". ").append(t).append("  (").append(body.length).append(" 字)\n")
                sb.append(body.take(maxChars.coerceAtMost(2500))).append("\n\n")
            }
            sb.append("共 ").append(secs.size).append(" 节。需要某一节的全文就用 mode=text + offset 精确取。")
            return@withContext sb.toString()
        }

        val end = (offset + maxChars).coerceAtMost(text.length)
        if (offset >= text.length) return@withContext head + "offset 超出范围（总长 " + text.length + "）"
        val chunk = text.substring(offset, end)
        head + chunk + "\n\n[取字符 " + offset + "–" + end + " / " + text.length + "]" +
                if (end < text.length) "  继续读请用 offset=" + end else "  （已到结尾）"
    }

    private suspend fun getCitations(args: JSONObject): String = withContext(Dispatchers.IO) {
        val doi = Util.normDoi(args.optString("doi"))
        if (doi.isEmpty()) throw IllegalArgumentException("doi 不能为空")
        val dir = args.optString("direction", "both").lowercase(Locale.US)
        val limit = args.optInt("limit", 15).coerceIn(1, 50)
        val mail = Util.encPath(email())
        val w = JSONObject(
            Net.getJson("https://api.openalex.org/works/" + Util.encPath(Util.doiUrl(doi)) + "?mailto=" + mail)
        )
        if (w.optString("title").isEmpty()) throw IllegalStateException("OpenAlex 里没有这个 DOI：" + doi)
        val sb = StringBuilder()
        sb.append("论文: ").append(w.optString("title")).append('\n')
        sb.append("DOI: ").append(doi).append("   被引总数: ").append(w.optInt("cited_by_count", -1)).append("\n\n")

        if (dir == "citing" || dir == "both") {
            val api = w.optString("cited_by_api_url")
            sb.append("被谁引用：\n")
            if (api.isBlank()) sb.append("  （无）\n")
            else {
                val arr = JSONObject(Net.getJson(api + "&per-page=" + limit + "&mailto=" + mail)).optJSONArray("results")
                if (arr == null || arr.length() == 0) sb.append("  （无）\n")
                else for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    sb.append("  ").append(i + 1).append(". ").append(o.optString("title")).append('\n')
                    sb.append("     ").append(o.optInt("publication_year", 0))
                        .append("  引用数 ").append(o.optInt("cited_by_count", -1))
                        .append("  DOI ").append(Util.normDoi(o.optString("doi"))).append('\n')
                }
            }
            sb.append('\n')
        }

        if (dir == "referenced" || dir == "both") {
            val refs = w.optJSONArray("referenced_works")
            sb.append("它引用了谁：\n")
            if (refs == null || refs.length() == 0) sb.append("  （OpenAlex 没收录参考文献）\n")
            else {
                val ids = (0 until minOf(refs.length(), limit)).map {
                    refs.optString(it).substringAfterLast('/')
                }.filter { it.startsWith("W") }
                if (ids.isEmpty()) sb.append("  （无）\n") else {
                    val url = "https://api.openalex.org/works?filter=openalex_id:" + ids.joinToString("|") +
                            "&per-page=" + ids.size + "&mailto=" + mail
                    val arr = JSONObject(Net.getJson(url)).optJSONArray("results")
                    if (arr == null) sb.append("  （查询失败）\n")
                    else for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        sb.append("  ").append(i + 1).append(". ").append(o.optString("title")).append('\n')
                        sb.append("     ").append(o.optInt("publication_year", 0))
                            .append("  DOI ").append(Util.normDoi(o.optString("doi"))).append('\n')
                    }
                }
            }
        }
        sb.toString()
    }

    private suspend fun saveToDownloads(args: JSONObject): String {
        val f = resolveFile(args)
        val where = Downloader.export(app, f)
        return if (where == null) "导出失败（可能是系统限制或存储权限）" else "已导出到 " + where
    }

    private suspend fun diagnoseTool(): String {
        val list = Diagnostics.run(cfg(), email())
        return Diagnostics.summary(list)
    }
}
