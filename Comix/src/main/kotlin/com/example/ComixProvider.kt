package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * Comix Provider for CloudStream 3
 *
 * Source: comix.to
 *
 * Architecture:
 *  - HTML scraper using Jsoup CSS selectors
 *  - Manga reader — chapters are returned as image-page ExtractorLinks
 *    (CloudStream renders them as a horizontal/vertical reader)
 *  - Supports: Manga, Manhwa, Manhua
 */
class ComixProvider : MainAPI() {

    override var name = "Comix"
    override var mainUrl = "https://comix.to"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Others) // Manga type

    private val baseHeaders get() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer"    to "$mainUrl/",
    )

    // ─── Home Page ────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/home?page="              to "Latest Updates",
        "$mainUrl/browse?sort=views&page=" to "Most Popular",
        "$mainUrl/browse?sort=new&page="   to "New Manga",
        "$mainUrl/browse?type=manhwa&page=" to "Manhwa",
        "$mainUrl/browse?type=manhua&page=" to "Manhua",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url  = request.data + page
        val doc  = app.get(url, headers = baseHeaders).document
        val items = doc.select("div.manga-item, div.item-manga, div.book-item")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ─── Search ───────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=${query.encodeUri()}", headers = baseHeaders).document
        return doc.select("div.manga-item, div.item-manga, div.search-item")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href   = fixUrl(anchor.attr("href"))
        val title  = selectFirst("h3, h4, .title, .manga-title")?.text()
               ?: anchor.attr("title").takeIf { it.isNotEmpty() }
               ?: return null
        val poster = selectFirst("img")?.let {
            it.attr("data-src").takeIf { s -> s.isNotEmpty() } ?: it.attr("src")
        }
        return newMovieSearchResponse(title, href, TvType.Others) {
            this.posterUrl = poster
        }
    }

    // ─── Load (Manga Detail Page) ─────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = baseHeaders).document

        val title  = doc.selectFirst("h1.manga-title, h1.title, h1")?.text() ?: "Unknown"
        val poster = doc.selectFirst("div.manga-poster img, div.cover img")?.let {
            it.attr("data-src").takeIf { s -> s.isNotEmpty() } ?: it.attr("src")
        }
        val plot = doc.selectFirst("div.manga-summary, div.description, .synopsis")?.text()
        val tags = doc.select("div.genres a, .genre-list a").eachText()
        val status = doc.selectFirst("span.status, div.status")?.text().let {
            when {
                it?.contains("Completed", ignoreCase = true) == true -> ShowStatus.Completed
                it?.contains("Ongoing", ignoreCase = true) == true   -> ShowStatus.Ongoing
                else -> null
            }
        }

        // Chapter list — each chapter becomes an Episode
        val chapters = doc.select("div.chapter-list a, ul.chapter-list li a, div.chapters a")
            .mapIndexedNotNull { idx, el ->
                val chUrl   = fixUrl(el.attr("href"))
                val chName  = el.text().takeIf { it.isNotEmpty() } ?: "Chapter ${idx + 1}"
                val chNum   = Regex("""[\d.]+""").find(chName)?.value?.toFloatOrNull()
                    ?: (idx + 1).toFloat()
                newEpisode(chUrl) {
                    this.name    = chName
                    this.episode = chNum.toInt()
                }
            }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime, chapters) {
            this.posterUrl  = poster
            this.plot       = plot
            this.tags       = tags
            this.showStatus = status
        }
    }

    // ─── Load Links (Chapter Pages as Images) ─────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = app.get(data, headers = baseHeaders).document

        // Extract all page image URLs from the chapter reader
        val images = doc.select(
            "div.chapter-images img, div.reader-content img, div#chapter-reader img, img.reader-image"
        ).mapNotNull { img ->
            img.attr("data-src").takeIf { it.isNotEmpty() }
                ?: img.attr("src").takeIf { it.isNotEmpty() }
        }

        images.forEachIndexed { idx, imgUrl ->
            callback(
                ExtractorLink(
                    source  = name,
                    name    = "Page ${idx + 1}",
                    url     = imgUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8  = false,
                )
            )
        }
        return images.isNotEmpty()
    }
}
