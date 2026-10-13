package com.seagull.paper.data

/** 一条论文结果。pdfUrl 为空表示「有落地页但没找到 PDF 直链」，需要走解析链。 */
data class PaperItem(
    val title: String,
    val authors: String = "",
    val venue: String = "",
    val year: Int = 0,
    val abstractText: String = "",
    val doi: String = "",
    val pdfUrl: String? = null,
    val landingUrl: String? = null,
    val oa: Boolean = false,
    val citations: Int = -1,
    val sourceId: String = "",
    val sourceName: String = "",
    val extra: String = ""
) {
    /** 去重键：DOI 优先，没有就标题归一化。 */
    val key: String get() = Util.normDoi(doi).ifEmpty { Util.normTitle(title) }

    val hasPdf: Boolean get() = Util.looksPdf(pdfUrl)

    val citeText: String get() = if (citations >= 0) citations.toString() else ""

    /** 卡片上那个「谁 + 哪年 + 哪个期刊」。 */
    val byline: String
        get() {
            val parts = ArrayList<String>(3)
            if (authors.isNotBlank()) parts.add(authors)
            if (venue.isNotBlank()) parts.add(venue)
            if (year > 0) parts.add(year.toString())
            return parts.joinToString(" · ")
        }
}

sealed interface SourceEvent {
    data class Started(val sourceId: String, val sourceName: String) : SourceEvent
    data class Done(val sourceId: String, val sourceName: String, val items: List<PaperItem>, val ms: Long) : SourceEvent
    data class Failed(val sourceId: String, val sourceName: String, val message: String, val ms: Long) : SourceEvent
}

enum class SourceState { IDLE, RUNNING, DONE, EMPTY, FAILED }

data class SourceStatus(
    val id: String,
    val name: String,
    val state: SourceState = SourceState.IDLE,
    val count: Int = 0,
    val ms: Long = 0L,
    val message: String = ""
)

/** 单个条目当前的下载/解析状态，UI 卡片上直接显示。 */
data class TaskState(
    val running: Boolean = false,
    val done: Long = 0L,
    val total: Long = -1L,
    val stage: String = "",
    val error: String = "",
    val provider: String = "",
    val steps: List<ResolveStep> = emptyList()
) {
    val pct: Float
        get() = when {
            total > 0L -> (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            running -> 0.15f
            else -> 0f
        }

    val sizeText: String
        get() = if (total > 0L) Util.fmtSize(done) + " / " + Util.fmtSize(total)
        else if (done > 0L) Util.fmtSize(done) else ""
}
