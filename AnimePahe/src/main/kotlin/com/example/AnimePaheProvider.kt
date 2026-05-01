package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element

/**
 * AnimePahe Provider for CloudStream 3
 *
 * Supports mirrors:
 *   animepahe.com / animepahe.pw / animepahe.org
 *
 * Architecture:
 *  - Uses AnimePahe's REST API (/api?m=...) for all data fetching
 *  - Videos are hosted on kwik.si — this provider uses CloudStream's
 *    built-in KwikExtractor to bypass its JS obfuscation (eval packing)
 *  - DDoS-Guard bypass via CloudflareKiller interceptor
 */
class AnimePaheProvider : MainAPI() {

    override var name = "AnimePahe"
    override var mainUrl = "https://animepahe.ru"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        val MIRRORS = listOf(
            "https://animepahe.ru",
            "https://animepahe.com",
            "https://animepahe.pw",
            "https://animepahe.org",
        )
    }

    // ─── Cloudflare / DDoS-Guard bypass ───────────────────────────────
    private val cfKiller = CloudflareKiller()
    override val interceptors: List<Interceptor> = listOf(cfKiller)

    private val baseHeaders get() = mapOf(
        "User-Agent"  to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer"     to "$mainUrl/",
        "Cookie"      to "__ddg1=; __ddg2=;",
    )

    // ─── Home Page ────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/api?m=airing&page=" to "Currently Airing",
        "$mainUrl/api?m=movie&l=12&page=" to "Movies",
        "$mainUrl/api?m=fivestar&l=12&page=" to "Top Rated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url  = request.data + page
        val json = app.get(url, headers = baseHeaders).parsedSafe<AnimePaheApiResponse>()
            ?: return HomePageResponse(listOf(HomePageList(request.name, emptyList())))
        val items = json.data?.mapNotNull { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(request.name, items, hasNext = json.next_page_url != null)
    }

    // ─── Search ───────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val json = app.get(
            "$mainUrl/api?m=search&q=${query.encodeUri()}",
            headers = baseHeaders,
        ).parsedSafe<AnimePaheSearchResponse>() ?: return emptyList()
        return json.data?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    private fun AnimePaheAnime.toSearchResult(): AnimeSearchResponse? {
        val slug   = session ?: return null
        val title  = title ?: return null
        val url    = "$mainUrl/anime/$slug"
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // ─── Load (Result Page) ───────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        // URL format: https://animepahe.ru/anime/{session}
        val session = url.substringAfterLast("/anime/")
        val doc     = app.get(url, headers = baseHeaders).document

        val title  = doc.selectFirst("h1.title-wrapper span")?.text()
                  ?: doc.selectFirst("h1")?.text()
                  ?: "Unknown"
        val poster = doc.selectFirst("div.anime-poster img")?.attr("src")
                  ?: doc.selectFirst("div.anime-cover")?.attr("data-src")
        val plot   = doc.selectFirst("div.anime-summary p")?.text()
                  ?: doc.selectFirst(".anime-synopsis")?.text()
        val tags   = doc.select("div.anime-genre a").eachText()
        val status = doc.select("div.anime-info p:contains(Status) a").text().let {
            when {
                it.contains("Finished", ignoreCase = true)  -> ShowStatus.Completed
                it.contains("Currently", ignoreCase = true) -> ShowStatus.Ongoing
                else -> null
            }
        }

        // Fetch all episode pages
        val episodes = mutableListOf<Episode>()
        var page = 1
        while (true) {
            val epJson = app.get(
                "$mainUrl/api?m=release&id=$session&sort=episode_asc&page=$page",
                headers = baseHeaders,
            ).parsedSafe<AnimePaheEpisodeResponse>() ?: break

            epJson.data?.forEach { ep ->
                val epSession = ep.session ?: return@forEach
                val epNum     = ep.episode ?: 0
                val epUrl     = "$mainUrl/play/$session/$epSession"
                episodes.add(newEpisode(epUrl) {
                    this.name    = "Episode $epNum${ep.title?.let { ": $it" } ?: ""}"
                    this.episode = epNum
                    this.posterUrl = ep.snapshot
                })
            }

            if (epJson.next_page_url == null) break
            page++
        }

        return newAnimeLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl  = poster
            this.plot       = plot
            this.tags       = tags
            this.showStatus = status
        }
    }

    // ─── Load Links (Kwik extractor) ─────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data = play page URL e.g. /play/{animeSession}/{epSession}
        val doc = app.get(data, headers = baseHeaders).document

        // All video links are Kwik embeds on the play page
        doc.select("div#pickDownload a, div.dropdown-item[data-src]").forEach { el ->
            val kwikUrl = el.attr("href").takeIf { it.isNotEmpty() }
                ?: el.attr("data-src").takeIf { it.isNotEmpty() }
                ?: return@forEach
            val quality = el.text().trim()
            runCatching {
                loadExtractor(kwikUrl, data, subtitleCallback) { link ->
                    callback(
                        ExtractorLink(
                            source  = name,
                            name    = "$name · $quality",
                            url     = link.url,
                            referer = link.referer,
                            quality = link.quality,
                            isM3u8  = link.isM3u8,
                        )
                    )
                }
            }
        }
        return true
    }

    // ─── Data Classes ─────────────────────────────────────────────────

    data class AnimePaheApiResponse(
        val data: List<AnimePaheAnime>?,
        val next_page_url: String?,
    )

    data class AnimePaheSearchResponse(
        val data: List<AnimePaheAnime>?,
    )

    data class AnimePaheAnime(
        val session: String?,
        val title: String?,
        val poster: String?,
    )

    data class AnimePaheEpisodeResponse(
        val data: List<AnimePaheEpisode>?,
        val next_page_url: String?,
    )

    data class AnimePaheEpisode(
        val session: String?,
        val episode: Int?,
        val title: String?,
        val snapshot: String?,
    )
}
