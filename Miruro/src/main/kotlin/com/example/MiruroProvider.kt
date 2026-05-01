package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

/**
 * Miruro Provider for CloudStream 3
 *
 * Supports mirrors:
 *   miruro.online / miruro.to / miruro.com / miruro.tv / miruro.bz
 *
 * Architecture:
 *  - Uses AniList GraphQL API for metadata (titles, descriptions, genres, etc.)
 *  - Uses Miruro's own backend API (/api/episodes, /api/stream) for episode lists
 *    and direct m3u8 HLS streams
 *  - Both Sub and Dub audio tracks are supported
 */
class MiruroProvider : MainAPI() {

    override var name = "Miruro"
    override var mainUrl = "https://www.miruro.tv"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        val MIRRORS = listOf(
            "https://www.miruro.tv",
            "https://www.miruro.online",
            "https://www.miruro.to",
            "https://www.miruro.com",
            "https://www.miruro.bz",
        )
        private const val ANILIST_API = "https://graphql.anilist.co"
        private val ANILIST_POPULAR_QUERY = """
            query (${"$"}page: Int, ${"$"}perPage: Int) {
              Page(page: ${"$"}page, perPage: ${"$"}perPage) {
                media(type: ANIME, sort: POPULARITY_DESC, isAdult: false) {
                  id title { romaji english } coverImage { large } episodes status genres
                }
              }
            }
        """.trimIndent()
        private val ANILIST_TRENDING_QUERY = """
            query (${"$"}page: Int, ${"$"}perPage: Int) {
              Page(page: ${"$"}page, perPage: ${"$"}perPage) {
                media(type: ANIME, sort: TRENDING_DESC, isAdult: false) {
                  id title { romaji english } coverImage { large } episodes status genres
                }
              }
            }
        """.trimIndent()
        private val ANILIST_SEARCH_QUERY = """
            query (${"$"}search: String, ${"$"}page: Int) {
              Page(page: ${"$"}page, perPage: 20) {
                media(type: ANIME, search: ${"$"}search, isAdult: false) {
                  id title { romaji english } coverImage { large } episodes status genres
                }
              }
            }
        """.trimIndent()
        private val ANILIST_DETAIL_QUERY = """
            query (${"$"}id: Int) {
              Media(id: ${"$"}id, type: ANIME) {
                id title { romaji english } description coverImage { extraLarge } 
                episodes status genres averageScore startDate { year }
                studios(isMain: true) { nodes { name } }
              }
            }
        """.trimIndent()
    }

    private val jsonHeaders = mapOf(
        "Content-Type" to "application/json",
        "Referer" to "$mainUrl/",
    )

    // ─── Home Page ────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "popular"  to "Most Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val query = if (request.data == "trending") ANILIST_TRENDING_QUERY else ANILIST_POPULAR_QUERY
        val body  = """{"query":${query.toJson()},"variables":{"page":$page,"perPage":20}}"""
        val json  = app.post(ANILIST_API, requestBody = body.toRequestBody(), headers = jsonHeaders)
            .parsedSafe<AniListPageResponse>()
        val items = json?.data?.Page?.media?.mapNotNull { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    // ─── Search ───────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val body = """{"query":${ANILIST_SEARCH_QUERY.toJson()},"variables":{"search":"$query","page":1}}"""
        val json = app.post(ANILIST_API, requestBody = body.toRequestBody(), headers = jsonHeaders)
            .parsedSafe<AniListPageResponse>()
        return json?.data?.Page?.media?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    private fun AniListMedia.toSearchResult(): AnimeSearchResponse? {
        val id    = this.id ?: return null
        val title = this.title?.english ?: this.title?.romaji ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/watch/$id", TvType.Anime) {
            this.posterUrl = this@toSearchResult.coverImage?.large
        }
    }

    // ─── Load (Result Page) ───────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        // URL: https://www.miruro.tv/watch/{anilistId}
        val anilistId = url.substringAfterLast("/watch/").substringBefore("?").toIntOrNull()
            ?: throw ErrorLoadingException("Invalid URL: $url")

        // Fetch metadata from AniList
        val body   = """{"query":${ANILIST_DETAIL_QUERY.toJson()},"variables":{"id":$anilistId}}"""
        val detail = app.post(ANILIST_API, requestBody = body.toRequestBody(), headers = jsonHeaders)
            .parsedSafe<AniListDetailResponse>()?.data?.Media
            ?: throw ErrorLoadingException("AniList returned no data for id=$anilistId")

        val title  = detail.title?.english ?: detail.title?.romaji ?: "Unknown"
        val poster = detail.coverImage?.extraLarge
        val plot   = detail.description?.replace(Regex("<[^>]+>"), "")
        val tags   = detail.genres
        val year   = detail.startDate?.year
        val status = when (detail.status) {
            "FINISHED" -> ShowStatus.Completed
            "RELEASING" -> ShowStatus.Ongoing
            else -> null
        }

        // Fetch episode list from Miruro's backend
        // Miruro proxies episodes from Consumet/HiAnime — both sub and dub
        val subEpisodes  = fetchEpisodes(anilistId, "sub")
        val dubEpisodes  = fetchEpisodes(anilistId, "dub")
        val episodes = (subEpisodes + dubEpisodes).sortedBy { it.episode }

        return newAnimeLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl  = poster
            this.plot       = plot
            this.tags       = tags
            this.year       = year
            this.showStatus = status
        }
    }

    private suspend fun fetchEpisodes(anilistId: Int, lang: String): List<Episode> {
        return runCatching {
            val json = app.get(
                "$mainUrl/api/episodes?id=$anilistId&lang=$lang",
                headers = jsonHeaders,
            ).parsedSafe<MiruroEpisodeListResponse>() ?: return emptyList()

            json.episodes?.mapNotNull { ep ->
                val epNum = ep.number ?: return@mapNotNull null
                newEpisode("$anilistId|${ep.id ?: epNum}|$lang") {
                    this.name      = "Ep $epNum${ep.title?.let { ": $it" } ?: ""} [${lang.uppercase()}]"
                    this.episode   = epNum
                    this.posterUrl = ep.image
                    this.scanlator = lang.uppercase()
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    // ─── Load Links ───────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,        // "anilistId|episodeId|lang"
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts     = data.split("|")
        val anilistId = parts.getOrNull(0) ?: return false
        val episodeId = parts.getOrNull(1) ?: return false
        val lang      = parts.getOrNull(2) ?: "sub"

        val streamJson = app.get(
            "$mainUrl/api/stream?id=$anilistId&ep=$episodeId&lang=$lang",
            headers = jsonHeaders,
        ).parsedSafe<MiruroStreamResponse>() ?: return false

        // Primary m3u8 source
        streamJson.sources?.forEach { src ->
            val quality = when {
                src.quality?.contains("1080") == true -> Qualities.P1080
                src.quality?.contains("720")  == true -> Qualities.P720
                src.quality?.contains("480")  == true -> Qualities.P480
                else -> Qualities.Unknown
            }
            callback(
                ExtractorLink(
                    source  = name,
                    name    = "$name · ${lang.uppercase()} · ${src.quality ?: "Auto"}",
                    url     = src.url ?: return@forEach,
                    referer = mainUrl,
                    quality = quality.value,
                    isM3u8  = src.url.contains(".m3u8"),
                )
            )
        }

        // Subtitles
        streamJson.subtitles?.forEach { sub ->
            subtitleCallback(
                SubtitleFile(
                    lang = sub.lang ?: "Unknown",
                    url  = sub.url ?: return@forEach,
                )
            )
        }

        return true
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private fun String.toJson()       = com.lagradost.cloudstream3.utils.AppUtils.toJson(this)
    private fun String.toRequestBody() =
        okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), this)

    // ─── AniList Data Classes ─────────────────────────────────────────

    data class AniListPageResponse(val data: AniListPageData?)
    data class AniListPageData(val Page: AniListPage?)
    data class AniListPage(val media: List<AniListMedia>?)
    data class AniListDetailResponse(val data: AniListDetailData?)
    data class AniListDetailData(val Media: AniListDetailMedia?)

    data class AniListMedia(
        val id: Int?,
        val title: AniListTitle?,
        val coverImage: AniListCoverImage?,
        val episodes: Int?,
        val status: String?,
        val genres: List<String>?,
    )

    data class AniListDetailMedia(
        val id: Int?,
        val title: AniListTitle?,
        val description: String?,
        val coverImage: AniListCoverImageLarge?,
        val episodes: Int?,
        val status: String?,
        val genres: List<String>?,
        val averageScore: Int?,
        val startDate: AniListDate?,
    )

    data class AniListTitle(val romaji: String?, val english: String?)
    data class AniListCoverImage(val large: String?)
    data class AniListCoverImageLarge(val extraLarge: String?)
    data class AniListDate(val year: Int?)

    // ─── Miruro Data Classes ──────────────────────────────────────────

    data class MiruroEpisodeListResponse(val episodes: List<MiruroEpisode>?)
    data class MiruroEpisode(
        val id: String?,
        val number: Int?,
        val title: String?,
        val image: String?,
    )

    data class MiruroStreamResponse(
        val sources: List<MiruroSource>?,
        val subtitles: List<MiruroSubtitle>?,
    )
    data class MiruroSource(val url: String?, val quality: String?)
    data class MiruroSubtitle(val url: String?, val lang: String?)
}
