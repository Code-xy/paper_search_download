package com.seagull.paper.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.seagull.paper.Net
import com.seagull.paper.data.DiagItem
import com.seagull.paper.data.Downloader
import com.seagull.paper.data.PaperItem
import com.seagull.paper.data.PaperSource
import com.seagull.paper.data.PdfResolver
import com.seagull.paper.data.PreviewSite
import com.seagull.paper.data.RemoteConfig
import com.seagull.paper.data.SearchEngine
import com.seagull.paper.data.SiteSpec
import com.seagull.paper.data.SourceEvent
import com.seagull.paper.data.SourceRegistry
import com.seagull.paper.data.SourceState
import com.seagull.paper.data.SourceStatus
import com.seagull.paper.data.Store
import com.seagull.paper.data.TaskState
import com.seagull.paper.data.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _items = MutableStateFlow<List<PaperItem>>(emptyList())
    val items = _items.asStateFlow()

    private val _status = MutableStateFlow<List<SourceStatus>>(emptyList())
    val status = _status.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()

    private val _allSources = MutableStateFlow<List<PaperSource>>(SourceRegistry.builtIn)
    val allSources = _allSources.asStateFlow()

    private val _disabled = MutableStateFlow<Set<String>>(emptySet())
    val disabled = _disabled.asStateFlow()

    private val _tasks = MutableStateFlow<Map<String, TaskState>>(emptyMap())
    val tasks = _tasks.asStateFlow()

    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files = _files.asStateFlow()

    private val _tip = MutableStateFlow("")
    val tip = _tip.asStateFlow()

    /** "" = 全部源；否则只看这一个源的结果。 */
    private val _filter = MutableStateFlow("")
    val filter = _filter.asStateFlow()

    private val _diag = MutableStateFlow<List<DiagItem>>(emptyList())
    val diag = _diag.asStateFlow()

    private val _diagRunning = MutableStateFlow(false)
    val diagRunning = _diagRunning.asStateFlow()

    val previewSite = MutableStateFlow(PreviewSite())
    val history = MutableStateFlow<List<String>>(emptyList())
    val favs = MutableStateFlow<List<PaperItem>>(emptyList())

    var email: String = ""
        private set

    var proxySpec: String = ""
        private set

    private var job: Job? = null
    private var page = 1
    private var lastQuery = ""
    private val seen = HashSet<String>()

    init {
        proxySpec = store.proxy
        Net.setProxyFromSpec(proxySpec)
        _disabled.value = store.disabledSources()
        history.value = store.history()
        favs.value = store.favs()
        email = store.email.ifEmpty { RemoteConfig.active.email }
        val cached = store.configCache()
        if (cached != null) _tip.value = "已载入缓存的源配置"
        rebuildSources(cached)
        refreshFiles()
    }

    // ── 源管理 ───────────────────────────────────────────────

    private fun rebuildSources(configJson: String?) {
        val list = ArrayList<PaperSource>(SourceRegistry.builtIn)
        if (configJson != null) {
            try {
                val cfg = RemoteConfig.parse(configJson)
                RemoteConfig.active = cfg.resolver.copy(
                    email = store.email.ifEmpty { cfg.resolver.email }
                )
                previewSite.value = cfg.preview
                cfg.sites.forEach { list.add(com.seagull.paper.data.sources.SelectorSource(it)) }
            } catch (e: Exception) {
                // 配置坏了就用内置的
            }
        }
        _allSources.value = list
    }

    val activeSources: List<PaperSource>
        get() = _allSources.value.filter { it.id !in _disabled.value }

    fun isEnabled(src: PaperSource): Boolean = src.id !in _disabled.value

    fun toggleSource(src: PaperSource, on: Boolean) {
        store.setDisabled(src.id, !on)
        _disabled.value = store.disabledSources()
        _allSources.value = _allSources.value.toList()
    }

    fun setQuery(q: String) { _query.value = q }

    fun setFilter(id: String) { _filter.value = id }

    /** 每个源当前命中多少条，给筛选芯片显示数量。 */
    fun countOf(sourceId: String): Int = _items.value.count { it.sourceId == sourceId }

    fun visibleItems(): List<PaperItem> =
        if (_filter.value.isEmpty()) _items.value else _items.value.filter { it.sourceId == _filter.value }

    fun saveProxy(spec: String) {
        store.proxy = spec
        proxySpec = spec.trim()
        Net.setProxyFromSpec(proxySpec)
        if (proxySpec.isEmpty()) {
            _tip.value = "已关闭代理（直连）"
            return
        }
        _tip.value = "代理已启用，正在探测可用性…"
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            val r = withContext(Dispatchers.IO) {
                Net.diag("https://www.google.com/generate_204")
            }
            val ms = System.currentTimeMillis() - t0
            _tip.value = if (r.ok) "代理可用 ✔ Google 通了（" + ms + "ms），现在去搜学术就有着落了"
            else "代理已保存，但探测失败：" + r.detail + "（检查梯子是否在跑、端口对不对）"
        }
    }

    /** 网络自检：把每个源的失败原因摊开，别只给用户看一个「失败」。 */
    fun runDiag() {
        if (_diagRunning.value) return
        _diagRunning.value = true
        _diag.value = emptyList()
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val cfg = RemoteConfig.active
            val mail = store.email.ifEmpty { cfg.email }
            val targets = listOf(
                Triple("arXiv", "http://export.arxiv.org/api/query?search_query=all:test&max_results=1", ""),
                Triple("OpenAlex", "https://api.openalex.org/works?search=test&per-page=1&mailto=" + Util.encPath(mail), ""),
                Triple("Crossref", "https://api.crossref.org/works?query=test&rows=1&mailto=" + Util.encPath(mail), ""),
                Triple("Europe PMC", "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=test&format=json&pageSize=1", ""),
                Triple("DOAJ", "https://doaj.org/api/search/articles/test?pageSize=1", ""),
                Triple(
                    "Semantic Scholar",
                    "https://api.semanticscholar.org/graph/v1/paper/search?query=test&limit=1&fields=title", ""
                ),
                Triple("Unpaywall", "https://api.unpaywall.org/v2/10.1038/nature12373?email=" + Util.encPath(mail), ""),
                Triple("doi.org", "https://doi.org/10.1038/nature12373", "")
            ) + cfg.scholarMirrors.take(2).map {
                Triple("学术镜像 " + Util.host(it), it.trimEnd('/') + "/scholar?hl=zh-CN&q=test", Net.DESKTOP_UA)
            } + cfg.scihubMirrors.take(4).map {
                Triple("Sci-Hub " + Util.host(it), it.trimEnd('/') + "/10.1038/nature12373", Net.DESKTOP_UA)
            }

            val out = coroutineScope {
                targets.map { (name, url, ua) ->
                    async(Dispatchers.IO) { diagOne(name, url, ua) }
                }.awaitAll()
            }
            _diag.value = out
            _diagRunning.value = false
            val bad = out.count { !it.ok }
            _tip.value = if (bad == 0) "自检全通（" + out.size + " 项）"
            else "自检完成：" + bad + "/" + out.size + " 项不通，红字就是原因"
        }
    }

    private fun diagOne(name: String, url: String, ua: String?): DiagItem {
        val r = Net.diag(url, accept = if (name == "arXiv") "application/atom+xml" else null, uaOverride = ua?.ifEmpty { null })
        val detail = when {
            !r.ok -> r.detail
            name.startsWith("学术镜像") -> {
                val b = r.body.lowercase()
                when {
                    b.contains("gs_ri") -> "能抓到结果"
                    b.contains("captcha") || b.contains("unusual traffic") -> "被人机验证拦了"
                    else -> "返回页面里没有结果结构"
                }
            }

            name.startsWith("Sci-Hub") -> {
                val pdf = PdfResolver.findPdfIn(r.body, url)
                if (pdf != null) "命中全文：" + pdf.take(70) else "页面里没解析出 PDF（库里可能没有）"
            }

            name == "doi.org" -> if (r.body.isNotEmpty()) "可达（会跳到出版社）" else "可达"
            else -> {
                val b = r.body.trimStart()
                val looksJson = b.startsWith("{") || b.startsWith("[") || b.startsWith("<?xml")
                when {
                    looksJson -> "正常"
                    else -> "能连上但返回的不是接口数据（可能被劫持/需要代理）"
                }
            }
        }
        return DiagItem(name, url, r.ok && detail != "返回页面里没有结果结构", r.ms, detail + " · " + r.ms + "ms")
    }

    // ── 搜索 ─────────────────────────────────────────────────

    fun search(q: String? = null, more: Boolean = false) {
        val text = (q ?: _query.value).trim()
        if (text.isEmpty()) return
        if (more && lastQuery.isEmpty()) return
        job?.cancel()
        page = if (more) page + 1 else 1
        if (!more) {
            lastQuery = text
            seen.clear()
            _items.value = emptyList()
            _tasks.value = emptyMap()
            store.addHistory(text)
            history.value = store.history()
        }
        _query.value = text
        val list = activeSources
        if (list.isEmpty()) {
            _status.value = emptyList()
            _searching.value = false
            _tip.value = "没有启用任何源，去设置里开几个"
            return
        }
        _tip.value = ""
        val query = if (more) lastQuery else text
        _status.value = list.map { SourceStatus(it.id, it.name, SourceState.RUNNING) }
        _searching.value = true
        job = viewModelScope.launch {
            SearchEngine.stream(list, query, page).collect { ev ->
                when (ev) {
                    is SourceEvent.Started -> mark(ev.sourceId, SourceState.RUNNING)
                    is SourceEvent.Done -> {
                        mark(
                            ev.sourceId,
                            if (ev.items.isEmpty()) SourceState.EMPTY else SourceState.DONE,
                            ev.items.size, ev.ms, ""
                        )
                        merge(ev.items)
                    }

                    is SourceEvent.Failed -> mark(ev.sourceId, SourceState.FAILED, 0, ev.ms, ev.message)
                }
            }
            _searching.value = false
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _searching.value = false
    }

    private fun mark(id: String, state: SourceState, count: Int = 0, ms: Long = 0L, msg: String = "") {
        _status.value = _status.value.map {
            if (it.id == id) it.copy(state = state, count = count, ms = ms, message = msg) else it
        }
    }

    private fun merge(newItems: List<PaperItem>) {
        val cur = _items.value.toMutableList()
        for (m in newItems) if (seen.add(m.key)) cur.add(m)
        _items.value = cur.sortedWith(
            compareByDescending<PaperItem> { if (it.hasPdf) 1 else 0 }
                .thenByDescending { it.citations }
                .thenByDescending { it.year }
        )
    }

    // ── 收藏 / 历史 ──────────────────────────────────────────

    fun toggleFav(item: PaperItem): Boolean {
        val added = store.toggleFav(item)
        favs.value = store.favs()
        return added
    }

    fun wifiOnly(): Boolean = store.wifiOnly

    fun setWifiOnly(on: Boolean) {
        store.wifiOnly = on
    }

    fun clearHistory() {
        store.clearHistory()
        history.value = emptyList()
    }

    // ── 配置 ─────────────────────────────────────────────────

    fun refreshConfig() {
        viewModelScope.launch {
            _tip.value = "正在拉取源配置…"
            try {
                val json = withContext(Dispatchers.IO) { Net.get(RemoteConfig.CONFIG_URL) }
                RemoteConfig.parse(json)
                store.setConfigCache(json)
                rebuildSources(json)
                val cfg = RemoteConfig.parse(json)
                _tip.value = "配置已更新：Sci-Hub " + cfg.resolver.scihubMirrors.size +
                        " 个镜像 · 学术镜像 " + cfg.resolver.scholarMirrors.size + " 个"
            } catch (e: Exception) {
                _tip.value = "配置更新失败：" + (e.message ?: "网络错误")
            }
        }
    }

    fun saveEmail(v: String) {
        store.email = v
        email = v.trim()
        RemoteConfig.active = RemoteConfig.active.copy(email = v.trim().ifEmpty { RemoteConfig.active.email })
    }

    // ── 下载 ─────────────────────────────────────────────────

    private fun setTask(key: String, st: TaskState) {
        _tasks.value = _tasks.value.toMutableMap().apply { put(key, st) }
    }

    fun taskOf(item: PaperItem): TaskState? = _tasks.value[item.key]

    fun download(item: PaperItem) {
        val key = item.key
        if (_tasks.value[key]?.running == true) return
        if (store.wifiOnly && !onWifi()) {
            setTask(key, TaskState(error = "开了仅 Wi-Fi 下载，当前不是 Wi-Fi"))
            return
        }
        setTask(key, TaskState(running = true, stage = "准备解析全文…"))
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val cfg = RemoteConfig.active.copy(email = store.email.ifEmpty { RemoteConfig.active.email })
            try {
                val res = PdfResolver.resolve(item, cfg) { s -> setTask(key, TaskState(running = true, stage = s)) }
                if (!res.ok) {
                    setTask(
                        key,
                        TaskState(
                            running = false,
                            error = res.steps.joinToString("；") { it.provider + ":" + it.detail }.take(180)
                                .ifEmpty { "没找到可下载的 PDF" },
                            steps = res.steps
                        )
                    )
                    return@launch
                }
                val provider = res.best?.provider.orEmpty()
                setTask(key, TaskState(running = true, stage = "下载中 · " + provider, provider = provider, steps = res.steps))
                val r = Downloader.download(ctx, res.candidates, Util.paperFileName(item), item.landingUrl) { d, t ->
                    setTask(key, TaskState(running = true, done = d, total = t, stage = "下载中 · " + provider, provider = provider))
                }
                setTask(
                    key,
                    TaskState(
                        running = false,
                        done = r.bytes,
                        total = r.bytes,
                        stage = "已完成 · " + r.provider + " · " + Util.fmtSize(r.bytes),
                        provider = r.provider,
                        steps = res.steps
                    )
                )
                refreshFiles()
                Actions.toast(ctx, "已下载：" + r.file.name)
            } catch (e: Exception) {
                setTask(key, TaskState(running = false, error = e.message ?: "下载失败"))
            }
        }
    }

    private fun onWifi(): Boolean {
        return try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return false
            val cap = cm.getNetworkCapabilities(n) ?: return false
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (e: Exception) {
            true
        }
    }

    fun refreshFiles() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _files.value = withContext(Dispatchers.IO) { Downloader.list(ctx) }
        }
    }

    fun exportFile(file: File) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val where = Downloader.export(ctx, file)
            Actions.toast(ctx, if (where == null) "导出失败（可能是系统限制）" else "已保存到 " + where)
        }
    }

    fun deleteFile(file: File) {
        val ctx = getApplication<Application>()
        if (file.delete()) {
            Actions.toast(ctx, "已删除")
            refreshFiles()
        } else {
            Actions.toast(ctx, "删除失败")
        }
    }

    fun filesSizeText(): String = Util.fmtSize(_files.value.sumOf { it.length() })

    fun previewUrl(item: PaperItem): String {
        val site = previewSite.value
        val q = item.title.ifBlank { item.doi }
        return site.searchUrlTemplate.replace("{q}", Util.encPath(q))
    }

    fun siteCount(): Int = _allSources.value.count { it is com.seagull.paper.data.sources.SelectorSource }

    fun remoteSiteSpecs(): List<SiteSpec> = emptyList()
}
