package com.seagull.paper.data

import com.seagull.paper.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** 网络自检：App 设置页和 MCP 的 diagnose 工具共用这一份。 */
object Diagnostics {

    data class Target(val name: String, val url: String, val ua: String? = null)

    fun targets(cfg: ResolverConfig, email: String): List<Target> = buildList {
        add(Target("梯子链路", "https://www.gstatic.com/generate_204"))
        add(Target("arXiv", "http://export.arxiv.org/api/query?search_query=all:test&max_results=1"))
        add(Target("OpenAlex", "https://api.openalex.org/works?search=test&per-page=1&mailto=" + Util.encPath(email)))
        add(Target("Crossref", "https://api.crossref.org/works?query=test&rows=1&mailto=" + Util.encPath(email)))
        add(Target("Europe PMC", "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=test&format=json&pageSize=1"))
        add(Target("DOAJ", "https://doaj.org/api/search/articles/test?pageSize=1"))
        add(Target("Semantic Scholar", "https://api.semanticscholar.org/graph/v1/paper/search?query=test&limit=1&fields=title"))
        add(Target("Unpaywall", "https://api.unpaywall.org/v2/10.1038/nature12373?email=" + Util.encPath(email)))
        cfg.scholarMirrors.take(2).forEach {
            add(Target("学术镜像 " + Util.host(it), it.trimEnd('/') + "/scholar?hl=zh-CN&q=test", Net.DESKTOP_UA))
        }
        cfg.scihubMirrors.take(4).forEach {
            add(Target("Sci-Hub " + Util.host(it), it.trimEnd('/') + "/10.1038/nature12373", Net.DESKTOP_UA))
        }
    }

    suspend fun run(cfg: ResolverConfig, email: String): List<DiagItem> = coroutineScope {
        targets(cfg, email).map { t ->
            async(Dispatchers.IO) { judge(t, Net.diag(t.url, accept = if (t.name == "arXiv") "application/atom+xml" else null, uaOverride = t.ua)) }
        }.awaitAll()
    }

    fun judge(t: Target, r: Net.Diag): DiagItem {
        val name = t.name
        val body = r.body.trimStart()
        val looksJson = body.startsWith("{") || body.startsWith("[") || body.startsWith("<?xml")
        val raw = r.detail
        val friendly = Net.friendly(r.detail)
        val detail = when {
            name == "梯子链路" -> when {
                r.ok -> if (Net.proxyOn) "代理可用（节点正常，Google 直通）" else "直连通（没配代理也通，运气好）"
                Net.proxyOn -> "代理配了但不通：" + friendly + " —— 去 Clash 里测节点延迟、换节点或更新订阅"
                else -> "没配代理：国内直连 Google 必挂，去设置里填端口"
            }

            !r.ok -> friendly
            name.startsWith("学术镜像") -> {
                val b = r.body.lowercase()
                when {
                    b.contains("gs_ri") -> "能抓到结果"
                    b.contains("captcha") || b.contains("unusual traffic") -> "被人机验证拦了"
                    else -> "页面里没有结果结构：" + Net.snippet(r.body, 36)
                }
            }

            name.startsWith("Sci-Hub") -> {
                val pdf = PdfResolver.findPdfIn(r.body, t.url)
                if (pdf != null) "命中全文：" + pdf.take(70) else "页面里没解析出 PDF（库里没有/要人机验证）"
            }

            else -> if (looksJson) "正常" else "返回的不是接口数据（被劫持/坏链路）：" + Net.snippet(r.body)
        }
        val ok = r.ok && when {
            name == "梯子链路" -> r.ok
            name.startsWith("学术镜像") -> detail == "能抓到结果"
            name.startsWith("Sci-Hub") -> detail.startsWith("命中全文")
            else -> looksJson
        }
        val extra = if (!r.ok && raw.isNotBlank() && raw != friendly) " ｜ " + raw.take(48) else ""
        return DiagItem(name, t.url, ok, r.ms, detail + extra + " · " + r.ms + "ms")
    }

    fun summary(list: List<DiagItem>): String {
        if (list.isEmpty()) return "自检没有结果"
        val sb = StringBuilder()
        sb.append("链路：").append(if (Net.proxyOn) "代理 " + Net.proxySpec else "直连").append('\n')
        sb.append("通过 ").append(list.count { it.ok }).append("/").append(list.size).append('\n')
        for (d in list) sb.append(if (d.ok) "[OK] " else "[FAIL] ").append(d.name).append(" — ").append(d.detail).append('\n')
        return sb.toString()
    }
}
