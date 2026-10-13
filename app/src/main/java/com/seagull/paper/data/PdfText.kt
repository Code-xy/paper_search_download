package com.seagull.paper.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** PDF 取文本 + 章节切分（给 MCP 的 read_paper 用，对应 paper-search-mcp 的 extract_sections）。 */
object PdfText {

    @Volatile
    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        try {
            PDFBoxResourceLoader.init(ctx.applicationContext)
            inited = true
        } catch (e: Exception) {
            // 忽略：老版本上没这步也能跑
        }
    }

    fun extract(ctx: Context, file: File, maxPages: Int = 300): String {
        init(ctx)
        val doc = PDDocument.load(file)
        return doc.use {
            val pages = it.numberOfPages
            val s = PDFTextStripper()
            s.startPage = 1
            s.endPage = if (maxPages in 1..pages) maxPages else pages
            s.getText(it)
        }
    }

    private val NUMBERED = Regex("^\\s*(\\d+(?:\\.\\d+)*)\\s*[\\.\\)]?\\s+\\S.{0,70}$")
    private val NAMED = Regex(
        "^\\s*(abstract|introduction|background|related work|literature review|materials? and methods?|methods?|" +
                "methodology|results?|experiments?|evaluation|discussion|conclusions?|summary|references|" +
                "acknowledge?ments?|appendix(?:\\s+[A-Z])?|funding|limitations?|future work)\\s*:?\\s*$",
        RegexOption.IGNORE_CASE
    )

    /** 返回 (标题, 正文) 列表；切不出来就整篇算一节。 */
    fun sections(text: String, maxSections: Int = 40, maxCharsPerSection: Int = 4000): List<Pair<String, String>> {
        val lines = text.split('\n')
        val out = ArrayList<Pair<String, String>>()
        var title = "正文"
        val buf = StringBuilder()
        var started = false

        fun flush() {
            val body = buf.toString().trim()
            if (body.isNotEmpty()) {
                out.add(title to (if (body.length > maxCharsPerSection) body.substring(0, maxCharsPerSection) else body))
            }
            buf.setLength(0)
        }

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) {
                buf.append('\n')
                continue
            }
            val isHeading = line.length <= 90 && (NAMED.matches(line) || NUMBERED.matches(line))
            if (isHeading && started) {
                flush()
                title = line.trim()
            } else if (isHeading) {
                flush()
                title = line.trim()
                started = true
            } else {
                started = true
                buf.append(line).append('\n')
            }
            if (out.size >= maxSections) break
        }
        flush()
        if (out.isEmpty()) return listOf("全文" to text.take(maxCharsPerSection))
        return out
    }
}
