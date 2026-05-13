package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * AniKoto Provider for CloudStream 3
 *
 * Site: https://anikototv.to
 *
 * Architecture:
 *  - Uses the PUBLIC REST API at https://anikotoapi.site
 *      GET /recent-anime?page={n}&per_page=20   → paginated anime list
 *      GET /series/{id}                          → anime detail + full episode list
 *        each episode has:
 *          episode_embed_id  (same ID system as legacy HiAnime)
 *          embed_url.sub     → iframe URL for sub track
 *          embed_url.dub     → iframe URL for dub track (if available)
 *
 *  - Streams come from megaplay.buzz via the embed iframe.
 *    Direct m3u8 extraction:
 *      https://megaplay.buzz/stream/s-2/{episode_embed_id}/{language}
 *    Also supports MAL / AniList ID approach:
 *      https://megaplay.buzz/stream/mal/{mal_id}/{ep_num}/{language}
 *      https://megaplay.buzz/stream/ani/{anilist_id}/{ep_num}/{language}
 *
 *  Rate limit: 60 requests / 120 seconds per IP
 */
class AniKotoProvider : MainAPI() {

    override var name        = "AniKoto"
    override var mainUrl     = "https://anikototv.to"
    override val hasMainPage = true
    override var lang        = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val API_BASE    = "https://anikotoapi.site"
        private const val STREAM_BASE = "https://megaplay.buzz/stream"
    }

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept"     to "application/json",
        "Referer"    to "$mainUrl/",
    )

    // ─── Home Page ────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$API_BASE/recent-anime?per_page=20&page=" to "Recently Updated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url  = request.data + page
        val json = app.get(url, headers = apiHeaders)
            .parsedSafe<AniKotoListResponse>()
            ?: return HomePageResponse(listOf(HomePageList(request.name, emptyList())))

        val items = json.data?.mapNotNull { it.toSearchResult() } ?: emptyList()
        val hasNext = json.pagination?.let {
            (it.current_page ?: 1) < (it.last_page ?: 1)
        } ?: false

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ─── Search ───────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        // AniKoto API doesn't expose a search endpoint publicly,
        // so we fall back to scraping the site's search page
        val doc = app.get(
            "$mainUrl/search?keyword=${query.encodeUri()}",
            headers = apiHeaders + mapOf("Accept" to "text/html"),
        ).document

        return doc.select("div.film_list-wrap div.flw-item, div.anime-list div.item")
            .mapNotNull { el ->
                val anchor = el.selectFirst("a[href]") ?: return@mapNotNull null
                val href   = fixUrl(anchor.attr("href"))
                val title  = el.selectFirst(".film-name, .title, h3")?.text()
                    ?: anchor.attr("title").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }
                // Extract numeric ID from URL slug  e.g. /watch/one-piece-0et8i → slug stored
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            }
    }

    // ─── Anime → SearchResponse helper ───────────────────────────────

    private fun AniKotoAnime.toSearchResult(): AnimeSearchResponse? {
        val id    = this.id ?: return null
        val title = this.title ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/series/$id", TvType.Anime) {
            this.posterUrl = this@toSearchResult.image
        }
    }

    // ─── Load (Detail Page) ───────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        // URL formats:
        //   https://anikototv.to/series/1234          (from our search result)
        //   https://anikototv.to/watch/slug-0et8i     (user-pasted link)
        val animeId = extractAnimeId(url)
            ?: throw ErrorLoadingException("Cannot determine anime ID from: $url")

        val detail = app.get("$API_BASE/series/$animeId", headers = apiHeaders)
            .parsedSafe<AniKotoDetailResponse>()
            ?: throw ErrorLoadingException("API returned no data for id=$animeId")

        val anime   = detail.anime ?: throw ErrorLoadingException("No anime in response")
        val title   = anime.title ?: "Unknown"
        val poster  = anime.image
        val plot    = anime.description
        val tags    = anime.genres?.map { it.name ?: "" }?.filter { it.isNotEmpty() }
        val status  = when (anime.status?.lowercase()) {
            "finished", "completed" -> ShowStatus.Completed
            "airing", "ongoing"     -> ShowStatus.Ongoing
            else                    -> null
        }

        // Build episodes — both sub and dub listed separately
        val episodes = mutableListOf<Episode>()
        detail.episodes?.forEach { ep ->
            val epNum     = ep.number ?: return@forEach
            val embedId   = ep.episode_embed_id ?: return@forEach
            val epTitle   = ep.title

            // Sub track
            if (ep.embed_url?.sub != null) {
                episodes.add(newEpisode("$embedId|sub") {
                    this.name      = "Ep $epNum${if (!epTitle.isNullOrBlank()) ": $epTitle" else ""} [SUB]"
                    this.episode   = epNum
                    this.scanlator = "SUB"
                })
            }
            // Dub track
            if (ep.embed_url?.dub != null) {
                episodes.add(newEpisode("$embedId|dub") {
                    this.name      = "Ep $epNum${if (!epTitle.isNullOrBlank()) ": $epTitle" else ""} [DUB]"
                    this.episode   = epNum
                    this.scanlator = "DUB"
                })
            }
            // Fallback if neither embed_url provided but we have an embed_id
            if (ep.embed_url?.sub == null && ep.embed_url?.dub == null) {
                episodes.add(newEpisode("$embedId|sub") {
                    this.name      = "Ep $epNum${if (!epTitle.isNullOrBlank()) ": $epTitle" else ""}"
                    this.episode   = epNum
                })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime, episodes.sortedBy { it.episode }) {
            this.posterUrl  = poster
            this.plot       = plot
            this.tags       = tags
            this.showStatus = status
        }
    }

    // ─── Load Links (megaplay.buzz stream) ────────────────────────────

    override suspend fun loadLinks(
        data: String,          // "{episode_embed_id}|{sub|dub}"
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts     = data.split("|")
        val embedId   = parts.getOrNull(0) ?: return false
        val language  = parts.getOrNull(1) ?: "sub"

        // Direct stream endpoint from megaplay.buzz
        // Returns m3u8 HLS or redirects to mp4
        val streamUrl = "$STREAM_BASE/s-2/$embedId/$language"

        // Try to load via CloudStream's built-in extractor first
        val extracted = runCatching {
            loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
        }.getOrDefault(false)

        if (!extracted) {
            // Fallback: fetch the embed page and extract m3u8 directly
            runCatching {
                val embedPage = app.get(streamUrl, headers = apiHeaders + mapOf(
                    "Accept" to "text/html,application/xhtml+xml",
                    "Referer" to "$mainUrl/",
                )).document

                // Look for m3u8 in page source
                val pageSource = embedPage.html()
                val m3u8Regex  = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
                m3u8Regex.findAll(pageSource).forEach { match ->
                    callback(
                        ExtractorLink(
                            source  = name,
                            name    = "$name · ${language.uppercase()}",
                            url     = match.value,
                            referer = streamUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8  = true,
                        )
                    )
                }
            }
        }

        return true
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    /**
     * Extract a numeric anime ID from various URL formats:
     *  /series/1234           → 1234
     *  /watch/one-piece-0et8i → need to look up via site (fallback to slug search)
     *  /anime/one-piece-1234  → 1234
     */
    private suspend fun extractAnimeId(url: String): String? {
        // Direct API URL format we set
        Regex("""/series/(\d+)""").find(url)?.groupValues?.getOrNull(1)?.let { return it }

        // anikototv.to/watch/slug  — scrape the page to find data-id or API id
        Regex("""/watch/([^/?#]+)""").find(url)?.groupValues?.getOrNull(1)?.let { slug ->
            runCatching {
                val doc = app.get("$mainUrl/watch/$slug", headers = apiHeaders + mapOf(
                    "Accept" to "text/html",
                )).document
                // Look for data-id attribute or JSON ld script
                doc.selectFirst("[data-id]")?.attr("data-id")?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
                // Try numeric suffix in slug  e.g. one-piece-100 → 100
                Regex("""(\d+)$""").find(slug)?.groupValues?.getOrNull(1)?.let { return it }
            }
        }
        return null
    }

    // ─── Data Classes ─────────────────────────────────────────────────

    data class AniKotoListResponse(
        val data: List<AniKotoAnime>?,
        val pagination: AniKotoPagination?,
        val ok: Boolean?,
    )

    data class AniKotoPagination(
        val current_page: Int?,
        val last_page: Int?,
        val total: Int?,
    )

    data class AniKotoAnime(
        val id: Int?,
        val title: String?,
        val image: String?,
        val status: String?,
        val episodes_count: Int?,
        val genres: List<AniKotoGenre>?,
    )

    data class AniKotoDetailResponse(
        val ok: Boolean?,
        val anime: AniKotoAnimeDetail?,
        val episodes: List<AniKotoEpisode>?,
    )

    data class AniKotoAnimeDetail(
        val id: Int?,
        val title: String?,
        val image: String?,
        val description: String?,
        val status: String?,
        val genres: List<AniKotoGenre>?,
        val mal_id: Int?,
        val anilist_id: Int?,
        val year: Int?,
    )

    data class AniKotoGenre(val name: String?)

    data class AniKotoEpisode(
        val number: Int?,
        val title: String?,
        val episode_embed_id: String?,
        val embed_url: AniKotoEmbedUrl?,
    )

    data class AniKotoEmbedUrl(
        val sub: String?,
        val dub: String?,
    )
}
