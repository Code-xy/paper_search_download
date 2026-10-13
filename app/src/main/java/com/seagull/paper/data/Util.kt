package com.seagull.paper.data

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Util {

    private val DOI_URL_RE = Regex("^(?:https?://)?(?:dx\\.)?doi\\.org/", RegexOption.IGNORE_CASE)
    private val NON_WORD = Regex("[^a-z0-9\\u4e00-\\u9fa5]+")
    private val ILLEGAL_FILE = Regex("[\\\\/:*?\"<>|\\r\\n\\t]+")

    fun enc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    /** URL 路径里用，空格要 %20 不要 +（arXiv / Scholar 都吃这个）。 */
    fun encPath(s: String): String = enc(s).replace("+", "%20")

    fun normDoi(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()
        s = DOI_URL_RE.replace(s, "")
        s = s.removePrefix("doi:").trim()
        return s.trim().trimEnd('.', ',', ')', ']').lowercase(Locale.US)
    }

    fun doiUrl(doi: String): String = "https://doi.org/" + normDoi(doi)

    fun normTitle(t: String): String = NON_WORD.replace(t.lowercase(Locale.US), " ").trim()

    fun abs(base: String?, url: String?): String? {
        if (url.isNullOrBlank()) return null
        val u = url.trim()
        return when {
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.startsWith("//") -> "https:" + u
            u.startsWith("/") && !base.isNullOrBlank() -> base.trimEnd('/') + u
            !base.isNullOrBlank() -> base.trimEnd('/') + "/" + u.trimStart('/')
            else -> u
        }
    }

    fun host(url: String?): String = try {
        val u = java.net.URI(url)
        u.host?.removePrefix("www.") ?: ""
    } catch (e: Exception) {
        ""
    }

    fun yearOf(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        val m = Regex("(19|20)\\d{2}").find(raw) ?: return 0
        return m.value.toIntOrNull() ?: 0
    }

    fun intOf(raw: String?): Int {
        if (raw.isNullOrBlank()) return -1
        val m = Regex("\\d[\\d, ]*").find(raw) ?: return -1
        return m.value.replace(",", "").replace(" ", "").toIntOrNull() ?: -1
    }

    fun fmtSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = 1024.0
        val mb = kb * 1024
        return when {
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> bytes.toString() + " B"
        }
    }

    fun fmtDate(ms: Long): String {
        if (ms <= 0L) return ""
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
        } catch (e: Exception) {
            ""
        }
    }

    fun repoDays(ms: Long): String {
        if (ms <= 0L) return ""
        val diff = System.currentTimeMillis() - ms
        val d = diff / 86400000L
        return when {
            diff < 3600000L -> "刚刚"
            diff < 86400000L -> (diff / 3600000L).toString() + " 小时前"
            d < 30 -> d.toString() + " 天前"
            else -> (d / 30).toString() + " 个月前"
        }
    }

    fun safeFileName(raw: String, max: Int = 96): String {
        var s = ILLEGAL_FILE.replace(raw, " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) s = "paper"
        if (s.length > max) s = s.substring(0, max).trim()
        return s
    }

    /** 论文文件名：作者-年份-标题.pdf，便于本地检索。 */
    fun paperFileName(item: PaperItem): String {
        val first = item.authors.split(",", ";", " and ", "，").firstOrNull()?.trim().orEmpty()
        val who = if (first.isEmpty()) "" else first.take(24) + " "
        val yr = if (item.year > 0) item.year.toString() + " " else ""
        val t = safeFileName(item.title, 80)
        return safeFileName((who + yr + t).trim(), 100).ifEmpty { "paper" } + ".pdf"
    }

    /** 看起来是不是一个 PDF 直链（没直链就得走解析）。 */
    fun looksPdf(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val s = url.lowercase(Locale.US)
        if (!s.startsWith("http")) return false
        if (s.contains(".pdf")) return true
        if (s.contains("pdf=render") || s.contains("type=pdf") || s.contains("/pdf/")) return true
        if (s.contains("download=true") && s.contains("pdf")) return true
        return false
    }
}
