package com.seagull.paper.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 轻量本地存储：搜索历史、收藏、源开关、解析设置、远程配置缓存。 */
class Store(ctx: Context) {

    private val sp = ctx.getSharedPreferences("paper", Context.MODE_PRIVATE)

    // ── 搜索历史 ──
    fun history(): List<String> = sp.getString(KEY_HIST, "").orEmpty()
        .split("\n").filter { it.isNotBlank() }

    fun addHistory(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        val list = history().filter { it != t }.toMutableList()
        list.add(0, t)
        sp.edit().putString(KEY_HIST, list.take(40).joinToString("\n")).apply()
    }

    fun clearHistory() = sp.edit().remove(KEY_HIST).apply()

    // ── 收藏 ──
    fun favs(): List<PaperItem> {
        val raw = sp.getString(KEY_FAV, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                PaperItem(
                    title = o.optString("title"),
                    authors = o.optString("authors"),
                    venue = o.optString("venue"),
                    year = o.optInt("year", 0),
                    abstractText = o.optString("abstract"),
                    doi = o.optString("doi"),
                    pdfUrl = o.optString("pdf").ifEmpty { null },
                    landingUrl = o.optString("landing").ifEmpty { null },
                    oa = o.optBoolean("oa", false),
                    citations = o.optInt("cites", -1),
                    sourceId = o.optString("sid"),
                    sourceName = o.optString("sname"),
                    extra = o.optString("extra")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveFav(list: List<PaperItem>) {
        val arr = JSONArray()
        for (m in list) {
            arr.put(JSONObject().apply {
                put("title", m.title)
                put("authors", m.authors)
                put("venue", m.venue)
                put("year", m.year)
                put("abstract", m.abstractText)
                put("doi", m.doi)
                put("pdf", m.pdfUrl.orEmpty())
                put("landing", m.landingUrl.orEmpty())
                put("oa", m.oa)
                put("cites", m.citations)
                put("sid", m.sourceId)
                put("sname", m.sourceName)
                put("extra", m.extra)
            })
        }
        sp.edit().putString(KEY_FAV, arr.toString()).apply()
    }

    /** 返回 true 表示这次是「加入收藏」。 */
    fun toggleFav(item: PaperItem): Boolean {
        val list = favs().toMutableList()
        val idx = list.indexOfFirst { it.key == item.key }
        if (idx >= 0) {
            list.removeAt(idx)
            saveFav(list)
            return false
        }
        list.add(0, item)
        saveFav(list)
        return true
    }

    // ── 源开关 ──
    fun disabledSources(): MutableSet<String> =
        sp.getStringSet(KEY_DISABLED, emptySet())?.toMutableSet() ?: mutableSetOf()

    fun setDisabled(id: String, disabled: Boolean) {
        val s = disabledSources()
        if (disabled) s.add(id) else s.remove(id)
        sp.edit().putStringSet(KEY_DISABLED, s).apply()
    }

    // ── 远程配置缓存 ──
    fun configCache(): String? = sp.getString(KEY_CFG, null)
    fun setConfigCache(json: String) = sp.edit().putString(KEY_CFG, json).apply()

    // ── 解析设置 ──
    var email: String
        get() = sp.getString(KEY_EMAIL, "").orEmpty()
        set(v) = sp.edit().putString(KEY_EMAIL, v.trim()).apply()

    var autoResolve: Boolean
        get() = sp.getBoolean(KEY_AUTO, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO, v).apply()

    var preferSciHub: Boolean
        get() = sp.getBoolean(KEY_SCIHUB, true)
        set(v) = sp.edit().putBoolean(KEY_SCIHUB, v).apply()

    /** 梯子的 HTTP 代理，例如 127.0.0.1:7890；空=直连。 */
    var proxy: String
        get() = sp.getString(KEY_PROXY, "").orEmpty()
        set(v) = sp.edit().putString(KEY_PROXY, v.trim()).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean(KEY_WIFI, false)
        set(v) = sp.edit().putBoolean(KEY_WIFI, v).apply()

    private companion object {
        const val KEY_HIST = "hist"
        const val KEY_FAV = "fav"
        const val KEY_DISABLED = "disabled"
        const val KEY_CFG = "cfg"
        const val KEY_EMAIL = "email"
        const val KEY_AUTO = "autoResolve"
        const val KEY_SCIHUB = "scihub"
        const val KEY_WIFI = "wifiOnly"
        const val KEY_PROXY = "proxy"
    }
}
