package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * MangaFire Provider for CloudStream 3
 *
 * Source: mangafire.to
 *
 * Architecture:
 *  - HTML scraper using Jsoup
 *  - MangaFire uses an AJAX API for chapter page images:
 *      GET /ajax/read/{chapterId}/list-images → JSON { images: [...] }
 *  - Chapter IDs are embedded in the chapter list anchor data attributes
 *  - Supports: Manga, Manhwa, Manhua, One-shot, Doujinshi
 */
class MangaFireProvider : MainAPI() {

    override var name = "MangaFire"
    override var mainUrl = "https://mangafire.to"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Others)

    private val baseHeaders get() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer"    to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest",
    )

    // ─── Home Page ────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/home?page="               to "Latest Updates",
        "$mainUrl/filter?sort=trending&page=" to "Trending",
        "$mainUrl/filter?sort=most_viewed&page=" to "Most Viewed",
        "$mainUrl/filter?sort=new&page="       to "New Releases",
        "$mainUrl/filter?type=manhwa&page="    to "Manhwa",
        "$mainUrl/filter?type=manhua&page="    to "Manhua",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url  = request.data + page
        val doc  = app.get(url, headers = baseHeaders).document
        val items = doc.select("div.unit .inner, div.manga-item, article.manga")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ─── Search ───────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/filter?keyword=${query.encodeUri()}",
            headers = baseHeaders,
        ).document
        return doc.select("div.unit .inner, div.manga-item, article.manga")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a.poster, a[href*='/manga/']") ?: return null
        val href   = fixUrl(anchor.attr("href"))
        val title  = selectFirst(".title, h3, h4")?.text()
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

        val title  = doc.selectFirst("h1.manga-name, h1.title, h1")?.text() ?: "Unknown"
        val poster = doc.selectFirst("div.manga-poster img, .poster img")?.let {
            it.attr("data-src").takeIf { s -> s.isNotEmpty() } ?: it.attr("src")
        }
        val plot   = doc.selectFirst("div.synopsis p, div.description, .manga-summary")?.text()
        val tags   = doc.select("div.genres a, .tag-list a, .genres a").eachText()
        val status = doc.selectFirst(".status, span:contains(Status)")
            ?.nextElementSibling()?.text()
            ?.let { s ->
                when {
                    s.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
                    s.contains("Releasing", ignoreCase = true) ||
                    s.contains("Ongoing",   ignoreCase = true) -> ShowStatus.Ongoing
                    else -> null
                }
            }

        // MangaFire chapter list — chapters have data-id attributes
        // URL stored as: "{mangaSlug}|{chapterId}"  so we can fetch images via AJAX
        val mangaSlug = url.substringAfterLast("/manga/")
        val chapters = doc.select("div#en-chapters ul li a, ul.chapter-list li a, .chapter-list a")
            .mapNotNull { el ->
                val chHref    = el.attr("href")
                val chapterId = el.attr("data-id").takeIf { it.isNotEmpty() }
                    ?: Regex("""/(\d+)""").find(chHref)?.groupValues?.getOrNull(1)
                    ?: return@mapNotNull null
                val chName = el.selectFirst(".name, span")?.text()
                    ?: el.text().takeIf { it.isNotEmpty() }
                    ?: "Chapter"
                val chNum  = Regex("""[\d.]+""").find(chName)?.value?.toFloatOrNull() ?: 0f
                newEpisode("$mangaSlug|$chapterId") {
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

    // ─── Load Links (Chapter Images via AJAX) ─────────────────────────

    override suspend fun loadLinks(
        data: String,        // "{mangaSlug}|{chapterId}"
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts     = data.split("|")
        val chapterId = parts.getOrNull(1) ?: return false

        // MangaFire AJAX endpoint for chapter page images
        val json = app.get(
            "$mainUrl/ajax/read/$chapterId/list-images",
            headers = baseHeaders + mapOf("Accept" to "application/json"),
        ).parsedSafe<MangaFireImagesResponse>() ?: return false

        val images = json.data?.images ?: json.images ?: return false

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

    // ─── Data Classes ─────────────────────────────────────────────────

    data class MangaFireImagesResponse(
        val data: MangaFireImageData?,
        val images: List<String>?, // fallback flat list
    )
    data class MangaFireImageData(val images: List<String>?)
}
