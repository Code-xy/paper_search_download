package com.seagull.paper.data

import com.seagull.paper.Net

/** 没有 DOI/直链时用来跳转的网页搜索站（抓不到结果时至少能一键去网页看）。 */
data class PreviewSite(
    val name: String = "Google 学术",
    val searchUrlTemplate: String = "https://scholar.google.com/scholar?hl=zh-CN&q={q}"
)

/** 解析链的可调参数，全部能从远程 sources.json 覆盖。 */
data class ResolverConfig(
    val email: String = "seagull@qq.com",
    val scihubMirrors: List<String> = DEFAULT_SCIHUB,
    val scholarMirrors: List<String> = DEFAULT_SCHOLAR,
    val routes: List<String> = DEFAULT_ROUTES,
    val timeoutMs: Long = 20000L
) {
    companion object {
        val DEFAULT_SCIHUB = listOf(
            "https://sci-hub.st",
            "https://sci-hub.se",
            "https://sci-hub.ru",
            "https://sci-hub.ee",
            "https://sci-hub.wf",
            "https://sci-hub.usualwant.com",
            "https://sci-hub.hkvisa.net"
        )
        val DEFAULT_SCHOLAR = listOf(
            "https://scholar.google.com",
            "https://scholar.lanfanshu.cn",
            "https://sc.panda985.com"
        )
        val DEFAULT_ROUTES = listOf("unpaywall", "openalex", "europepmc", "preprint", "scihub", "landing")
    }
}

/** 选择器驱动的自定义论文站：站点改版只改 JSON，不用重发版。 */
data class SiteSpec(
    val id: String,
    val name: String,
    val group: String = "自定义",
    val siteUrl: String = "",
    val urlTemplate: String = "",
    val itemSelector: String = "",
    val titleSelector: String = "a",
    val titleAttr: String = "text",
    val linkSelector: String = "a",
    val linkAttr: String = "href",
    val authorSelector: String = "",
    val venueSelector: String = "",
    val yearSelector: String = "",
    val abstractSelector: String = "",
    val pdfSelector: String = "",
    val pdfAttr: String = "href",
    val doiSelector: String = "",
    val note: String = "远程配置"
)

object RemoteConfig {

    /** 当前生效的解析参数：ViewModel 拉到远程配置后写进来，解析链和学术镜像都读它。 */
    @Volatile
    var active: ResolverConfig = ResolverConfig()

    const val CONFIG_URL = "https://raw.githubusercontent.com/Code-xy/paper_search_download/main/sources.json"

    data class Config(
        val resolver: ResolverConfig,
        val sites: List<SiteSpec>,
        val preview: PreviewSite
    )

    private fun strList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim().trimEnd('/')
            if (v.startsWith("http")) out.add(v)
        }
        return out
    }

    fun parse(json: String): Config {
        val o = org.json.JSONObject(json)

        val d = ResolverConfig()
        var resolver = d
        o.optJSONObject("resolver")?.let { r ->
            val sh = strList(r.optJSONArray("scihubMirrors")).ifEmpty { d.scihubMirrors }
            val sc = strList(r.optJSONArray("scholarMirrors")).ifEmpty { d.scholarMirrors }
            val rt = strList(r.optJSONArray("routes")).ifEmpty { d.routes }
            resolver = ResolverConfig(
                email = r.optString("email").trim().ifEmpty { d.email },
                scihubMirrors = sh,
                scholarMirrors = sc,
                routes = rt,
                timeoutMs = r.optLong("timeoutMs", d.timeoutMs)
            )
        }

        var preview = PreviewSite()
        o.optJSONObject("preview")?.let { p ->
            preview = PreviewSite(
                name = p.optString("name").trim().ifEmpty { PreviewSite().name },
                searchUrlTemplate = p.optString("searchUrlTemplate").trim()
                    .ifEmpty { PreviewSite().searchUrlTemplate }
            )
        }

        val sites = ArrayList<SiteSpec>()
        o.optJSONArray("sites")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val sid = s.optString("id").trim()
                val tpl = s.optString("urlTemplate").trim()
                val item = s.optString("itemSelector").trim()
                if (sid.isEmpty() || tpl.isEmpty() || item.isEmpty()) continue
                sites.add(
                    SiteSpec(
                        id = sid,
                        name = s.optString("name").trim().ifEmpty { sid },
                        group = s.optString("group").trim().ifEmpty { "自定义" },
                        siteUrl = s.optString("siteUrl").trim(),
                        urlTemplate = tpl,
                        itemSelector = item,
                        titleSelector = s.optString("titleSelector").trim().ifEmpty { "a" },
                        titleAttr = s.optString("titleAttr").trim().ifEmpty { "text" },
                        linkSelector = s.optString("linkSelector").trim().ifEmpty { "a" },
                        linkAttr = s.optString("linkAttr").trim().ifEmpty { "href" },
                        authorSelector = s.optString("authorSelector").trim(),
                        venueSelector = s.optString("venueSelector").trim(),
                        yearSelector = s.optString("yearSelector").trim(),
                        abstractSelector = s.optString("abstractSelector").trim(),
                        pdfSelector = s.optString("pdfSelector").trim(),
                        pdfAttr = s.optString("pdfAttr").trim().ifEmpty { "href" },
                        doiSelector = s.optString("doiSelector").trim(),
                        note = s.optString("note").trim().ifEmpty { "远程配置" }
                    )
                )
            }
        }
        return Config(resolver, sites, preview)
    }
}
