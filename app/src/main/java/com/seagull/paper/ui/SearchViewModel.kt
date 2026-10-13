package com.seagull.paper.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.seagull.paper.Net
import com.seagull.paper.data.DiagItem
import com.seagull.paper.data.Diagnostics
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

    /** 网络自检：把每个源的失败原因摊开（具体逻辑在 data/Diagnostics，MCP 的 diagnose 共用）。 */
    fun runDiag() {
        if (_diagRunning.value) return
        _diagRunning.value = true
        _diag.value = emptyList()
        viewModelScope.launch {
            val cfg = RemoteConfig.active.copy(email = store.email.ifEmpty { RemoteConfig.active.email })
            val out = Diagnostics.run(cfg, cfg.email)
            _diag.value = out
            _diagRunning.value = false
            val bad = out.count { !it.ok }
            _tip.value = if (bad == 0) "自检全通（" + out.size + " 项）"
            else "自检完成：" + bad + "/" + out.size + " 项不通，红字就是原因"
        }
    }

    // ── MCP 内嵌服务 ─────────────────────────────────────────

    val mcpState = com.seagull.paper.mcp.McpHub.state

    fun setMcpEnabled(on: Boolean) {
        store.mcpEnabled = on
        if (on) {
            store.mcpToken = store.mcpToken.ifEmpty { com.seagull.paper.mcp.McpHub.newToken() }
            com.seagull.paper.mcp.McpService.start(getApplication())
        } else {
            com.seagull.paper.mcp.McpService.stop(getApplication())
            com.seagull.paper.mcp.McpHub.stop()
        }
    }

    fun setMcpPort(port: Int) {
        store.mcpPort = port
        restartMcpIfRunning()
    }

    fun setMcpToken(token: String) {
        store.mcpToken = token.trim()
        restartMcpIfRunning()
    }

    fun regenMcpToken(): String {
        val t = com.seagull.paper.mcp.McpHub.newToken()
        store.mcpToken = t
        restartMcpIfRunning()
        return t
    }

    fun mcpEnabled(): Boolean = store.mcpEnabled

    fun mcpPort(): Int = store.mcpPort

    fun mcpToken(): String = store.mcpToken

    private fun restartMcpIfRunning() {
        if (mcpState.value.running) {
            com.seagull.paper.mcp.McpService.start(getApplication())
        }
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
