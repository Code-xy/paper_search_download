package com.seagull.paper.data

import com.seagull.paper.data.sources.SelectorSource

/** 源的完整集合：内置 + 远程选择器源。UI 和 MCP 都用这一份。 */
object SourceCatalog {
    fun build(store: Store): List<PaperSource> {
        val list = ArrayList<PaperSource>(SourceRegistry.builtIn)
        val cached = store.configCache() ?: return list
        return try {
            val cfg = RemoteConfig.parse(cached)
            cfg.sites.forEach { list.add(SelectorSource(it)) }
            list
        } catch (e: Exception) {
            list
        }
    }
}
