package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element

/**
 * AnimeKai Provider for CloudStream 3
 *
 * Supports mirror domains:
 *   animekai.to / animekai.gs / animekai.fo / animekai.fi / animekai.la / anikai.to / anigo.to
 *
 * Architecture:
 *  - Uses enc-dec.app to decrypt episode tokens and server IDs (same as Tachiyomi AnimeKai extension)
 *  - Video links served through MegaUp / Server 1 / Server 2 hosters
 *  - Supports Sub, Soft-Sub, and Dub tracks
 */
class AnimeKaiProvider : MainAPI() {

    override var name = "AnimeKai"
    override var mainUrl = "https://animekai.to"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // All known mirrors — user can switch via app Settings → Extension settings
    companion object {
        val MIRRORS = listOf(
            "https://animekai.to",
            "https://animekai.gs",
            "https://animekai.fo",
            "https://animekai.fi",
            "https://animekai.la",
            "https://anikai.to",
            "https://anigo.to",
        )
        private const val ENC_DEC_BASE = "https://enc-dec.app/api"
    }

    // ─── Cloudflare bypass ────────────────────────────────────────────
    private val cfKiller = CloudflareKiller()

    override val interceptors: List<Interceptor> = listOf(cfKiller)

    private val baseHeaders get() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
    )

    // ─── Home Page ────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/trending?page="     to "Trending",
        "$mainUrl/updates?page="      to "Recently Updated",
        "$mainUrl/browser?sort=recent_added&page=" to "Recently Added",
        "$mainUrl/browser?sort=top_airing&page="   to "Top Airing",
        "$mainUrl/browser?sort=most_popular&page=" to "Most Popular",
        "$mainUrl/browser?sort=completed&page="    to "Completed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val doc = app.get(url, headers = baseHeaders).document
        val items = doc.select("div.aitem-wrapper div.aitem").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ─── Search ───────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/browser?keyword=${query.encodeUri()}&page=1"
        val doc = app.get(url, headers = baseHeaders).document
        return doc.select("div.aitem-wrapper div.aitem").mapNotNull { it.toSearchResult() }
    }

    // ─── Element → SearchResponse ─────────────────────────────────────

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = selectFirst("a.title")?.text() ?: return null
        val href  = fixUrl(selectFirst("a.poster")?.attr("href") ?: return null)
        val poster = selectFirst("a.poster img")?.attr("data-src")
            ?: selectFirst("a.poster img")?.attr("src")
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // ─── Load (Result Page) ───────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = baseHeaders).document

        val title    = doc.selectFirst("h1.title")?.text()
                    ?: doc.selectFirst("h2.film-name")?.text()
                    ?: "Unknown"
        val poster   = doc.selectFirst(".poster img")?.attr("src")
        val plot     = doc.selectFirst(".desc")?.text()
        val tags     = doc.select("div.detail a[href*=genre]").eachText()
        val status   = doc.select("div.detail div:contains(Status:) a").text().let {
            when {
                it.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
                it.contains("Releasing", ignoreCase = true) -> ShowStatus.Ongoing
                else -> null
            }
        }

        val animeId = doc.selectFirst("div[data-id]")?.attr("data-id")
            ?: throw ErrorLoadingException("Anime ID not found in page")

        // Decrypt token for episode list endpoint
        val enc = encDec(animeId)
        val epListJson = app.get(
            "$mainUrl/ajax/episodes/list?ani_id=$animeId&_=$enc",
            headers = baseHeaders,
        ).parsedSafe<ResultResponse>()

        val epDoc = epListJson?.toDocument()
            ?: throw ErrorLoadingException("Failed to load episode list")

        val isMovie = doc.selectFirst("span:contains(Movie)") != null

        val episodes = epDoc.select("div.eplist a").mapNotNull { el ->
            val token  = el.attr("token").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val epNum  = el.attr("num").toFloatOrNull() ?: 1f
            val epName = el.selectFirst("span")?.text()
            val langs  = el.attr("langs").toIntOrNull() ?: 0
            val track  = when (langs) { 1 -> "Sub"; 3 -> "Sub+Dub"; else -> "" }
            newEpisode(token) {
                this.name          = "Episode ${epNum.toInt()}${if (!epName.isNullOrBlank()) ": $epName" else ""}"
                this.episode       = epNum.toInt()
                this.scanlator     = track
            }
        }.reversed()

        return if (isMovie && episodes.size == 1) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, episodes.first().data) {
                this.posterUrl = poster
                this.plot      = plot
                this.tags      = tags
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl    = poster
                this.plot         = plot
                this.tags         = tags
                this.showStatus   = status
            }
        }
    }

    // ─── Load Links (Video Sources) ───────────────────────────────────

    override suspend fun loadLinks(
        data: String,          // episode token
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val token = data
        val enc   = encDec(token)

        val serversDoc = app.get(
            "$mainUrl/ajax/links/list?token=$token&_=$enc",
            headers = baseHeaders,
        ).parsedSafe<ResultResponse>()?.toDocument()
            ?: return false

        serversDoc.select("div.server-items[data-id]").forEach { typeEl ->
            val trackType = typeEl.attr("data-id") // sub / softsub / dub
            val trackLabel = when (trackType) {
                "sub"     -> "[Hard Sub]"
                "softsub" -> "[Soft Sub]"
                "dub"     -> "[Dub]"
                else      -> trackType
            }

            typeEl.select("span.server[data-lid]").forEach { serverEl ->
                val lid        = serverEl.attr("data-lid")
                val serverName = serverEl.text()
                runCatching {
                    val lidEnc   = encDec(lid)
                    val encoded  = app.get(
                        "$mainUrl/ajax/links/view?id=$lid&_=$lidEnc",
                        headers = baseHeaders,
                    ).parsedSafe<ResultResponse>()?.result ?: return@runCatching

                    // Decrypt via enc-dec.app (same method as the Tachiyomi extension)
                    val iframeUrl = app.post(
                        "$ENC_DEC_BASE/dec-kai",
                        requestBody = """{"text":"$encoded"}""".toRequestBody(),
                        headers = baseHeaders + mapOf("Content-Type" to "application/json"),
                    ).parsedSafe<IframeResponse>()?.result?.url ?: return@runCatching

                    loadExtractor(iframeUrl, mainUrl, subtitleCallback) { link ->
                        callback(
                            ExtractorLink(
                                source  = link.source,
                                name    = "$serverName $trackLabel",
                                url     = link.url,
                                referer = link.referer,
                                quality = link.quality,
                                isM3u8  = link.isM3u8,
                            )
                        )
                    }
                }
            }
        }
        return true
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    /** Hit enc-dec.app to get the decryption parameter */
    private suspend fun encDec(text: String): String =
        app.get("$ENC_DEC_BASE/enc-kai?text=$text", headers = baseHeaders).text.trim()

    private fun String.toRequestBody() =
        okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), this)

    // ─── Data Classes ─────────────────────────────────────────────────

    data class ResultResponse(val result: String?) {
        fun toDocument() = org.jsoup.Jsoup.parse(result ?: "")
    }

    data class IframeResponse(val result: IframeResult?)
    data class IframeResult(val url: String?)
}
