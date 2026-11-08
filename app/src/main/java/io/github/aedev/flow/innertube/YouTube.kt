package io.github.aedev.flow.innertube

import android.util.Log
import io.github.aedev.flow.data.model.VideoCollaborator
import io.github.aedev.flow.innertube.models.AccountInfo
import io.github.aedev.flow.innertube.models.AlbumItem
import io.github.aedev.flow.innertube.models.Artist
import io.github.aedev.flow.innertube.models.ArtistItem
import io.github.aedev.flow.innertube.models.BrowseEndpoint
import io.github.aedev.flow.innertube.models.GridRenderer
import io.github.aedev.flow.innertube.models.MediaInfo
import io.github.aedev.flow.innertube.models.MusicCarouselShelfRenderer
import io.github.aedev.flow.innertube.models.MusicResponsiveHeaderRenderer
import io.github.aedev.flow.innertube.models.MusicShelfRenderer
import io.github.aedev.flow.innertube.models.PlaylistItem
import io.github.aedev.flow.innertube.models.ReturnYouTubeDislikeResponse
import io.github.aedev.flow.innertube.models.Run
import io.github.aedev.flow.innertube.models.Runs
import io.github.aedev.flow.innertube.models.SearchSuggestions
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.models.WatchEndpoint
import io.github.aedev.flow.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.models.YouTubeClient.Companion.ANDROID_MUSIC
import io.github.aedev.flow.innertube.models.YouTubeClient.Companion.WEB
import io.github.aedev.flow.innertube.models.YouTubeClient.Companion.WEB_REMIX
import io.github.aedev.flow.innertube.models.YouTubeLocale
import io.github.aedev.flow.innertube.models.body.BrowseBody
import io.github.aedev.flow.innertube.models.getContinuation
import io.github.aedev.flow.innertube.models.getItems
import io.github.aedev.flow.innertube.models.oddElements
import io.github.aedev.flow.innertube.models.response.AccountMenuResponse
import io.github.aedev.flow.innertube.models.response.BrowseResponse
import io.github.aedev.flow.innertube.models.response.ChannelVideosResponse
import io.github.aedev.flow.innertube.models.response.CreatePlaylistResponse
import io.github.aedev.flow.innertube.models.response.EditPlaylistResponse
import io.github.aedev.flow.innertube.models.response.GetQueueResponse
import io.github.aedev.flow.innertube.models.response.GetSearchSuggestionsResponse
import io.github.aedev.flow.innertube.models.response.GetTranscriptResponse
import io.github.aedev.flow.innertube.models.response.ImageUploadResponse
import io.github.aedev.flow.innertube.models.response.NextResponse
import io.github.aedev.flow.innertube.models.response.PaidPromotionLabel
import io.github.aedev.flow.innertube.models.response.PaidPromotionResponse
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.innertube.models.response.ResolveUrlResponse
import io.github.aedev.flow.innertube.models.response.SearchResponse
import io.github.aedev.flow.innertube.models.response.channelVideoCountText
import io.github.aedev.flow.innertube.pages.AlbumPage
import io.github.aedev.flow.innertube.pages.ArtistItemsContinuationPage
import io.github.aedev.flow.innertube.pages.ArtistItemsPage
import io.github.aedev.flow.innertube.pages.ArtistPage
import io.github.aedev.flow.innertube.pages.BrowseResult
import io.github.aedev.flow.innertube.pages.ChartsPage
import io.github.aedev.flow.innertube.pages.ExplorePage
import io.github.aedev.flow.innertube.pages.HistoryPage
import io.github.aedev.flow.innertube.pages.HomePage
import io.github.aedev.flow.innertube.pages.LibraryContinuationPage
import io.github.aedev.flow.innertube.pages.LibraryPage
import io.github.aedev.flow.innertube.pages.MoodAndGenres
import io.github.aedev.flow.innertube.pages.NewReleaseAlbumPage
import io.github.aedev.flow.innertube.pages.NextPage
import io.github.aedev.flow.innertube.pages.NextResult
import io.github.aedev.flow.innertube.pages.PlaylistContinuationPage
import io.github.aedev.flow.innertube.pages.PlaylistPage
import io.github.aedev.flow.innertube.pages.RelatedPage
import io.github.aedev.flow.innertube.pages.SearchPage
import io.github.aedev.flow.innertube.pages.SearchResult
import io.github.aedev.flow.innertube.pages.SearchSuggestionPage
import io.github.aedev.flow.innertube.pages.SearchSummaryPage
import io.github.aedev.flow.innertube.pages.TimedLyricLine
import io.github.aedev.flow.innertube.pages.VideoCommentsPage
import io.github.aedev.flow.innertube.pages.VideoCommentsPanelHeader
import io.github.aedev.flow.innertube.pages.VideoDescriptionPage
import io.github.aedev.flow.innertube.pages.VideoPlaylistPage
import io.github.aedev.flow.innertube.pages.channel.ChannelAbout
import io.github.aedev.flow.innertube.pages.channel.ChannelHeader
import io.github.aedev.flow.innertube.pages.channel.ChannelPage
import io.github.aedev.flow.innertube.pages.channel.ChannelShortsPage
import io.github.aedev.flow.innertube.pages.channel.ChannelSortOption
import io.github.aedev.flow.innertube.pages.channel.ChannelTabContent
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind
import io.github.aedev.flow.innertube.pages.channel.channelAboutContinuation
import io.github.aedev.flow.innertube.pages.channel.channelSortOptions
import io.github.aedev.flow.innertube.pages.channel.toChannelAbout
import io.github.aedev.flow.innertube.pages.channel.toChannelHeader
import io.github.aedev.flow.innertube.pages.channel.toChannelShortsPage
import io.github.aedev.flow.innertube.pages.channel.toChannelTabContent
import io.github.aedev.flow.innertube.pages.channel.toChannelTabs
import io.github.aedev.flow.innertube.pages.explore.ExploreDestinationPage
import io.github.aedev.flow.innertube.pages.explore.MusicVideoChart
import io.github.aedev.flow.innertube.pages.explore.exploreShelves
import io.github.aedev.flow.innertube.pages.explore.ranked
import io.github.aedev.flow.innertube.pages.explore.toExploreDestinationShell
import io.github.aedev.flow.innertube.pages.explore.trendingVideoChart
import io.github.aedev.flow.innertube.pages.mixSeedVideoId
import io.github.aedev.flow.innertube.pages.parseYouTubeViewCount
import io.github.aedev.flow.innertube.pages.reel.ReelLockup
import io.github.aedev.flow.innertube.pages.reel.ReelOverlay
import io.github.aedev.flow.innertube.pages.reel.ReelParams
import io.github.aedev.flow.innertube.pages.reel.ReelSequencePage
import io.github.aedev.flow.innertube.pages.reel.toReelLockups
import io.github.aedev.flow.innertube.pages.reel.toReelOverlay
import io.github.aedev.flow.innertube.pages.reel.toReelSequencePage
import io.github.aedev.flow.innertube.pages.renderer.CommunityCommentsPage
import io.github.aedev.flow.innertube.pages.renderer.CommunityPostsPage
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.innertube.pages.renderer.FeedShelf
import io.github.aedev.flow.innertube.pages.renderer.collaboratorDialog
import io.github.aedev.flow.innertube.pages.renderer.lockupDateAndViews
import io.github.aedev.flow.innertube.pages.renderer.toCommunityCommentsPage
import io.github.aedev.flow.innertube.pages.renderer.toCommunityPostsPage
import io.github.aedev.flow.innertube.pages.search.SearchResultsPage
import io.github.aedev.flow.innertube.pages.search.SearchSuggestion
import io.github.aedev.flow.innertube.pages.search.parseSearchSuggestions
import io.github.aedev.flow.innertube.pages.search.toSearchResultsPage
import io.github.aedev.flow.innertube.pages.timedLyricLines
import io.github.aedev.flow.innertube.pages.toCommentRepliesPage
import io.github.aedev.flow.innertube.pages.toMixPlaylistPage
import io.github.aedev.flow.innertube.pages.toVideoCommentsPage
import io.github.aedev.flow.innertube.pages.toVideoDescriptionPage
import io.github.aedev.flow.innertube.pages.toVideoPlaylistPage
import io.github.aedev.flow.innertube.pages.videoCommentsContinuation
import io.github.aedev.flow.innertube.pages.videoCommentsPanelHeader
import io.github.aedev.flow.utils.PerformanceDispatcher
import io.github.aedev.flow.utils.avatarImageIdentityKey
import io.github.aedev.flow.utils.relativedate.RelativeUploadDateParser
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.net.Proxy
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Parse useful data with [InnerTube] sending requests.
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 */
object YouTube {
    private val innerTube = InnerTube()
    private const val CHANNEL_VIDEOS_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"
    private const val CHANNEL_LIVE_PARAMS = "EgdzdHJlYW1z8gYECgJ6AA%3D%3D"
    private const val CHANNEL_POSTS_PARAMS = "EgVwb3N0c_IGBAoCSgA="
    private const val CHANNEL_SHORTS_PARAMS = "EgZzaG9ydHPyBgUKA5oBAA=="

    /** The playlist header menu's "Show unavailable videos" view, which lists a Shorts grid in full. */
    const val PLAYLIST_SHOW_UNAVAILABLE_PARAMS = "wgYCCAA="

    var locale: YouTubeLocale
        get() = innerTube.locale
        set(value) {
            innerTube.locale = value
        }
    var visitorData: String?
        get() = innerTube.visitorData
        set(value) {
            innerTube.visitorData = value
        }
    var onVisitorDataChanged: ((String?) -> Unit)?
        get() = innerTube.onVisitorDataChanged
        set(value) {
            innerTube.onVisitorDataChanged = value
        }
    var dataSyncId: String?
        get() = innerTube.dataSyncId
        set(value) {
            innerTube.dataSyncId = value
        }
    var cookie: String?
        get() = innerTube.cookie
        set(value) {
            innerTube.cookie = value
        }
    var proxy: Proxy?
        get() = innerTube.proxy
        set(value) {
            innerTube.proxy = value
        }

    var proxyAuth: String?
        get() = innerTube.proxyAuth
        set(value) {
            innerTube.proxyAuth = value
        }
    var cacheDirectory: java.io.File?
        get() = innerTube.cacheDirectory
        set(value) {
            innerTube.cacheDirectory = value
        }
    var useLoginForBrowse: Boolean
        get() = innerTube.useLoginForBrowse
        set(value) {
            innerTube.useLoginForBrowse = value
        }

    suspend fun searchSuggestions(query: String): Result<SearchSuggestions> =
        runCatching {
            val response = innerTube.getSearchSuggestions(WEB_REMIX, query).body<GetSearchSuggestionsResponse>()
            SearchSuggestions(
                queries =
                    response.contents
                        ?.getOrNull(0)
                        ?.searchSuggestionsSectionRenderer
                        ?.contents
                        ?.mapNotNull { content ->
                            content.searchSuggestionRenderer
                                ?.suggestion
                                ?.runs
                                ?.joinToString(separator = "") { it.text }
                        }.orEmpty(),
                recommendedItems =
                    response.contents
                        ?.getOrNull(1)
                        ?.searchSuggestionsSectionRenderer
                        ?.contents
                        ?.mapNotNull {
                            it.musicResponsiveListItemRenderer?.let { renderer ->
                                SearchSuggestionPage.fromMusicResponsiveListItemRenderer(renderer)
                            }
                        }.orEmpty(),
            )
        }

    suspend fun searchSummary(query: String): Result<SearchSummaryPage> =
        runCatching {
            SearchSummaryPage.fromSearchResponse(innerTube.search(WEB_REMIX, query).body<SearchResponse>())
        }

    suspend fun search(
        query: String,
        filter: SearchFilter,
    ): Result<SearchResult> =
        runCatching {
            val response = innerTube.search(WEB_REMIX, query, filter.value).body<SearchResponse>()
            // The results shelf is not always the last section: a "did you mean" or message can follow it.
            val shelf =
                response.contents
                    ?.tabbedSearchResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstNotNullOfOrNull { it.musicShelfRenderer }
            SearchResult(
                items =
                    shelf
                        ?.contents
                        ?.getItems()
                        ?.mapNotNull(SearchPage::toYTItem)
                        .orEmpty(),
                continuation = shelf?.continuations?.getContinuation(),
            )
        }

    /** Reels matching [query], through the Shorts filter the search dialog itself sends. */
    suspend fun searchShorts(query: String): Result<List<ReelLockup>> =
        runCatching {
            ensureVisitorData()
            innerTube
                .webSearch(
                    client = currentWebClient(),
                    query = query,
                    params = ReelParams.SEARCH_SHORTS_FILTER,
                    anonymous = true,
                    includeVisitorData = true,
                ).body<JsonObject>()
                .toReelLockups()
        }.onFailure { Log.w("SearchShorts", "query='$query' failed: ${it.message}") }

    /** Typeahead suggestions for the video search bar, in the app's content language. */
    suspend fun videoSearchSuggestions(query: String): Result<List<SearchSuggestion>> =
        runCatching {
            parseSearchSuggestions(innerTube.searchSuggestions(query).bodyAsText())
        }

    /** One page of video search. Filters and sorting ride in [params]; paging rides in [continuation]. */
    suspend fun videoSearch(
        query: String,
        params: String? = null,
        continuation: String? = null,
    ): Result<SearchResultsPage> =
        runCatching {
            ensureVisitorData()
            innerTube
                .webSearch(
                    client = currentWebClient(),
                    query = query.takeIf { continuation == null },
                    params = params?.takeIf { continuation == null },
                    continuation = continuation,
                    anonymous = true,
                    includeVisitorData = true,
                ).body<JsonObject>()
                .toSearchResultsPage()
        }

    private suspend fun ensureVisitorData() {
        if (!visitorData.isNullOrBlank()) return
        visitorData()
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let { visitorData = it }
    }

    private suspend fun currentWebClient(): YouTubeClient =
        withContext(Dispatchers.IO) {
            WEB.copy(
                clientVersion =
                    runCatching { YoutubeParsingHelper.getClientVersion() }
                        .getOrDefault(WEB.clientVersion),
            )
        }

    suspend fun searchContinuation(continuation: String): Result<SearchResult> =
        runCatching {
            val response = innerTube.search(WEB_REMIX, continuation = continuation).body<SearchResponse>()
            SearchResult(
                items =
                    response.continuationContents
                        ?.musicShelfContinuation
                        ?.contents
                        ?.mapNotNull {
                            SearchPage.toYTItem(it.musicResponsiveListItemRenderer)
                        }.orEmpty(),
                continuation =
                    response.continuationContents
                        ?.musicShelfContinuation
                        ?.continuations
                        ?.getContinuation(),
            )
        }

    suspend fun videoAvatarStack(videoId: String): Result<List<String>> =
        runCatching {
            val rawBody = innerTube.next(WEB, videoId, null, null, null, null, null).bodyAsText()
            val root =
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }.parseToJsonElement(rawBody)
            root.findVideoOwnerAvatarStackUrls()
        }

    suspend fun videoCollaborators(videoId: String): Result<List<VideoCollaborator>> =
        runCatching {
            val rawBody = innerTube.next(WEB, videoId, null, null, null, null, null).bodyAsText()
            val root =
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }.parseToJsonElement(rawBody)
            root.findVideoOwnerCollaborators()
        }

    private fun JsonElement.collectAvatarImageUrls(): List<String> {
        val urls = mutableListOf<String>()

        fun collect(element: JsonElement) {
            when (element) {
                is JsonArray -> {
                    element.forEach(::collect)
                }

                is JsonObject -> {
                    val url = (element["url"] as? JsonPrimitive)?.contentOrNull
                    if (!url.isNullOrBlank() && url.contains("yt3.ggpht.com")) {
                        urls += url
                    }
                    element.values.forEach(::collect)
                }

                else -> {
                    Unit
                }
            }
        }

        collect(this)
        return urls
            .distinctBy { it.avatarImageIdentityKey() }
            .take(5)
    }

    private fun JsonElement.findVideoOwnerAvatarStackUrls(): List<String> =
        when (this) {
            is JsonObject -> {
                val owner = this["videoOwnerRenderer"] as? JsonObject
                val ownerStack =
                    owner
                        ?.get("avatarStack")
                        ?.collectAvatarImageUrls()
                        .orEmpty()
                        .take(2)
                if (ownerStack.size > 1) {
                    ownerStack
                } else {
                    values
                        .firstNotNullOfOrNull { child ->
                            child.findVideoOwnerAvatarStackUrls().takeIf { it.size > 1 }
                        }.orEmpty()
                }
            }

            is JsonArray -> {
                firstNotNullOfOrNull { child ->
                    child.findVideoOwnerAvatarStackUrls().takeIf { it.size > 1 }
                }.orEmpty()
            }

            else -> {
                emptyList()
            }
        }

    private fun JsonElement.findVideoOwnerCollaborators(): List<VideoCollaborator> =
        when (this) {
            is JsonObject -> {
                val owner = this["videoOwnerRenderer"] as? JsonObject
                val collaborators = owner?.get("navigationEndpoint").collaboratorDialog()
                if (collaborators.size > 1) {
                    collaborators
                } else {
                    values
                        .firstNotNullOfOrNull { child ->
                            child.findVideoOwnerCollaborators().takeIf { it.size > 1 }
                        }.orEmpty()
                }
            }

            is JsonArray -> {
                firstNotNullOfOrNull { child ->
                    child.findVideoOwnerCollaborators().takeIf { it.size > 1 }
                }.orEmpty()
            }

            else -> {
                emptyList()
            }
        }

    // ── Channel (native InnerTube) ─────────────────────

    /**
     * A channel's landing page: header, the tabs it actually has, and the tab YouTube returned with
     * it. [idOrHandle] must be a channel id in practice: browse answers 400 to an @handle, which
     * would need a resolve request first.
     */
    suspend fun channel(idOrHandle: String): Result<ChannelPage> =
        runCatching {
            val response = channelBrowseJson(browseId = idOrHandle)
            val about =
                response
                    .channelAboutContinuation()
                    ?.let { token -> runCatching { channelBrowseJson(continuation = token).toChannelAbout() }.getOrNull() }
            response.toChannelPage(response.toChannelHeader(idOrHandle).mergedWith(about))
        }

    private val resolvedChannelIds = ConcurrentHashMap<String, String>()

    /**
     * The channel id behind an @handle, `/c/` or `/user/` link, which browse cannot open directly.
     * Kept for the process: a channel page and its Shorts feed both resolve the same link.
     */
    suspend fun resolveChannelId(url: String): Result<String> =
        runCatching {
            resolvedChannelIds[url]?.let { return@runCatching it }
            val channelId =
                innerTube
                    .resolveUrl(WEB, url)
                    .body<ResolveUrlResponse>()
                    .endpoint
                    ?.browseEndpoint
                    ?.browseId
                    ?.takeIf { it.startsWith("UC") }
                    ?: error("No channel behind $url")
            resolvedChannelIds[url] = channelId
            channelId
        }

    /** [channel] without the About request, for callers that only need the header and the tabs. */
    suspend fun channelLanding(channelId: String): Result<ChannelPage> =
        runCatching {
            val response = channelBrowseJson(browseId = channelId)
            response.toChannelPage(response.toChannelHeader(channelId))
        }

    private fun JsonElement.toChannelPage(header: ChannelHeader): ChannelPage {
        val tabs = toChannelTabs()
        return ChannelPage(
            header = header,
            tabs = tabs,
            initialTab =
                tabs
                    .firstOrNull { it.selected }
                    ?.let { tab -> toChannelTabContent(tab.kind, header.toOwner()) },
        )
    }

    suspend fun channelTab(
        browseId: String,
        params: String,
        owner: FeedItemOwner = FeedItemOwner(id = browseId),
        kind: ChannelTabKind = ChannelTabKind.Unknown,
    ): Result<ChannelTabContent> =
        runCatching {
            channelBrowseJson(browseId = browseId, params = params).toChannelTabContent(kind, owner)
        }

    /** Serves paging and sort switching alike — a sort chip's token is just another continuation. */
    suspend fun channelTabContinuation(
        continuation: String,
        owner: FeedItemOwner,
        kind: ChannelTabKind = ChannelTabKind.Unknown,
    ): Result<ChannelTabContent> =
        runCatching {
            channelBrowseJson(continuation = continuation).toChannelTabContent(kind, owner)
        }

    private suspend fun channelBrowseJson(
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
    ): JsonElement {
        // Channel rows carry server-rendered times ("Scheduled for 9/16/26, 6:45 PM"); the WEB
        // client's zero offset would print them in UTC.
        val response =
            innerTube.channelBrowse(
                client = currentWebClient().copy(utcOffsetMinutes = localUtcOffsetMinutes()),
                channelId = browseId,
                params = params,
                continuation = continuation,
            )
        return Json.parseToJsonElement(response.bodyAsText())
    }

    private fun localUtcOffsetMinutes(): Int =
        ZoneId
            .systemDefault()
            .rules
            .getOffset(Instant.now())
            .totalSeconds / 60

    /**
     * The landing response carries a one-line description and nothing else about the channel; the
     * About panel is a separate continuation and is where the links, country, join date and totals
     * live.
     */
    private fun ChannelHeader.mergedWith(about: ChannelAbout?): ChannelHeader =
        if (about == null) {
            this
        } else {
            copy(
                description = about.description ?: description,
                subscriberCountText = about.subscriberCountText ?: subscriberCountText,
                videoCountText = about.videoCountText ?: videoCountText,
                joinedDateText = about.joinedDateText,
                viewCountText = about.viewCountText,
                countryText = about.countryText,
                canonicalUrl = about.canonicalUrl ?: canonicalUrl,
                links = about.links,
            )
        }

    private fun ChannelHeader.toOwner() = FeedItemOwner(id = id, name = title, avatarUrl = avatarUrl)

    // ── Channel-scoped video search (YouTube.com WEB API) ─────────────────────

    data class ChannelVideoSearchResult(
        val videos: List<io.github.aedev.flow.data.model.Video>,
        val continuation: String?,
        val channelVideoCountText: String? = null,
        /** The tab's Latest/Popular/Oldest bar. Empty on a continuation, which carries no header. */
        val sorts: List<ChannelSortOption> = emptyList(),
    )

    /**
     * Search for videos within [channelId] matching [query].
     * Uses the YouTube.com WEB Innertube endpoint with a channel-scoped params filter.
     */
    suspend fun channelSearch(
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
        query: String,
    ): Result<ChannelVideoSearchResult> =
        runCatching {
            val httpResponse = innerTube.channelSearch(currentWebClient(), channelId, query)
            val rawBody = httpResponse.bodyAsText()
            val lenientJson =
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
            val response = lenientJson.decodeFromString<io.github.aedev.flow.innertube.models.response.ChannelSearchResponse>(rawBody)
            parseChannelSearchResponse(response, channelId, channelName, channelThumbnailUrl)
        }

    suspend fun channelSearchContinuation(
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
        continuation: String,
    ): Result<ChannelVideoSearchResult> =
        runCatching {
            val httpResponse =
                innerTube.channelSearch(
                    currentWebClient(),
                    channelId,
                    query = "",
                    continuation = continuation,
                )
            val rawBody = httpResponse.bodyAsText()
            val lenientJson =
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
            val response = lenientJson.decodeFromString<io.github.aedev.flow.innertube.models.response.ChannelSearchResponse>(rawBody)

            val videos = mutableListOf<io.github.aedev.flow.data.model.Video>()
            var nextContinuation: String? = null

            val appendedItems =
                response.onResponseReceivedActions
                    ?.firstOrNull { it.appendContinuationItemsAction != null }
                    ?.appendContinuationItemsAction
                    ?.continuationItems
                    .orEmpty()
            if (appendedItems.isNotEmpty()) {
                appendedItems.forEach { richItem ->
                    richItem.richItemRenderer
                        ?.content
                        ?.videoRenderer
                        ?.let { parseVideoRenderer(it, channelId, channelName, channelThumbnailUrl) }
                        ?.let { videos.add(it) }
                    richItem.itemSectionRenderer?.contents?.forEach { sectionItem ->
                        sectionItem.videoRenderer
                            ?.let { parseVideoRenderer(it, channelId, channelName, channelThumbnailUrl) }
                            ?.let { videos.add(it) }
                    }
                    richItem.continuationItemRenderer
                        ?.continuationEndpoint
                        ?.continuationCommand
                        ?.token
                        ?.let { nextContinuation = it }
                }
            }

            if (videos.isEmpty()) {
                val sectionContents =
                    response.continuationContents
                        ?.sectionListContinuation
                        ?.contents
                        .orEmpty()
                sectionContents
                    .mapNotNull { it.itemSectionRenderer?.contents }
                    .flatten()
                    .mapNotNull { it.videoRenderer }
                    .mapNotNull { parseVideoRenderer(it, channelId, channelName, channelThumbnailUrl) }
                    .forEach { videos.add(it) }
                if (nextContinuation == null) {
                    nextContinuation = response.continuationContents
                        ?.sectionListContinuation
                        ?.continuations
                        ?.firstOrNull()
                        ?.nextContinuationData
                        ?.continuation
                        ?: sectionContents
                            .mapNotNull { it.continuationItemRenderer }
                            .firstOrNull()
                            ?.continuationEndpoint
                            ?.continuationCommand
                            ?.token
                }
            }

            if (videos.isEmpty()) {
                response.continuationContents?.richGridContinuation?.contents?.forEach { richItem ->
                    richItem.richItemRenderer
                        ?.content
                        ?.videoRenderer
                        ?.let { parseVideoRenderer(it, channelId, channelName, channelThumbnailUrl) }
                        ?.let { videos.add(it) }
                    richItem.continuationItemRenderer
                        ?.continuationEndpoint
                        ?.continuationCommand
                        ?.token
                        ?.let { nextContinuation = it }
                }
            }

            ChannelVideoSearchResult(videos = videos, continuation = nextContinuation)
        }

    suspend fun channelVideos(
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): Result<ChannelVideoSearchResult> = channelVideosPage(channelId, channelName, channelThumbnailUrl, CHANNEL_VIDEOS_PARAMS, false)

    suspend fun channelVideosContinuation(
        continuation: String,
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): Result<ChannelVideoSearchResult> = channelVideosPage(channelId, channelName, channelThumbnailUrl, null, false, continuation)

    suspend fun channelLiveStreams(
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): Result<ChannelVideoSearchResult> = channelVideosPage(channelId, channelName, channelThumbnailUrl, CHANNEL_LIVE_PARAMS, true)

    suspend fun channelLiveStreamsContinuation(
        continuation: String,
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): Result<ChannelVideoSearchResult> = channelVideosPage(channelId, channelName, channelThumbnailUrl, null, true, continuation)

    /**
     * A channel's Shorts tab, including its sort bar. Unlike the Videos and Live tabs this goes
     * through the native client rather than NewPipe, because NewPipe's `ChannelTabInfo` has no
     * notion of the Latest/Popular/Oldest chips and the tab is useless without them (#547).
     */
    suspend fun channelShorts(channelId: String): Result<ChannelShortsPage> = channelShortsPage(channelId = channelId)

    /** Serves both paging and sort switching — a sort chip's token is just another continuation. */
    suspend fun channelShortsContinuation(continuation: String): Result<ChannelShortsPage> = channelShortsPage(continuation = continuation)

    private suspend fun channelShortsPage(
        channelId: String? = null,
        continuation: String? = null,
    ): Result<ChannelShortsPage> =
        runCatching {
            val client = currentWebClient()
            val response =
                innerTube.channelBrowse(
                    client = client,
                    channelId = channelId,
                    params = CHANNEL_SHORTS_PARAMS.takeIf { continuation == null },
                    continuation = continuation,
                )
            Json
                .parseToJsonElement(response.bodyAsText())
                .jsonObject
                .toChannelShortsPage()
        }

    /**
     * An explore destination's landing page, emitted as it is mapped: the tabs first, then one more
     * shelf each time. `FEtrending` and `FEexplore` are dead — see
     * [io.github.aedev.flow.innertube.pages.explore.ExploreDestination].
     *
     * The whole page arrives in a single browse response, so this cannot paint before the body
     * lands; what it takes off first paint is the mapping of every shelf after the first, which on
     * a destination runs to hundreds of items. That mapping and the parse it walks both stay off
     * the collector's thread.
     */
    fun exploreDestination(
        browseId: String,
        params: String? = null,
    ): Flow<ExploreDestinationPage> =
        flow {
            val response = channelBrowseJson(browseId = browseId, params = params)
            val shell = response.toExploreDestinationShell()
            emit(shell)
            val shelves = mutableListOf<FeedShelf>()
            response.exploreShelves(shell.owner).forEach { shelf ->
                shelves += shelf
                emit(shell.copy(shelves = shelves.toList()))
            }
        }.flowOn(PerformanceDispatcher.parsing)

    /**
     * A country's trending music videos: YouTube Music's charts page for [country] names the chart
     * playlist, and the playlist browse gives its rows. Two requests, both on hosts that serve
     * anonymous clients.
     */
    suspend fun musicVideoChart(country: String): Result<MusicVideoChart> =
        runCatching {
            val playlist =
                getChartsPage(country.uppercase()).getOrThrow().trendingVideoChart()
                    ?: return@runCatching MusicVideoChart(title = "")
            val page = videoPlaylistPage(playlist.id).getOrThrow()
            MusicVideoChart(title = playlist.title, entries = page.videos.ranked())
        }

    suspend fun communityPosts(
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): Result<CommunityPostsPage> = communityPostsPage(channelId, channelName, channelThumbnailUrl)

    suspend fun communityPostsContinuation(
        continuation: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): Result<CommunityPostsPage> =
        communityPostsPage(
            channelId = "",
            channelName = channelName,
            channelThumbnailUrl = channelThumbnailUrl,
            continuation = continuation,
        )

    suspend fun communityPostComments(
        postId: String,
        params: String?,
    ): Result<CommunityCommentsPage> =
        runCatching {
            val initial = communityPostCommentsPage(postId = postId, params = params)
            if (initial.comments.isNotEmpty() || initial.continuation == null) {
                initial
            } else {
                val commentsPage = communityPostCommentsPage(continuation = initial.continuation)
                commentsPage.copy(
                    commentCountText = commentsPage.commentCountText ?: initial.commentCountText,
                )
            }
        }

    suspend fun communityPostCommentsContinuation(continuation: String): Result<CommunityCommentsPage> =
        runCatching {
            communityPostCommentsPage(continuation = continuation)
        }

    /**
     * A YouTube playlist's first page when [continuation] is null, otherwise the page it points to.
     * [params] picks a view of the first page, such as [PLAYLIST_SHOW_UNAVAILABLE_PARAMS].
     */
    suspend fun videoPlaylistPage(
        playlistId: String,
        continuation: String? = null,
        params: String? = null,
    ): Result<VideoPlaylistPage> =
        runCatching {
            val response =
                innerTube.channelBrowse(
                    client = WEB,
                    channelId = "VL$playlistId".takeIf { continuation == null },
                    params = params.takeIf { continuation == null },
                    continuation = continuation,
                )
            Json.parseToJsonElement(response.bodyAsText()).toVideoPlaylistPage()
        }

    /** A mix's queue, which is the only list of it: the playlist browse refuses mixes. */
    suspend fun mixPlaylistPage(playlistId: String): Result<VideoPlaylistPage> =
        runCatching {
            val response = innerTube.nextMix(mixSeedVideoId(playlistId), playlistId)
            Json.parseToJsonElement(response.bodyAsText()).toMixPlaylistPage()
        }

    private suspend fun communityPostsPage(
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
        continuation: String? = null,
    ): Result<CommunityPostsPage> =
        runCatching {
            val client = currentWebClient()
            val response =
                innerTube.channelBrowse(
                    client = client,
                    channelId = channelId.takeIf { continuation == null },
                    params = CHANNEL_POSTS_PARAMS.takeIf { continuation == null },
                    continuation = continuation,
                )
            Json.parseToJsonElement(response.bodyAsText()).toCommunityPostsPage(
                fallbackAuthorName = channelName,
                fallbackAuthorAvatarUrl = channelThumbnailUrl,
            )
        }

    private suspend fun communityPostCommentsPage(
        postId: String? = null,
        params: String? = null,
        continuation: String? = null,
    ): CommunityCommentsPage {
        val client = currentWebClient()
        val response =
            innerTube.postCommentsBrowse(
                client = client,
                postId = postId,
                params = params,
                continuation = continuation,
            )
        return Json.parseToJsonElement(response.bodyAsText()).toCommunityCommentsPage()
    }

    private suspend fun channelVideosPage(
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
        params: String?,
        isLive: Boolean,
        continuation: String? = null,
    ): Result<ChannelVideoSearchResult> =
        runCatching {
            val client = currentWebClient()
            val httpResponse =
                innerTube.channelBrowse(
                    client = client,
                    channelId = if (continuation == null) channelId else null,
                    params = params,
                    continuation = continuation,
                )
            val rawBody = httpResponse.bodyAsText()
            val lenientJson =
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
            val response = lenientJson.decodeFromString<ChannelVideosResponse>(rawBody)
            val sorts = Json.parseToJsonElement(rawBody).channelSortOptions()
            parseChannelVideosResponse(response, channelId, channelName, channelThumbnailUrl, isLive)
                .copy(sorts = sorts)
        }

    private fun parseChannelVideosResponse(
        response: ChannelVideosResponse,
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
        isLive: Boolean,
    ): ChannelVideoSearchResult {
        val metadata = response.metadata?.channelMetadataRenderer
        val resolvedChannelId =
            metadata?.externalChannelId
                ?: metadata?.externalId
                ?: channelId
        val resolvedChannelName = metadata?.title?.takeIf { it.isNotBlank() } ?: channelName
        val resolvedThumbnail =
            metadata
                ?.avatar
                ?.thumbnails
                ?.maxByOrNull { it.width ?: 0 }
                ?.url
                ?: channelThumbnailUrl

        val richItems = mutableListOf<ChannelVideosResponse.RichItem>()
        response.onResponseReceivedActions
            ?.flatMap {
                it.appendContinuationItemsAction?.continuationItems.orEmpty() +
                    it.reloadContinuationItemsCommand?.continuationItems.orEmpty()
            }?.let { richItems += it }

        response.continuationContents
            ?.richGridContinuation
            ?.contents
            ?.let { richItems += it }

        val tabs =
            response.contents
                ?.twoColumnBrowseResultsRenderer
                ?.tabs
                .orEmpty()
        val selectedTab =
            tabs.firstOrNull { it.tabRenderer?.selected == true }?.tabRenderer
                ?: tabs.firstOrNull { it.tabRenderer?.content?.richGridRenderer != null }?.tabRenderer
                ?: tabs.firstOrNull { it.expandableTabRenderer?.content?.richGridRenderer != null }?.expandableTabRenderer
        selectedTab
            ?.content
            ?.richGridRenderer
            ?.contents
            ?.let { richItems += it }

        val videos = mutableListOf<io.github.aedev.flow.data.model.Video>()
        var nextContinuation: String? = null
        richItems.forEach { richItem ->
            val content = richItem.richItemRenderer?.content
            content
                ?.lockupViewModel
                ?.let { parseLockupViewModel(it, resolvedChannelId, resolvedChannelName, resolvedThumbnail, isLive) }
                ?.let { videos.add(it) }
            content
                ?.videoRenderer
                ?.let { parseBrowseVideoRenderer(it, resolvedChannelId, resolvedChannelName, resolvedThumbnail, isLive) }
                ?.let { videos.add(it) }
            richItem.continuationItemRenderer
                ?.continuationEndpoint
                ?.continuationCommand
                ?.token
                ?.let { nextContinuation = it }
        }

        return ChannelVideoSearchResult(
            videos = videos.distinctBy { it.id },
            continuation = nextContinuation,
            channelVideoCountText = response.channelVideoCountText(),
        )
    }

    private fun parseLockupViewModel(
        lockup: ChannelVideosResponse.LockupViewModel,
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
        isLive: Boolean,
    ): io.github.aedev.flow.data.model.Video? {
        val videoId = lockup.contentId ?: return null
        val metadata = lockup.metadata?.lockupMetadataViewModel
        val title = metadata?.title?.content?.takeIf { it.isNotBlank() } ?: return null
        val thumbnail =
            lockup.contentImage
                ?.thumbnailViewModel
                ?.image
                ?.sources
                ?.maxByOrNull { it.width ?: 0 }
                ?.url
                ?: "https://i.ytimg.com/vi/$videoId/hq720.jpg"
        val durationText =
            lockup.contentImage
                ?.thumbnailViewModel
                ?.overlays
                ?.firstNotNullOfOrNull { overlay ->
                    overlay.thumbnailBottomOverlayViewModel
                        ?.badges
                        ?.firstNotNullOfOrNull { it.thumbnailBadgeViewModel?.text }
                }
        val parts =
            metadata
                ?.metadata
                ?.contentMetadataViewModel
                ?.metadataRows
                ?.firstOrNull()
                ?.metadataParts
                ?.mapNotNull { it.text?.content?.takeIf(String::isNotBlank) }
                .orEmpty()
        val (viewsText, uploadText, uploadTimestamp) = lockupDateAndViews(parts, locale.hl)

        return io.github.aedev.flow.data.model.Video(
            id = videoId,
            title = title,
            channelName = channelName,
            channelId = channelId,
            thumbnailUrl = thumbnail,
            duration = parseLengthText(durationText),
            viewCount = parseYouTubeViewCount(viewsText),
            uploadDate = uploadText,
            timestamp = uploadTimestamp ?: 0L,
            channelThumbnailUrl = channelThumbnailUrl,
            isLive = isLive || viewsText?.contains("watching", ignoreCase = true) == true,
        )
    }

    private fun parseBrowseVideoRenderer(
        r: ChannelVideosResponse.VideoRenderer,
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
        isLive: Boolean,
    ): io.github.aedev.flow.data.model.Video? {
        val videoId = r.videoId ?: return null
        val title = r.title?.textValue()?.takeIf { it.isNotBlank() } ?: return null
        val thumbnail =
            r.thumbnail
                ?.thumbnails
                ?.maxByOrNull { it.width ?: 0 }
                ?.url
                ?: "https://i.ytimg.com/vi/$videoId/hq720.jpg"
        val uploadText = r.publishedTimeText?.textValue().orEmpty()
        val viewsText = r.viewCountText?.textValue()
        val avatarUrls = r.channelAvatarUrls(channelThumbnailUrl)
        return io.github.aedev.flow.data.model.Video(
            id = videoId,
            title = title,
            channelName = channelName,
            channelId = channelId,
            thumbnailUrl = thumbnail,
            duration = parseLengthText(r.lengthText?.textValue()),
            viewCount = parseYouTubeViewCount(viewsText),
            uploadDate = uploadText,
            timestamp = RelativeUploadDateParser.parse(uploadText, locale.hl) ?: 0L,
            channelThumbnailUrl = avatarUrls.firstOrNull().orEmpty(),
            channelThumbnailUrls = avatarUrls,
            isLive = isLive || viewsText?.contains("watching", ignoreCase = true) == true,
        )
    }

    private fun ChannelVideosResponse.SimpleText.textValue(): String? = simpleText ?: runs?.joinToString("") { it.text.orEmpty() }

    private fun ChannelVideosResponse.VideoRenderer.channelAvatarUrls(fallback: String): List<String> {
        val supported = channelThumbnailSupportedRenderers
        val stackAvatars =
            listOfNotNull(
                avatarStackViewModel,
                supported?.avatarStackViewModel,
                supported?.channelThumbnailWithLinkRenderer?.avatarStack?.avatarStackViewModel,
            ).flatMap { stack ->
                stack.avatars.orEmpty().mapNotNull { avatar ->
                    avatar.avatarViewModel
                        ?.image
                        ?.sources
                        ?.maxByOrNull { maxOf(it.width ?: 0, it.height ?: 0) }
                        ?.url
                }
            }
        val linkedAvatar =
            supported
                ?.channelThumbnailWithLinkRenderer
                ?.thumbnail
                ?.thumbnails
                ?.maxByOrNull { maxOf(it.width ?: 0, it.height ?: 0) }
                ?.url

        return (stackAvatars + linkedAvatar + fallback)
            .filter { !it.isNullOrBlank() }
            .distinctBy { it.avatarImageIdentityKey() }
            .take(2)
            .filterNotNull()
    }

    private fun parseChannelSearchResponse(
        response: io.github.aedev.flow.innertube.models.response.ChannelSearchResponse,
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): ChannelVideoSearchResult {
        val tabs =
            response.contents
                ?.twoColumnBrowseResultsRenderer
                ?.tabs
                .orEmpty()
        Log.d("ChannelSearch", "tabs=${tabs.size}")
        tabs.forEachIndexed { i, tab ->
            val url =
                tab.tabRenderer
                    ?.endpoint
                    ?.commandMetadata
                    ?.webCommandMetadata
                    ?.url
            Log.d(
                "ChannelSearch",
                "tab[$i]: url=$url, selected=${tab.tabRenderer?.selected}, " +
                    "hasSection=${tab.tabRenderer?.content?.sectionListRenderer != null}, " +
                    "hasRichGrid=${tab.tabRenderer?.content?.richGridRenderer != null}, " +
                    "isExpandable=${tab.expandableTabRenderer != null}",
            )
        }

        val tabContent =
            tabs
                .firstOrNull {
                    it.tabRenderer?.selected == true && it.tabRenderer.endpoint
                        ?.commandMetadata
                        ?.webCommandMetadata
                        ?.url
                        ?.contains("/search") == true
                }?.tabRenderer
                ?.content
                ?: tabs.firstOrNull { it.expandableTabRenderer?.content != null }?.expandableTabRenderer?.content
                ?: tabs.firstOrNull { it.tabRenderer?.selected == true }?.tabRenderer?.content

        Log.d(
            "ChannelSearch",
            "tabContent=${tabContent != null}, " +
                "hasSection=${tabContent?.sectionListRenderer != null}, " +
                "hasRichGrid=${tabContent?.richGridRenderer != null}",
        )

        val videos = mutableListOf<io.github.aedev.flow.data.model.Video>()
        var continuation: String? = null

        tabContent?.richGridRenderer?.contents?.forEach { richItem ->
            richItem.richItemRenderer
                ?.content
                ?.videoRenderer
                ?.let { parseVideoRenderer(it, channelId, channelName, channelThumbnailUrl) }
                ?.let { videos.add(it) }
            richItem.continuationItemRenderer
                ?.continuationEndpoint
                ?.continuationCommand
                ?.token
                ?.let { continuation = it }
        }

        if (videos.isEmpty()) {
            val sectionContents = tabContent?.sectionListRenderer?.contents.orEmpty()
            sectionContents
                .mapNotNull { it.itemSectionRenderer?.contents }
                .flatten()
                .mapNotNull { it.videoRenderer }
                .mapNotNull { parseVideoRenderer(it, channelId, channelName, channelThumbnailUrl) }
                .forEach { videos.add(it) }
            if (continuation == null) {
                continuation =
                    sectionContents
                        .mapNotNull { it.continuationItemRenderer }
                        .firstOrNull()
                        ?.continuationEndpoint
                        ?.continuationCommand
                        ?.token
            }
        }

        Log.d("ChannelSearch", "videos=${videos.size}, hasContinuation=${continuation != null}")
        return ChannelVideoSearchResult(videos = videos, continuation = continuation)
    }

    private fun parseVideoRenderer(
        r: io.github.aedev.flow.innertube.models.response.ChannelSearchResponse.VideoRenderer,
        channelId: String,
        channelName: String,
        channelThumbnailUrl: String,
    ): io.github.aedev.flow.data.model.Video? {
        val videoId = r.videoId ?: return null
        val title =
            r.title
                ?.runs
                ?.joinToString("") { it.text ?: "" }
                ?.takeIf { it.isNotBlank() } ?: return null
        val thumbnail =
            r.thumbnail
                ?.thumbnails
                ?.maxByOrNull { it.width ?: 0 }
                ?.url
                ?: "https://i.ytimg.com/vi/$videoId/hq720.jpg"
        val duration = parseLengthText(r.lengthText?.simpleText)
        val viewCount = parseYouTubeViewCount(r.viewCountText?.simpleText)
        val avatarUrls = r.channelAvatarUrls(channelThumbnailUrl)
        return io.github.aedev.flow.data.model.Video(
            id = videoId,
            title = title,
            channelName = channelName,
            channelId = channelId,
            thumbnailUrl = thumbnail,
            duration = duration,
            viewCount = viewCount,
            uploadDate = r.publishedTimeText?.simpleText ?: "",
            channelThumbnailUrl = avatarUrls.firstOrNull().orEmpty(),
            channelThumbnailUrls = avatarUrls,
        )
    }

    private fun io.github.aedev.flow.innertube.models.response.ChannelSearchResponse.VideoRenderer.channelAvatarUrls(
        fallback: String,
    ): List<String> {
        val supported = channelThumbnailSupportedRenderers
        val stackAvatars =
            listOfNotNull(
                avatarStackViewModel,
                supported?.avatarStackViewModel,
                supported?.channelThumbnailWithLinkRenderer?.avatarStack?.avatarStackViewModel,
            ).flatMap { stack ->
                stack.avatars.orEmpty().mapNotNull { avatar ->
                    avatar.avatarViewModel
                        ?.image
                        ?.sources
                        ?.maxByOrNull { maxOf(it.width ?: 0, it.height ?: 0) }
                        ?.url
                }
            }
        val linkedAvatar =
            supported
                ?.channelThumbnailWithLinkRenderer
                ?.thumbnail
                ?.thumbnails
                ?.maxByOrNull { maxOf(it.width ?: 0, it.height ?: 0) }
                ?.url

        return (stackAvatars + linkedAvatar + fallback)
            .filter { !it.isNullOrBlank() }
            .distinctBy { it.avatarImageIdentityKey() }
            .take(2)
            .filterNotNull()
    }

    private fun parseLengthText(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val parts = text.split(":").map { it.trim().toIntOrNull() ?: 0 }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> 0
        }
    }

    suspend fun album(
        browseId: String,
        withSongs: Boolean = true,
    ): Result<AlbumPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId).body<BrowseResponse>()
            if (browseId.contains("FEmusic_library_privately_owned_release_detail")) {
                val playlistId =
                    response.header
                        ?.musicDetailHeaderRenderer
                        ?.menu
                        ?.menuRenderer
                        ?.topLevelButtons
                        ?.firstOrNull()
                        ?.buttonRenderer
                        ?.navigationEndpoint
                        ?.watchPlaylistEndpoint
                        ?.playlistId!!
                val albumItem =
                    AlbumItem(
                        browseId = browseId,
                        playlistId = playlistId,
                        title =
                            response.header.musicDetailHeaderRenderer.title.runs
                                ?.firstOrNull()
                                ?.text!!,
                        artists =
                            response.header.musicDetailHeaderRenderer.subtitle.runs?.filter { it.navigationEndpoint != null }?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            },
                        year =
                            response.header.musicDetailHeaderRenderer.subtitle.runs
                                ?.lastOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        thumbnail =
                            response.header.musicDetailHeaderRenderer.thumbnail.croppedSquareThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()!!
                                .url,
                        explicit = false,
                    )
                return@runCatching AlbumPage(
                    album = albumItem,
                    songs =
                        response.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.musicShelfRenderer
                            ?.contents
                            ?.getItems()
                            ?.mapNotNull {
                                AlbumPage.getSong(it, albumItem)
                            }!!
                            .toMutableList(),
                    otherVersions = emptyList(),
                )
            } else {
                val header =
                    response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicResponsiveHeaderRenderer
                val playlistId =
                    response.microformat
                        ?.microformatDataRenderer
                        ?.urlCanonical
                        ?.substringAfterLast('=')!!
                val albumItem =
                    AlbumItem(
                        browseId = browseId,
                        playlistId = playlistId,
                        title =
                            response.contents
                                ?.twoColumnBrowseResultsRenderer
                                ?.tabs
                                ?.firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text!!,
                        artists =
                            response.contents.twoColumnBrowseResultsRenderer.tabs
                                .firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.straplineTextOne
                                ?.runs
                                ?.oddElements()
                                ?.map {
                                    Artist(
                                        name = it.text,
                                        id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                    )
                                }!!,
                        year =
                            response.contents.twoColumnBrowseResultsRenderer.tabs
                                .firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.subtitle
                                ?.runs
                                ?.lastOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        thumbnail =
                            response.contents.twoColumnBrowseResultsRenderer.tabs
                                .firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.thumbnail
                                ?.musicThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url!!,
                        explicit = AlbumPage.isExplicit(header),
                    )
                return@runCatching AlbumPage(
                    album = albumItem,
                    songs =
                        if (withSongs) {
                            albumSongs(
                                playlistId,
                                albumItem,
                            ).getOrThrow()
                        } else {
                            emptyList()
                        },
                    otherVersions = AlbumPage.otherVersions(response, albumItem),
                    durationText = AlbumPage.durationText(header),
                )
            }
        }

    suspend fun albumSongs(
        playlistId: String,
        album: AlbumItem? = null,
    ): Result<List<SongItem>> =
        runCatching {
            var response = innerTube.browse(WEB_REMIX, "VL$playlistId").body<BrowseResponse>()

            val twoColumnRenderer = response.contents?.twoColumnBrowseResultsRenderer
            val secondaryContents = twoColumnRenderer?.secondaryContents
            val sectionList = secondaryContents?.sectionListRenderer
            val firstContent = sectionList?.contents?.firstOrNull()
            val playlistShelf = firstContent?.musicPlaylistShelfRenderer

            val songs =
                playlistShelf
                    ?.contents
                    ?.getItems()
                    ?.mapNotNull {
                        AlbumPage.getSong(it, album)
                    }!!
                    .toMutableList()
            var continuation = playlistShelf?.contents?.getContinuation()
            val seenContinuations = mutableSetOf<String>()
            var requestCount = 0
            val maxRequests = 50 // Prevent excessive API calls

            while (continuation != null && requestCount < maxRequests) {
                // Prevent infinite loops by tracking seen continuations
                if (continuation in seenContinuations) {
                    break
                }
                seenContinuations.add(continuation)
                requestCount++

                response =
                    innerTube
                        .browse(
                            client = WEB_REMIX,
                            continuation = continuation,
                        ).body<BrowseResponse>()
                songs +=
                    response.onResponseReceivedActions
                        ?.firstOrNull()
                        ?.appendContinuationItemsAction
                        ?.continuationItems
                        ?.getItems()
                        ?.mapNotNull {
                            AlbumPage.getSong(it, album)
                        }.orEmpty()
                continuation =
                    response.continuationContents
                        ?.musicPlaylistShelfContinuation
                        ?.continuations
                        ?.getContinuation()
            }
            songs
        }

    suspend fun artist(browseId: String): Result<ArtistPage> =
        runCatching {
            ArtistPage.fromBrowseResponse(browseId, innerTube.browse(WEB_REMIX, browseId).body<BrowseResponse>())
        }

    suspend fun artistItems(endpoint: BrowseEndpoint): Result<ArtistItemsPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params).body<BrowseResponse>()
            val sectionContent =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()

            val gridRenderer = sectionContent?.gridRenderer
            val musicCarouselShelfRenderer = sectionContent?.musicCarouselShelfRenderer
            val musicPlaylistShelfRenderer = sectionContent?.musicPlaylistShelfRenderer
            val musicShelfRenderer = sectionContent?.musicShelfRenderer

            when {
                gridRenderer != null -> {
                    ArtistItemsPage(
                        title =
                            gridRenderer.header
                                ?.gridHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                .orEmpty(),
                        items =
                            gridRenderer.items.mapNotNull {
                                it.musicTwoRowItemRenderer?.let { renderer ->
                                    ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                                }
                            },
                        continuation = gridRenderer.continuations?.getContinuation(),
                    )
                }

                musicCarouselShelfRenderer != null -> {
                    ArtistItemsPage(
                        title =
                            musicCarouselShelfRenderer.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                .orEmpty(),
                        items =
                            musicCarouselShelfRenderer.contents.mapNotNull { content ->
                                content.musicTwoRowItemRenderer?.let { renderer ->
                                    ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                                } ?: content.musicResponsiveListItemRenderer?.let { renderer ->
                                    ArtistItemsPage.fromMusicResponsiveListItemRenderer(renderer)
                                }
                            },
                        continuation = null,
                    )
                }

                musicShelfRenderer != null -> {
                    ArtistItemsPage(
                        title =
                            musicShelfRenderer.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: response.header
                                    ?.musicHeaderRenderer
                                    ?.title
                                    ?.runs
                                    ?.firstOrNull()
                                    ?.text
                                ?: "",
                        items =
                            musicShelfRenderer.contents?.getItems()?.mapNotNull {
                                ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                            } ?: emptyList(),
                        continuation = musicShelfRenderer.continuations?.getContinuation(),
                    )
                }

                else -> {
                    ArtistItemsPage(
                        title =
                            response.header
                                ?.musicHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text ?: "",
                        items =
                            musicPlaylistShelfRenderer?.contents?.getItems()?.mapNotNull {
                                ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                            } ?: emptyList(),
                        continuation = musicPlaylistShelfRenderer?.contents?.getContinuation(),
                    )
                }
            }
        }

    suspend fun artistItemsContinuation(continuation: String): Result<ArtistItemsContinuationPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, continuation = continuation).body<BrowseResponse>()

            when {
                response.continuationContents?.gridContinuation != null -> {
                    val gridContinuation = response.continuationContents.gridContinuation
                    ArtistItemsContinuationPage(
                        items =
                            gridContinuation.items.mapNotNull {
                                it.musicTwoRowItemRenderer?.let { renderer ->
                                    ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                                }
                            },
                        continuation = gridContinuation.continuations?.getContinuation(),
                    )
                }

                response.continuationContents?.musicPlaylistShelfContinuation != null -> {
                    val musicPlaylistShelfContinuation = response.continuationContents.musicPlaylistShelfContinuation
                    ArtistItemsContinuationPage(
                        items =
                            musicPlaylistShelfContinuation.contents.getItems().mapNotNull {
                                ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                            },
                        continuation = musicPlaylistShelfContinuation.continuations?.getContinuation(),
                    )
                }

                response.continuationContents?.musicShelfContinuation != null -> {
                    val musicShelfContinuation = response.continuationContents!!.musicShelfContinuation!!
                    ArtistItemsContinuationPage(
                        items =
                            musicShelfContinuation.contents?.getItems()?.mapNotNull {
                                ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                            } ?: emptyList(),
                        continuation = musicShelfContinuation.continuations?.getContinuation(),
                    )
                }

                else -> {
                    val continuationItems =
                        response.onResponseReceivedActions
                            ?.firstOrNull()
                            ?.appendContinuationItemsAction
                            ?.continuationItems
                    ArtistItemsContinuationPage(
                        items =
                            continuationItems?.getItems()?.mapNotNull {
                                ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                            } ?: emptyList(),
                        continuation = continuationItems?.getContinuation(),
                    )
                }
            }
        }

    // A podcast's playlist page lists its episodes but has no header; the show page has one.
    private suspend fun podcastHeader(playlistId: String): MusicResponsiveHeaderRenderer? =
        runCatching {
            innerTube
                .browse(client = WEB_REMIX, browseId = "MPSP$playlistId", setLogin = true)
                .body<BrowseResponse>()
                .contents
                ?.twoColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.firstOrNull()
                ?.musicResponsiveHeaderRenderer
        }.getOrNull()

    suspend fun playlist(playlistId: String): Result<PlaylistPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "VL$playlistId",
                        setLogin = true,
                    ).body<BrowseResponse>()
            val base =
                response.contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()
            val header =
                base?.musicResponsiveHeaderRenderer
                    ?: base?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer
                    ?: podcastHeader(playlistId)

            val editable = base?.musicEditablePlaylistDetailHeaderRenderer != null

            PlaylistPage(
                playlist =
                    PlaylistItem(
                        id = playlistId,
                        title =
                            header
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text!!,
                        author =
                            header.straplineTextOne?.runs?.firstOrNull()?.let {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            },
                        songCountText =
                            header.secondSubtitle
                                ?.runs
                                ?.firstOrNull()
                                ?.text,
                        thumbnail =
                            header.thumbnail
                                ?.musicThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url!!,
                        playEndpoint = null,
                        shuffleEndpoint =
                            header.buttons
                                .lastOrNull()
                                ?.menuRenderer
                                ?.items
                                ?.firstOrNull()
                                ?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint,
                        radioEndpoint =
                            header.buttons
                                .getOrNull(2)
                                ?.menuRenderer
                                ?.items
                                ?.find {
                                    it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                                }?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint,
                        isEditable = editable,
                    ),
                songs =
                    response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.secondaryContents
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicPlaylistShelfRenderer
                        ?.contents
                        ?.getItems()
                        ?.mapNotNull {
                            PlaylistPage.fromMusicResponsiveListItemRenderer(it)
                        } ?: emptyList(),
                songsContinuation =
                    response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.secondaryContents
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicPlaylistShelfRenderer
                        ?.contents
                        ?.getContinuation(),
                continuation =
                    response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.secondaryContents
                        ?.sectionListRenderer
                        ?.continuations
                        ?.getContinuation(),
                trackCount = PlaylistPage.trackCountFrom(header.secondSubtitle),
            )
        }

    suspend fun playlistContinuation(continuation: String): Result<PlaylistContinuationPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        continuation = continuation,
                        browseId = "",
                        setLogin = true,
                    ).body<BrowseResponse>()

            val mainContents =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.contents
                    ?.flatMap { it.musicPlaylistShelfRenderer?.contents.orEmpty() }
                    ?: emptyList()

            val appendedContents =
                response.onResponseReceivedActions
                    ?.firstOrNull()
                    ?.appendContinuationItemsAction
                    ?.continuationItems
                    .orEmpty()

            val allContents = mainContents + appendedContents

            val songs =
                allContents
                    .mapNotNull { it.musicResponsiveListItemRenderer }
                    .mapNotNull { PlaylistPage.fromMusicResponsiveListItemRenderer(it) }

            val nextContinuation =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.continuations
                    ?.getContinuation()
                    ?: response.continuationContents
                        ?.musicPlaylistShelfContinuation
                        ?.continuations
                        ?.getContinuation()
                    ?: response.continuationContents
                        ?.musicShelfContinuation
                        ?.continuations
                        ?.getContinuation()
                    ?: response.onResponseReceivedActions
                        ?.firstOrNull()
                        ?.appendContinuationItemsAction
                        ?.continuationItems
                        ?.getContinuation()

            PlaylistContinuationPage(
                songs = songs,
                continuation = nextContinuation,
            )
        }

    suspend fun home(
        continuation: String? = null,
        browseId: String = "FEmusic_home",
        params: String? = null,
    ): Result<HomePage> =
        runCatching {
            if (continuation != null) {
                return@runCatching homeContinuation(continuation).getOrThrow()
            }

            val response = innerTube.browse(WEB_REMIX, browseId = browseId, params = params).body<BrowseResponse>()
            val continuation =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.continuations
                    ?.getContinuation()
            val sectionListRender =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
            val sections =
                sectionListRender
                    ?.contents!!
                    .mapNotNull { it.musicCarouselShelfRenderer }
                    .mapNotNull {
                        HomePage.Section.fromMusicCarouselShelfRenderer(it, includeSongRows = params != null)
                    }.toMutableList()
            val chips =
                sectionListRender.header
                    ?.chipCloudRenderer
                    ?.chips
                    ?.mapNotNull { HomePage.Chip.fromChipCloudChipRenderer(it) }
            HomePage(chips, sections, continuation)
        }

    private suspend fun homeContinuation(continuation: String): Result<HomePage> =
        runCatching {
            val response =
                innerTube.browse(WEB_REMIX, continuation = continuation).body<BrowseResponse>()
            val continuation =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.continuations
                    ?.getContinuation()
            HomePage(
                null,
                response.continuationContents
                    ?.sectionListContinuation
                    ?.contents
                    ?.mapNotNull { it.musicCarouselShelfRenderer }
                    ?.mapNotNull {
                        HomePage.Section.fromMusicCarouselShelfRenderer(it)
                    }.orEmpty(),
                continuation,
            )
        }

    suspend fun explore(): Result<ExplorePage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_explore").body<BrowseResponse>()
            ExplorePage(
                newReleaseAlbums =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.find {
                            it.musicCarouselShelfRenderer
                                ?.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.moreContentButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.browseEndpoint
                                ?.browseId == "FEmusic_new_releases_albums"
                        }?.musicCarouselShelfRenderer
                        ?.contents
                        ?.mapNotNull { it.musicTwoRowItemRenderer }
                        ?.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
                        .orEmpty(),
                moodAndGenres =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.find {
                            it.musicCarouselShelfRenderer
                                ?.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.moreContentButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.browseEndpoint
                                ?.browseId == "FEmusic_moods_and_genres"
                        }?.musicCarouselShelfRenderer
                        ?.contents
                        ?.mapNotNull { it.musicNavigationButtonRenderer }
                        ?.mapNotNull(MoodAndGenres.Companion::fromMusicNavigationButtonRenderer)
                        .orEmpty(),
            )
        }

    suspend fun newReleaseAlbums(): Result<List<AlbumItem>> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_new_releases_albums").body<BrowseResponse>()
            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.firstOrNull()
                ?.gridRenderer
                ?.items
                ?.mapNotNull { it.musicTwoRowItemRenderer }
                ?.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
                .orEmpty()
        }

    suspend fun moodAndGenres(): Result<List<MoodAndGenres>> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_moods_and_genres").body<BrowseResponse>()
            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents!!
                .mapNotNull(MoodAndGenres.Companion::fromSectionListRendererContent)
        }

    suspend fun browse(
        browseId: String,
        params: String?,
    ): Result<BrowseResult> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = browseId, params = params).body<BrowseResponse>()
            BrowseResult(
                title =
                    response.header
                        ?.musicHeaderRenderer
                        ?.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
                items =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.mapNotNull { content ->
                            when {
                                content.gridRenderer != null -> {
                                    BrowseResult.Item(
                                        title =
                                            content.gridRenderer.header
                                                ?.gridHeaderRenderer
                                                ?.title
                                                ?.runs
                                                ?.firstOrNull()
                                                ?.text,
                                        items =
                                            content.gridRenderer.items
                                                .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                                                .mapNotNull(RelatedPage.Companion::fromMusicTwoRowItemRenderer),
                                    )
                                }

                                content.musicCarouselShelfRenderer != null -> {
                                    BrowseResult.Item(
                                        title =
                                            content.musicCarouselShelfRenderer.header
                                                ?.musicCarouselShelfBasicHeaderRenderer
                                                ?.title
                                                ?.runs
                                                ?.firstOrNull()
                                                ?.text,
                                        items =
                                            content.musicCarouselShelfRenderer.contents
                                                .mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
                                                .mapNotNull(RelatedPage.Companion::fromMusicTwoRowItemRenderer),
                                    )
                                }

                                else -> {
                                    null
                                }
                            }
                        }.orEmpty(),
            )
        }

    suspend fun library(
        browseId: String,
        tabIndex: Int = 0,
    ) = runCatching {
        val response =
            innerTube
                .browse(
                    client = WEB_REMIX,
                    browseId = browseId,
                    setLogin = true,
                ).body<BrowseResponse>()

        val tabs = response.contents?.singleColumnBrowseResultsRenderer?.tabs

        val contents =
            if (tabs != null && tabs.size >= tabIndex) {
                tabs[tabIndex]
                    .tabRenderer.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()
            } else {
                null
            }

        when {
            contents?.gridRenderer != null -> {
                LibraryPage(
                    items =
                        contents.gridRenderer.items
                            .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                            .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) },
                    continuation = contents.gridRenderer.continuations?.getContinuation(),
                )
            }

            else -> { // contents?.musicShelfRenderer != null
                LibraryPage(
                    items =
                        contents
                            ?.musicShelfRenderer
                            ?.contents!!
                            .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                            .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) },
                    continuation = contents.musicShelfRenderer.continuations?.getContinuation(),
                )
            }
        }
    }

    suspend fun libraryContinuation(continuation: String) =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        continuation = continuation,
                        setLogin = true,
                    ).body<BrowseResponse>()

            val contents = response.continuationContents

            when {
                contents?.gridContinuation != null -> {
                    LibraryContinuationPage(
                        items =
                            contents.gridContinuation.items
                                .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                                .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) },
                        continuation = contents.gridContinuation.continuations?.getContinuation(),
                    )
                }

                else -> { // contents?.musicShelfContinuation != null
                    LibraryContinuationPage(
                        items =
                            contents
                                ?.musicShelfContinuation
                                ?.contents!!
                                .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                                .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) },
                        continuation = contents.musicShelfContinuation.continuations?.getContinuation(),
                    )
                }
            }
        }

    suspend fun libraryRecentActivity(): Result<LibraryPage> =
        runCatching {
            val continuation = LibraryFilter.FILTER_RECENT_ACTIVITY.value

            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        continuation = continuation,
                        setLogin = true,
                    ).body<BrowseResponse>()

            val items =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.contents
                    ?.firstOrNull()
                    ?.gridRenderer
                    ?.items!!
                    .mapNotNull {
                        it.musicTwoRowItemRenderer?.let { renderer ->
                            LibraryPage.fromMusicTwoRowItemRenderer(renderer)
                        }
                    }.toMutableList()

            items.forEachIndexed { index, item ->
                if (item is ArtistItem) {
                    items[index] = artist(item.id).getOrNull()?.artist!!.copy(thumbnail = item.thumbnail)
                }
            }

            LibraryPage(
                items = items,
                continuation = null,
            )
        }

    suspend fun getChartsPage(
        country: String? = null,
        continuation: String? = null,
    ): Result<ChartsPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_charts",
                        params = "ggMGCgQIgAQ%3D",
                        continuation = continuation,
                        formData = country?.let { BrowseBody.FormData(selectedValues = listOf(it)) },
                    ).body<BrowseResponse>()
            ChartsPage.fromBrowseResponse(response, country)
        }

    suspend fun musicHistory() =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_history",
                        setLogin = true,
                    ).body<BrowseResponse>()

            HistoryPage(
                sections =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.mapNotNull {
                            it.musicShelfRenderer?.let { musicShelfRenderer ->
                                HistoryPage.fromMusicShelfRenderer(musicShelfRenderer)
                            }
                        },
            )
        }

    suspend fun likeVideo(
        videoId: String,
        like: Boolean,
    ) = runCatching {
        if (like) {
            innerTube.likeVideo(WEB_REMIX, videoId)
        } else {
            innerTube.unlikeVideo(WEB_REMIX, videoId)
        }
    }

    suspend fun likePlaylist(
        playlistId: String,
        like: Boolean,
    ) = runCatching {
        if (like) {
            innerTube.likePlaylist(WEB_REMIX, playlistId)
        } else {
            innerTube.unlikePlaylist(WEB_REMIX, playlistId)
        }
    }

    suspend fun subscribeChannel(
        channelId: String,
        subscribe: Boolean,
    ) = runCatching {
        if (subscribe) {
            innerTube.subscribeChannel(WEB_REMIX, channelId)
        } else {
            innerTube.unsubscribeChannel(WEB_REMIX, channelId)
        }
    }

    suspend fun getChannelId(browseId: String): String {
        artist(browseId).onSuccess {
            return it.artist.channelId ?: ""
        }
        return ""
    }

    suspend fun addToPlaylist(
        playlistId: String,
        videoId: String,
    ) = runCatching {
        innerTube.addToPlaylist(WEB_REMIX, playlistId, videoId)
    }

    suspend fun addPlaylistToPlaylist(
        playlistId: String,
        addPlaylistId: String,
    ) = runCatching {
        innerTube.addPlaylistToPlaylist(WEB_REMIX, playlistId, addPlaylistId)
    }

    suspend fun removeFromPlaylist(
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) = runCatching {
        innerTube.removeFromPlaylist(WEB_REMIX, playlistId, videoId, setVideoId)
    }

    suspend fun moveSongPlaylist(
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String?,
    ) = runCatching {
        innerTube.moveSongPlaylist(WEB_REMIX, playlistId, setVideoId, successorSetVideoId)
    }

    fun createPlaylist(title: String) =
        runBlocking {
            innerTube.createPlaylist(WEB_REMIX, title).body<CreatePlaylistResponse>().playlistId
        }

    suspend fun renamePlaylist(
        playlistId: String,
        name: String,
    ) = runCatching {
        innerTube.renamePlaylist(WEB_REMIX, playlistId, name)
    }

    suspend fun uploadCustomThumbnailLink(
        playlistId: String,
        image: ByteArray,
    ) = runCatching {
        val uploadUrl = innerTube.getUploadCustomThumbnailLink(WEB_REMIX, image.size).headers["x-guploader-uploadid"]
        val blobReq =
            innerTube.uploadCustomThumbnail(
                WEB_REMIX,
                uploadUrl!!,
                image,
            )
        val blobId = Json.decodeFromString<ImageUploadResponse>(blobReq.bodyAsText()).encryptedBlobId
        innerTube
            .setThumbnailPlaylist(WEB_REMIX, playlistId, blobId)
            .body<EditPlaylistResponse>()
            .newHeader
            ?.musicEditablePlaylistDetailHeaderRenderer
            ?.header
            ?.musicResponsiveHeaderRenderer
            ?.thumbnail
            ?.musicThumbnailRenderer
            ?.getThumbnailUrl()
    }

    suspend fun removeThumbnailPlaylist(playlistId: String) =
        runCatching {
            innerTube
                .removeThumbnailPlaylist(WEB_REMIX, playlistId)
                .body<EditPlaylistResponse>()
                .newHeader
                ?.musicEditablePlaylistDetailHeaderRenderer
                ?.header
                ?.musicResponsiveHeaderRenderer
                ?.thumbnail
                ?.musicThumbnailRenderer
                ?.getThumbnailUrl()
        }

    suspend fun deletePlaylist(playlistId: String) =
        runCatching {
            innerTube.deletePlaylist(WEB_REMIX, playlistId)
        }

    suspend fun player(
        videoId: String,
        playlistId: String? = null,
        client: YouTubeClient,
        signatureTimestamp: Int? = null,
        poToken: String? = null,
        localeOverride: YouTubeLocale? = null,
        apiUrl: String? = null,
    ): Result<PlayerResponse> =
        runCatching {
            innerTube.player(client, videoId, playlistId, signatureTimestamp, poToken, localeOverride, apiUrl).body<PlayerResponse>()
        }

    suspend fun playerWeb(
        videoId: String,
        signatureTimestamp: Int?,
        poToken: String?,
        visitorData: String?,
        locale: YouTubeLocale,
        cpn: String?,
        reloadToken: String? = null,
        client: YouTubeClient = YouTubeClient.WEB,
    ): Result<PlayerResponse> =
        runCatching {
            innerTube
                .playerWeb(videoId, signatureTimestamp, poToken, visitorData, locale, cpn, reloadToken, client)
                .body<PlayerResponse>()
        }

    /**
     * The creator-declared category for [videoId].
     *
     * MWEB carries a `microformat` where the direct-URL clients the player path uses do not, and it
     * fills it without a `signatureTimestamp` — the response is UNPLAYABLE and carries no streams,
     * which is the point: it costs ~8 KB and no base.js fetch, against ~215 KB for WEB.
     */
    suspend fun videoCategory(videoId: String): Result<String?> =
        runCatching {
            innerTube
                .player(
                    YouTubeClient.MWEB,
                    videoId,
                    playlistId = null,
                    signatureTimestamp = null,
                    localeOverride = YouTubeLocale.EXTRACTION,
                    apiUrl = YouTubeClient.API_URL_YOUTUBE,
                ).body<PlayerResponse>()
                .microformat
                ?.playerMicroformatRenderer
                ?.category
                ?.takeIf { it.isNotBlank() }
        }

    suspend fun paidPromotion(videoId: String): Result<PaidPromotionLabel?> =
        runCatching { innerTube.paidPromotion(videoId).body<PaidPromotionResponse>().label() }

    /**
     * The raw web watch response for [videoId].
     *
     * The comment section, the attributed description and the related lane all read this one
     * response, so callers go through the cache that wraps it rather than requesting it each.
     */
    suspend fun watchNextJson(videoId: String): Result<JsonElement> =
        runCatching {
            Json.parseToJsonElement(innerTube.nextWatch(videoId = videoId).bodyAsText())
        }

    /** The description, its typed spans and the figures beside it, from an already-fetched watch response. */
    fun videoDescription(
        watchNext: JsonElement,
        videoId: String,
    ): VideoDescriptionPage = watchNext.toVideoDescriptionPage(videoId)

    /** The continuation that opens [videoId]'s comment section, from an already-fetched watch response. */
    fun commentsContinuation(watchNext: JsonElement): String? = watchNext.videoCommentsContinuation()

    /** The sort menu and count in [watchNext]'s comments panel, from an already-fetched watch response. */
    fun commentsPanelHeader(watchNext: JsonElement): VideoCommentsPanelHeader? = watchNext.videoCommentsPanelHeader()

    suspend fun comments(
        continuation: String,
        ownVideoId: String?,
    ): Result<VideoCommentsPage> =
        runCatching {
            Json
                .parseToJsonElement(innerTube.nextWatch(continuation = continuation).bodyAsText())
                .toVideoCommentsPage(ownVideoId)
        }

    suspend fun commentReplies(
        continuation: String,
        ownVideoId: String?,
    ): Result<VideoCommentsPage> =
        runCatching {
            Json
                .parseToJsonElement(innerTube.nextWatch(continuation = continuation).bodyAsText())
                .toCommentRepliesPage(ownVideoId)
        }

    suspend fun liveChatContinuation(videoId: String): Result<String?> =
        runCatching {
            innerTube
                .nextForLiveChat(videoId)
                .body<io.github.aedev.flow.innertube.models.response.LiveChatSeedResponse>()
                .seedContinuation()
        }

    suspend fun liveChat(
        continuation: String,
        offsetMs: Long? = null,
    ): Result<io.github.aedev.flow.innertube.models.response.GetLiveChatResponse> =
        runCatching {
            innerTube
                .getLiveChat(continuation, offsetMs)
                .body<io.github.aedev.flow.innertube.models.response.GetLiveChatResponse>()
        }

    suspend fun watchMetadata(videoId: String): Result<io.github.aedev.flow.innertube.models.response.WatchMetadataResponse> =
        runCatching {
            val primary =
                innerTube
                    .next(WEB, videoId, null, null, null, null, null)
                    .body<io.github.aedev.flow.innertube.models.response.WatchMetadataResponse>()
            if (primary.relatedVideos().isNotEmpty()) {
                primary
            } else {
                val webWatch =
                    runCatching {
                        innerTube
                            .nextForLiveChat(videoId)
                            .body<io.github.aedev.flow.innertube.models.response.WatchMetadataResponse>()
                    }.getOrNull()
                if (webWatch != null && webWatch.relatedVideos().size > primary.relatedVideos().size) {
                    webWatch
                } else {
                    primary
                }
            }
        }

    suspend fun watchMetadataLite(videoId: String): Result<io.github.aedev.flow.innertube.models.response.WatchMetadataResponse> =
        runCatching {
            innerTube
                .next(WEB, videoId, null, null, null, null, null)
                .body<io.github.aedev.flow.innertube.models.response.WatchMetadataResponse>()
        }

    suspend fun registerPlayback(
        playlistId: String? = null,
        playbackTracking: String,
    ) = runCatching {
        val cpn =
            (1..16)
                .map {
                    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"[
                        Random.Default.nextInt(
                            0,
                            64,
                        ),
                    ]
                }.joinToString("")

        val playbackUrl =
            playbackTracking.replace(
                "https://s.youtube.com",
                "https://music.youtube.com",
            )

        innerTube.registerPlayback(
            url = playbackUrl,
            playlistId = playlistId,
            cpn = cpn,
        )
    }

    suspend fun next(
        endpoint: WatchEndpoint,
        continuation: String? = null,
    ): Result<NextResult> =
        runCatching {
            val response =
                innerTube
                    .next(
                        WEB_REMIX,
                        endpoint.videoId,
                        endpoint.playlistId,
                        endpoint.playlistSetVideoId,
                        endpoint.index,
                        endpoint.params,
                        continuation,
                    ).body<NextResponse>()
            // YouTube inserts/reorders watch-next tabs (a Comments tab appeared at
            // index 2 in 2026), so lyrics/related must be found by browseId prefix,
            // never by position.
            val watchNextTabs =
                response.contents.singleColumnMusicWatchNextResultsRenderer
                    ?.tabbedRenderer
                    ?.watchNextTabbedResultsRenderer
                    ?.tabs
            val lyricsBrowseEndpoint = response.lyricsBrowseEndpoint()
            val relatedBrowseEndpoint =
                watchNextTabs?.firstNotNullOfOrNull { tab ->
                    tab.tabRenderer.endpoint
                        ?.browseEndpoint
                        ?.takeIf { it.browseId.startsWith("MPTRt") }
                }
            val playlistPanelRenderer =
                response.continuationContents?.playlistPanelContinuation
                    ?: response.contents.singleColumnMusicWatchNextResultsRenderer
                        ?.tabbedRenderer
                        ?.watchNextTabbedResultsRenderer
                        ?.tabs
                        ?.get(0)
                        ?.tabRenderer
                        ?.content
                        ?.musicQueueRenderer
                        ?.content
                        ?.playlistPanelRenderer!!
            val title =
                response.contents.singleColumnMusicWatchNextResultsRenderer
                    ?.tabbedRenderer
                    ?.watchNextTabbedResultsRenderer
                    ?.tabs
                    ?.get(0)
                    ?.tabRenderer
                    ?.content
                    ?.musicQueueRenderer
                    ?.header
                    ?.musicQueueHeaderRenderer
                    ?.subtitle
                    ?.runs
                    ?.firstOrNull()
                    ?.text
            val items =
                playlistPanelRenderer.contents.mapNotNull { content ->
                    content.playlistPanelVideoRenderer
                        ?.let(NextPage::fromPlaylistPanelVideoRenderer)
                        ?.let { it to content.playlistPanelVideoRenderer.selected }
                }
            val songs = items.map { it.first }
            val currentIndex = items.indexOfFirst { it.second }.takeIf { it != -1 }

            // load automix items
            playlistPanelRenderer.contents
                .lastOrNull()
                ?.automixPreviewVideoRenderer
                ?.content
                ?.automixPlaylistVideoRenderer
                ?.navigationEndpoint
                ?.watchPlaylistEndpoint
                ?.let { watchPlaylistEndpoint ->
                    return@runCatching next(watchPlaylistEndpoint).getOrThrow().let { result ->
                        result.copy(
                            title = title,
                            items = songs + result.items,
                            lyricsEndpoint = lyricsBrowseEndpoint,
                            relatedEndpoint = relatedBrowseEndpoint,
                            currentIndex = currentIndex,
                            endpoint = watchPlaylistEndpoint,
                        )
                    }
                }
            NextResult(
                title = title,
                items = songs,
                currentIndex = currentIndex,
                lyricsEndpoint = lyricsBrowseEndpoint,
                relatedEndpoint = relatedBrowseEndpoint,
                continuation = playlistPanelRenderer.continuations?.getContinuation(),
                endpoint = endpoint,
            )
        }

    private fun NextResponse.lyricsBrowseEndpoint(): BrowseEndpoint? =
        contents.singleColumnMusicWatchNextResultsRenderer
            ?.tabbedRenderer
            ?.watchNextTabbedResultsRenderer
            ?.tabs
            ?.firstNotNullOfOrNull { tab ->
                tab.tabRenderer.endpoint
                    ?.browseEndpoint
                    ?.takeIf { it.browseId.startsWith("MPLYt") }
            }

    /** One `next` and nothing else: [next] follows the automix row, which is a second, slower request. */
    suspend fun lyricsEndpoint(videoId: String): Result<BrowseEndpoint?> =
        runCatching {
            innerTube
                .next(WEB_REMIX, videoId, null, null, null, null)
                .body<NextResponse>()
                .lyricsBrowseEndpoint()
        }

    suspend fun timedLyrics(endpoint: BrowseEndpoint): Result<List<TimedLyricLine>> =
        runCatching {
            timedLyricLines(innerTube.browse(ANDROID_MUSIC, endpoint.browseId, endpoint.params).body<JsonObject>())
        }

    suspend fun lyrics(endpoint: BrowseEndpoint): Result<String?> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params).body<BrowseResponse>()
            response.contents
                ?.sectionListRenderer
                ?.contents
                ?.firstOrNull()
                ?.musicDescriptionShelfRenderer
                ?.description
                ?.runs
                ?.firstOrNull()
                ?.text
        }

    suspend fun related(endpoint: BrowseEndpoint): Result<RelatedPage> =
        runCatching {
            RelatedPage.fromBrowseResponse(innerTube.browse(WEB_REMIX, endpoint.browseId).body<BrowseResponse>())
        }

    suspend fun queue(
        videoIds: List<String>? = null,
        playlistId: String? = null,
    ): Result<List<SongItem>> =
        runCatching {
            if (videoIds != null) {
                assert(videoIds.size <= MAX_GET_QUEUE_SIZE) // Max video limit
            }
            innerTube
                .getQueue(WEB_REMIX, videoIds, playlistId)
                .body<GetQueueResponse>()
                .queueDatas
                .mapNotNull {
                    it.content.playlistPanelVideoRenderer?.let { renderer ->
                        NextPage.fromPlaylistPanelVideoRenderer(renderer)
                    }
                }
        }

    suspend fun transcript(videoId: String): Result<String> =
        runCatching {
            val response = innerTube.getTranscript(WEB_REMIX, videoId).body<GetTranscriptResponse>()
            response.actions
                ?.firstOrNull()
                ?.updateEngagementPanelAction
                ?.content
                ?.transcriptRenderer
                ?.body
                ?.transcriptBodyRenderer
                ?.cueGroups
                ?.joinToString(separator = "\n") { group ->
                    val time =
                        group.transcriptCueGroupRenderer.cues[0]
                            .transcriptCueRenderer.startOffsetMs
                    val text =
                        group.transcriptCueGroupRenderer.cues[0]
                            .transcriptCueRenderer.cue.simpleText
                            .trim('♪')
                            .trim(' ')
                    "[%02d:%02d.%03d]$text".format(time / 60000, (time / 1000) % 60, time % 1000)
                }!!
        }

    suspend fun visitorData(): Result<String> =
        runCatching {
            Json
                .parseToJsonElement(innerTube.getSwJsData().bodyAsText().substring(5))
                .jsonArray[0]
                .jsonArray[2]
                .jsonArray
                .first {
                    (it as? JsonPrimitive)?.contentOrNull?.let { candidate ->
                        VISITOR_DATA_REGEX.containsMatchIn(candidate)
                    } ?: false
                }.jsonPrimitive.content
        }

    suspend fun accountInfo(): Result<AccountInfo> =
        runCatching {
            innerTube
                .accountMenu(WEB_REMIX)
                .body<AccountMenuResponse>()
                .actions[0]
                .openPopupAction.popup.multiPageMenuRenderer
                .header
                ?.activeAccountHeaderRenderer
                ?.toAccountInfo()!!
        }

    suspend fun getMediaInfo(videoId: String): Result<MediaInfo> =
        runCatching {
            return innerTube.getMediaInfo(videoId)
        }

    suspend fun returnYouTubeDislike(videoId: String): Result<ReturnYouTubeDislikeResponse> =
        runCatching {
            innerTube.returnYouTubeDislike(videoId).body<ReturnYouTubeDislikeResponse>()
        }

    @JvmInline
    value class SearchFilter(
        val value: String,
    ) {
        companion object {
            val FILTER_SONG = SearchFilter("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            val FILTER_VIDEO = SearchFilter("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ALBUM = SearchFilter("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ARTIST = SearchFilter("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_FEATURED_PLAYLIST = SearchFilter("EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D")
            val FILTER_COMMUNITY_PLAYLIST = SearchFilter("EgeKAQQoAEABagoQAxAEEAoQCRAF")
            val FILTER_PODCAST = SearchFilter("EgWKAQJQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_EPISODE = SearchFilter("EgWKAQJIAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_PROFILE = SearchFilter("EgWKAQJYAWoSEAUQCRADEAQQEBAVEAoQDhAR")
        }
    }

    @JvmInline
    value class LibraryFilter(
        val value: String,
    ) {
        companion object {
            val FILTER_RECENT_ACTIVITY = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpYnJhcnlfbGFuZGluZxoQZ2dNR0tnUUlCaEFCb0FZQg%3D%3D")
            val FILTER_RECENTLY_PLAYED = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpYnJhcnlfbGFuZGluZxoQZ2dNR0tnUUlCUkFCb0FZQg%3D%3D")
            val FILTER_PLAYLISTS_ALPHABETICAL = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBUkFBb0FZQg%3D%3D")
            val FILTER_PLAYLISTS_RECENTLY_SAVED = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D")
        }
    }

    const val MAX_GET_QUEUE_SIZE = 1000

    /**
     * One page of the reel feed: the seedless first page for a null token, else the page a
     * previous response's continuation names. Entries carry ids and tokens only; see [reelOverlay].
     *
     * IOS first because its page is a few kilobytes per entry; ANDROID's carries a serialized
     * prefetch of every reel's player response and runs to several megabytes for the same ids.
     */
    suspend fun shorts(sequenceParams: String? = null): Result<ReelSequencePage> =
        runCatching {
            ensureVisitorData()
            val params = sequenceParams ?: ReelParams.INITIAL_SEQUENCE
            runCatching { reelSequence(YouTubeClient.IOS, params) }
                .getOrNull()
                ?.takeIf { it.entries.isNotEmpty() }
                ?: reelSequence(YouTubeClient.ANDROID, params)
        }

    private suspend fun reelSequence(
        client: YouTubeClient,
        sequenceParams: String,
    ): ReelSequencePage = innerTube.reel(client = client, sequenceParams = sequenceParams).body<JsonObject>().toReelSequencePage()

    /** The reels that follow [videoId]. The response never contains the seed itself. */
    suspend fun shortsFromVideo(videoId: String): Result<ReelSequencePage> = shorts(ReelParams.seedSequenceParams(videoId))

    /** Title, channel, counts and sound for one reel — everything the sequence leaves out. */
    suspend fun reelOverlay(videoId: String): Result<ReelOverlay?> =
        runCatching {
            innerTube.reelItemWatch(client = WEB, videoId = videoId).body<JsonObject>().toReelOverlay()
        }

    private val VISITOR_DATA_REGEX = Regex("^Cg[t|s]")
}
