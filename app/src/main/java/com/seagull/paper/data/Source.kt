package com.seagull.paper.data

import com.seagull.paper.data.sources.ArxivSource
import com.seagull.paper.data.sources.CrossrefSource
import com.seagull.paper.data.sources.DoajSource
import com.seagull.paper.data.sources.EuropePmcSource
import com.seagull.paper.data.sources.GoogleScholarSource
import com.seagull.paper.data.sources.OpenAlexSource
import com.seagull.paper.data.sources.SemanticScholarSource

interface PaperSource {
    val id: String
    val name: String
    val group: String
    val siteUrl: String
    val note: String get() = ""
    suspend fun search(query: String, page: Int): List<PaperItem>
}

object SourceRegistry {
    val builtIn: List<PaperSource> = listOf(
        ArxivSource,
        GoogleScholarSource,
        SemanticScholarSource,
        OpenAlexSource,
        EuropePmcSource,
        CrossrefSource,
        DoajSource
    )
}
