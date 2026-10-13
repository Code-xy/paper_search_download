package com.seagull.paper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.seagull.paper.data.PaperItem
import com.seagull.paper.data.SourceStatus
import com.seagull.paper.data.TaskState
import com.seagull.paper.data.Util
import java.io.File

@Composable
fun RootScaffold() {
    val vm: SearchViewModel = viewModel()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("学术搜", "我的收藏", "已下载 PDF", "设置")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(titles[tab], fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("搜索") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    label = { Text("收藏") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = {
                        tab = 2
                        vm.refreshFiles()
                    },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    label = { Text("下载") }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> SearchScreen(vm)
                1 -> FavScreen(vm)
                2 -> DownloadScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun SearchScreen(vm: SearchViewModel) {
    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val query by vm.query.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val favs by vm.favs.collectAsStateWithLifecycle()
    val tip by vm.tip.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<PaperItem?>(null) }
    var errStatus by remember { mutableStateOf<SourceStatus?>(null) }
    val counts = items.groupingBy { it.sourceId }.eachCount()
    val shown = if (filter.isEmpty()) items else items.filter { it.sourceId == filter }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(4.dp))
        SearchField(
            value = query,
            onValueChange = vm::setQuery,
            onSearch = {
                keyboard?.hide()
                vm.search()
            },
            onClear = { vm.setQuery("") }
        )
        Spacer(Modifier.height(10.dp))

        if (status.isNotEmpty()) {
            SourceFilterRow(
                list = status,
                counts = counts,
                total = items.size,
                filter = filter,
                onPick = { vm.setFilter(it) },
                onError = { errStatus = it }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (filter.isEmpty()) "点源名只看了它一家，长按看失败原因" else "只看：" + (status.firstOrNull { it.id == filter }?.name ?: filter) + "（点「全部」返回）",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        when {
            shown.isEmpty() && !searching && filter.isEmpty() -> EmptyZone(
                history = history,
                tip = tip,
                onPick = { vm.search(it) },
                onClear = { vm.clearHistory() }
            )

            shown.isEmpty() && searching -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }

            shown.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "这个源这次没结果（长按它的芯片看原因）",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.key }) { item ->
                    PaperCard(
                        item = item,
                        task = tasks[item.key],
                        fav = favs.any { it.key == item.key },
                        onFav = {
                            val added = vm.toggleFav(item)
                            Actions.toast(ctx, if (added) "已收藏" else "已取消收藏")
                        },
                        onDownload = { vm.download(item) },
                        onOpen = {
                            val u = item.landingUrl
                            if (u != null) Actions.openReader(ctx, u, item.title)
                            else Actions.openReader(ctx, vm.previewUrl(item), item.title)
                        },
                        onCopyDoi = {
                            if (item.doi.isNotEmpty()) Actions.copy(ctx, Util.doiUrl(item.doi), "DOI 链接已复制")
                            else Actions.copy(ctx, item.title, "标题已复制")
                        },
                        onDetail = { detail = item }
                    )
                }
                item {
                    if (searching) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        TextButton(
                            onClick = { vm.search(null, true) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("加载更多") }
                    }
                }
            }
        }
    }

    detail?.let { d ->
        DetailSheet(
            item = d,
            task = tasks[d.key],
            onDismiss = { detail = null },
            onDownload = { vm.download(d) },
            onOpen = {
                val u = d.landingUrl
                Actions.openReader(ctx, u ?: vm.previewUrl(d), d.title)
            },
            onPreview = { Actions.openReader(ctx, vm.previewUrl(d), d.title) },
            onCopy = {
                if (d.doi.isNotEmpty()) Actions.copy(ctx, Util.doiUrl(d.doi), "DOI 链接已复制")
                else Actions.copy(ctx, d.title, "标题已复制")
            },
            onShare = {
                val t = if (d.doi.isNotEmpty()) d.title + "\n" + Util.doiUrl(d.doi) else d.title
                Actions.share(ctx, t)
            }
        )
    }

    errStatus?.let { st ->
        AlertDialog(
            onDismissRequest = { errStatus = null },
            title = { Text(st.name + " 失败了", fontSize = 15.sp) },
            text = {
                Text(
                    st.message.ifEmpty { "没有返回错误信息" } +
                            "\n\n常见原因：源被墙（去设置里配代理）、源改版、限流。设置页「网络自检」能一次看清所有源。",
                    fontSize = 12.5.sp
                )
            },
            confirmButton = { TextButton(onClick = { errStatus = null }) { Text("知道了") } }
        )
    }
}

@Composable
private fun EmptyZone(
    history: List<String>,
    tip: String,
    onPick: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "一次搜遍 arXiv / 学术 / OpenAlex / PMC 等",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.5.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "有直链的直接下；没有的自动解析全文再下",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        if (tip.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(tip, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        }
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("搜索历史", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("清空", fontSize = 12.sp)
                }
            }
            for (h in history) {
                AssistChip(
                    onClick = { onPick(h) },
                    label = { Text(h, fontSize = 12.5.sp) },
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun FavScreen(vm: SearchViewModel) {
    val ctx = LocalContext.current
    val favs by vm.favs.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<PaperItem?>(null) }

    if (favs.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("还没有收藏", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(favs, key = { it.key }) { item ->
            PaperCard(
                item = item,
                task = tasks[item.key],
                fav = true,
                onFav = {
                    vm.toggleFav(item)
                    Actions.toast(ctx, "已取消收藏")
                },
                onDownload = { vm.download(item) },
                onOpen = {
                    val u = item.landingUrl
                    Actions.openReader(ctx, u ?: vm.previewUrl(item), item.title)
                },
                onCopyDoi = {
                    if (item.doi.isNotEmpty()) Actions.copy(ctx, Util.doiUrl(item.doi), "DOI 链接已复制")
                    else Actions.copy(ctx, item.title, "标题已复制")
                },
                onDetail = { detail = item }
            )
        }
    }

    detail?.let { d ->
        DetailSheet(
            item = d,
            task = tasks[d.key],
            onDismiss = { detail = null },
            onDownload = { vm.download(d) },
            onOpen = {
                val u = d.landingUrl
                Actions.openReader(ctx, u ?: vm.previewUrl(d), d.title)
            },
            onPreview = { Actions.openReader(ctx, vm.previewUrl(d), d.title) },
            onCopy = {
                if (d.doi.isNotEmpty()) Actions.copy(ctx, Util.doiUrl(d.doi), "DOI 链接已复制")
                else Actions.copy(ctx, d.title, "标题已复制")
            },
            onShare = {
                val t = if (d.doi.isNotEmpty()) d.title + "\n" + Util.doiUrl(d.doi) else d.title
                Actions.share(ctx, t)
            }
        )
    }
}

@Composable
private fun DownloadScreen(vm: SearchViewModel) {
    val ctx = LocalContext.current
    val files by vm.files.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "共 " + files.size + " 个 PDF · " + vm.filesSizeText(),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.refreshFiles() }) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.size(4.dp))
                Text("刷新", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "还没下载过 PDF\n搜到结果点「解析并下载」",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(files, key = { it.absolutePath }) { f: File ->
                    FileRow(
                        name = f.name,
                        meta = Util.fmtSize(f.length()) + " · " + Util.fmtDate(f.lastModified()),
                        onOpen = { Actions.openFile(ctx, f) },
                        onExport = { vm.exportFile(f) },
                        onShare = { Actions.shareFile(ctx, f) },
                        onDelete = { vm.deleteFile(f) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: SearchViewModel) {
    val ctx = LocalContext.current
    val all by vm.allSources.collectAsStateWithLifecycle()
    val tip by vm.tip.collectAsStateWithLifecycle()
    var mail by remember { mutableStateOf(vm.email) }
    var wifiOnly by remember { mutableStateOf(vm.wifiOnly()) }
    var proxy by remember { mutableStateOf(vm.proxySpec) }
    val diag by vm.diag.collectAsStateWithLifecycle()
    val diagRunning by vm.diagRunning.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)
    ) {
        Text("搜索源", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "关掉的源不参与并发搜索。源越多越慢，建议常留 4~5 个。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        for (src in all) {
            SwitchRow(
                title = src.name,
                subtitle = src.group + " · " + src.note,
                checked = vm.isEnabled(src),
                onCheckedChange = { vm.toggleSource(src, it) }
            )
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(14.dp))

        Text("全文解析", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "没有 PDF 直链时的顺序：Unpaywall → OpenAlex → Europe PMC → 预印本改写 → Sci-Hub 镜像 → 落地页解析。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        Text("Unpaywall 联系邮箱（用它查开放获取，随便写个真邮箱）", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = mail,
            onValueChange = {
                mail = it
                vm.saveEmail(it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("you@example.com", fontSize = 13.sp) },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )
        Spacer(Modifier.height(10.dp))

        SwitchRow(
            title = "仅 Wi-Fi 下载",
            subtitle = "蜂窝网络下不自动下 PDF",
            checked = wifiOnly,
            onCheckedChange = {
                wifiOnly = it
                vm.setWifiOnly(it)
            }
        )

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(14.dp))

        Text("梯子 / 代理", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "手机上的 Clash / V2Ray 大多是「HTTP 代理」，不是全局 VPN。App 不显式走它 = 直连，" +
                    "直连 Google 学术、arXiv 这些基本必挂。填你梯子的 HTTP 代理地址，例：127.0.0.1:7890。" +
                    "Clash 的 mixed 端口也能填成 socks5://127.0.0.1:7890。留空 = 直连。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = proxy,
            onValueChange = { proxy = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("127.0.0.1:7890", fontSize = 13.sp) },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.saveProxy(proxy) }, shape = RoundedCornerShape(12.dp)) {
                Text("保存并生效")
            }
            Text(
                if (vm.proxySpec.isEmpty()) "当前：直连" else "当前：走代理 " + vm.proxySpec,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(18.dp))
        Text("网络自检", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "逐个请求每个源，把失败原因摆出来：被墙 / 人机验证 / 源改版 / 限流，一眼看清。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.runDiag() },
                enabled = !diagRunning,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (diagRunning) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp))
                    Text("自检中…")
                } else {
                    Text("开始自检")
                }
            }
            if (diag.isNotEmpty()) {
                Text(
                    "通 " + diag.count { it.ok } + " / 共 " + diag.size,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (diag.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            for (d in diag) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, if (d.ok) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error
                    )
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            (if (d.ok) "✅ " else "❌ ") + d.name,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            d.detail,
                            fontSize = 11.sp,
                            color = if (d.ok) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(14.dp))

        Text("源配置（远程热更新）", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "sci-hub 镜像和学术镜像都写在仓库根目录 sources.json 里。" +
                    "镜像挂了或被墙，改 JSON 就行，App 不用重装。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.refreshConfig() }, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("更新配置")
            }
            OutlinedButton(
                onClick = {
                    vm.clearHistory()
                    Actions.toast(ctx, "已清空搜索历史")
                },
                shape = RoundedCornerShape(12.dp)
            ) { Text("清空历史") }
        }
        if (tip.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(tip, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(24.dp))
        Text("关于", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "学术搜只做聚合：并发查公开学术接口和网页，把能下的 PDF 找出来帮你下到本地。" +
                    "下载的文件在 App 私有目录，可一键导出到系统「下载/学术搜」。" +
                    "Sci-Hub 是社区镜像，可用性随时会变，镜像列表在 sources.json 里。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun DetailSheet(
    item: PaperItem,
    task: TaskState?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
    onPreview: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(8.dp))
            Text(item.byline, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(item.sourceName, MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer)
                if (item.hasPdf) Pill("PDF 直链", MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.secondaryContainer)
                if (item.citeText.isNotEmpty()) Pill("被引 " + item.citeText, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
                if (item.year > 0) Pill(item.year.toString(), MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
            }

            if (item.abstractText.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text("摘要", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(3.dp))
                Text(item.abstractText, fontSize = 12.5.sp, lineHeight = 18.sp)
            }

            Spacer(Modifier.height(12.dp))
            KeyValue("DOI", item.doi)
            KeyValue("PDF 直链", item.pdfUrl.orEmpty())
            KeyValue("落地页", item.landingUrl.orEmpty())
            if (!task?.provider.isNullOrEmpty()) KeyValue("本次命中来源", task.provider)

            if (task != null && task.steps.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("解析过程", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                for (s in task.steps) {
                    Text(
                        (if (s.ok) "✅ " else "⚠️ ") + s.provider + "：" + s.detail,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = if (s.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (task?.error?.isNotEmpty() == true) {
                Spacer(Modifier.height(10.dp))
                Text(task.error, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(onClick = onDownload, shape = RoundedCornerShape(12.dp)) {
                    Text(if (item.hasPdf) "下载 PDF" else "解析并下载", fontSize = 13.sp)
                }
                TextButton(onClick = onOpen) { Text("网页", fontSize = 13.sp) }
                TextButton(onClick = onPreview) { Text("学术搜索", fontSize = 13.sp) }
                TextButton(onClick = onCopy) { Text("复制", fontSize = 13.sp) }
                TextButton(onClick = onShare) { Text("分享", fontSize = 13.sp) }
            }
        }
    }
}
