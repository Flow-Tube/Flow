package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.innertube.YouTubeConstants
import io.github.aedev.flow.innertube.models.Album
import io.github.aedev.flow.innertube.models.AlbumItem
import io.github.aedev.flow.innertube.models.Artist
import io.github.aedev.flow.innertube.models.ArtistItem
import io.github.aedev.flow.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import io.github.aedev.flow.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import io.github.aedev.flow.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import io.github.aedev.flow.innertube.models.MusicCardShelfRenderer
import io.github.aedev.flow.innertube.models.MusicResponsiveListItemRenderer
import io.github.aedev.flow.innertube.models.MusicShelfRenderer
import io.github.aedev.flow.innertube.models.PlaylistItem
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.models.YTItem
import io.github.aedev.flow.innertube.models.clean
import io.github.aedev.flow.innertube.models.getContinuation
import io.github.aedev.flow.innertube.models.getItems
import io.github.aedev.flow.innertube.models.oddElements
import io.github.aedev.flow.innertube.models.response.SearchResponse
import io.github.aedev.flow.innertube.models.splitBySeparator
import io.github.aedev.flow.innertube.utils.parseTime

/** What a search summary section holds; [SHELF] is a section the server titled itself. */
enum class SearchSummaryKind {
    TOP_RESULT,
    SONGS,
    VIDEOS,
    EPISODES,
    ALBUMS,
    ARTISTS,
    PLAYLISTS,
    SHELF,
}

data class SearchSummary(
    val title: String,
    val items: List<YTItem>,
    val kind: SearchSummaryKind = SearchSummaryKind.SHELF,
)

/** [filterParams]: the search params of the filter chips YouTube offers for this query, in its order. */
data class SearchSummaryPage(
    val summaries: List<SearchSummary>,
    val continuation: String? = null,
    val filterParams: List<String> = emptyList(),
) {
    companion object {
        private const val MUSIC_VIDEO_TYPE_PODCAST_EPISODE = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"

        /**
         * Reads both layouts the unfiltered search has shipped: titled shelves with a continuation,
         * and the current one where every result is its own item section with no continuation. Runs
         * of item sections are grouped by what the items are, in the order each kind first appears.
         */
        fun fromSearchResponse(response: SearchResponse): SearchSummaryPage {
            val sectionList =
                response.contents
                    ?.tabbedSearchResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
            val contents = sectionList?.contents.orEmpty()
            val summaries = mutableListOf<SearchSummary>()
            val loose = mutableListOf<YTItem>()

            fun flushLoose() {
                summaries += groupedByKind(loose)
                loose.clear()
            }
            contents.forEach { content ->
                val itemSection = content.itemSectionRenderer
                if (itemSection != null) {
                    itemSection.contents
                        .orEmpty()
                        .getItems()
                        .mapNotNullTo(loose, ::fromMusicResponsiveListItemRenderer)
                } else {
                    flushLoose()
                    val summary =
                        content.musicCardShelfRenderer?.let(::topResult)
                            ?: content.musicShelfRenderer?.let(::titledShelf)
                    summary?.let(summaries::add)
                }
            }
            flushLoose()
            return SearchSummaryPage(
                summaries = summaries,
                continuation =
                    contents
                        .lastOrNull()
                        ?.musicShelfRenderer
                        ?.continuations
                        ?.getContinuation(),
                filterParams =
                    sectionList
                        ?.header
                        ?.chipCloudRenderer
                        ?.chips
                        ?.mapNotNull {
                            it.chipCloudChipRenderer.navigationEndpoint.searchEndpoint
                                ?.params
                        }.orEmpty(),
            )
        }

        private fun topResult(renderer: MusicCardShelfRenderer): SearchSummary? {
            val card = fromMusicCardShelfRenderer(renderer)
            val items =
                listOfNotNull(card)
                    .plus(
                        renderer.contents
                            ?.mapNotNull { it.musicResponsiveListItemRenderer }
                            ?.mapNotNull(::fromMusicResponsiveListItemRenderer)
                            ?.map { it.creditedToCard(card) }
                            .orEmpty(),
                    ).distinctBy { it.id }
            if (items.isEmpty()) return null
            return SearchSummary(
                title =
                    renderer.header
                        ?.musicCardShelfHeaderBasicRenderer
                        ?.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text ?: YouTubeConstants.DEFAULT_TOP_RESULT,
                items = items,
                kind = SearchSummaryKind.TOP_RESULT,
            )
        }

        /**
         * A song under an artist's top result card names no artist: its only subtitle is the
         * length, which the row parser would read as the artist. It is that artist's song.
         */
        private fun YTItem.creditedToCard(card: YTItem?): YTItem =
            if (card is ArtistItem && this is SongItem && artists.none { it.id != null }) {
                copy(artists = listOf(Artist(name = card.title, id = card.id)))
            } else {
                this
            }

        private fun titledShelf(renderer: MusicShelfRenderer): SearchSummary? {
            val items =
                renderer.contents
                    ?.getItems()
                    ?.mapNotNull(::fromMusicResponsiveListItemRenderer)
                    ?.distinctBy { it.id }
                    .orEmpty()
            if (items.isEmpty()) return null
            return SearchSummary(
                title =
                    renderer.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text ?: YouTubeConstants.DEFAULT_OTHER_RESULTS,
                items = items,
            )
        }

        private fun groupedByKind(items: List<YTItem>): List<SearchSummary> =
            items
                .distinctBy { it::class to it.id }
                .groupBy(::kindOf)
                .map { (kind, grouped) -> SearchSummary(title = "", items = grouped, kind = kind) }

        private fun kindOf(item: YTItem): SearchSummaryKind =
            when (item) {
                is SongItem -> {
                    when {
                        item.musicVideoType == MUSIC_VIDEO_TYPE_PODCAST_EPISODE -> SearchSummaryKind.EPISODES
                        item.isVideoSong -> SearchSummaryKind.VIDEOS
                        else -> SearchSummaryKind.SONGS
                    }
                }

                is AlbumItem -> {
                    SearchSummaryKind.ALBUMS
                }

                is ArtistItem -> {
                    SearchSummaryKind.ARTISTS
                }

                is PlaylistItem -> {
                    SearchSummaryKind.PLAYLISTS
                }
            }

        fun fromMusicCardShelfRenderer(renderer: MusicCardShelfRenderer): YTItem? {
            val subtitle = renderer.subtitle.runs?.splitBySeparator()
            return when {
                renderer.onTap.watchEndpoint != null -> {
                    SongItem(
                        id = renderer.onTap.watchEndpoint.videoId ?: return null,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists =
                            subtitle?.getOrNull(1)?.oddElements()?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            } ?: return null,
                        album =
                            subtitle.getOrNull(2)?.firstOrNull()?.takeIf { it.navigationEndpoint?.browseEndpoint != null }?.let {
                                Album(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId!!,
                                )
                            },
                        duration =
                            subtitle
                                .lastOrNull()
                                ?.firstOrNull()
                                ?.text
                                ?.parseTime(),
                        musicVideoType = renderer.onTap.musicVideoType,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.onTap.browseEndpoint?.isArtistEndpoint == true -> {
                    ArtistItem(
                        id = renderer.onTap.browseEndpoint.browseId,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        shuffleEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint ?: return null,
                        radioEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MIX" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint ?: return null,
                    )
                }

                renderer.onTap.browseEndpoint?.isAlbumEndpoint == true -> {
                    AlbumItem(
                        browseId = renderer.onTap.browseEndpoint.browseId,
                        playlistId =
                            renderer.buttons
                                .firstOrNull()
                                ?.buttonRenderer
                                ?.command
                                ?.anyWatchEndpoint
                                ?.playlistId ?: return null,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists =
                            subtitle?.getOrNull(1)?.oddElements()?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            } ?: return null,
                        year = null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.subtitleBadges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.onTap.browseEndpoint?.isPlaylistEndpoint == true -> {
                    PlaylistItem(
                        id =
                            renderer.onTap.browseEndpoint.browseId
                                .removePrefix("VL"),
                        title =
                            renderer.header
                                ?.musicCardShelfHeaderBasicRenderer
                                ?.title
                                ?.runs
                                ?.joinToString(separator = "") { it.text }
                                ?: return null,
                        author =
                            Artist(
                                id = null,
                                name = renderer.subtitle.runs?.joinToString { it.text } ?: return null,
                            ),
                        songCountText = null,
                        thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        playEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "PLAY_ARROW" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint
                                ?: return null,
                        shuffleEndpoint =
                            renderer.buttons
                                .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.buttonRenderer
                                ?.command
                                ?.watchPlaylistEndpoint
                                ?: return null,
                        radioEndpoint = null,
                    )
                }

                else -> {
                    null
                }
            }
        }

        fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): YTItem? {
            val secondaryLine =
                renderer.flexColumns
                    .getOrNull(1)
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs
                    ?.splitBySeparator()
                    ?: return null
            val thirdLine =
                renderer.flexColumns
                    .getOrNull(2)
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs
                    ?.splitBySeparator()
                    ?: emptyList()
            val listRun = (secondaryLine + thirdLine).clean()
            return when {
                renderer.isSong -> {
                    SongItem(
                        id = renderer.videoId ?: return null,
                        title =
                            renderer.flexColumns
                                .firstOrNull()
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists =
                            listRun.getOrNull(0)?.oddElements()?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            } ?: return null,
                        album =
                            listRun.getOrNull(1)?.firstOrNull()?.takeIf { it.navigationEndpoint?.browseEndpoint != null }?.let {
                                Album(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId!!,
                                )
                            },
                        duration =
                            secondaryLine
                                .lastOrNull()
                                ?.firstOrNull()
                                ?.text
                                ?.parseTime(),
                        musicVideoType = renderer.musicVideoType,
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.badges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.isArtist -> {
                    ArtistItem(
                        id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                        title =
                            renderer.flexColumns
                                .firstOrNull()
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: return null,
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        shuffleEndpoint =
                            renderer.menuPlaylistEndpoint("MUSIC_SHUFFLE") ?: return null,
                        radioEndpoint =
                            renderer.menuPlaylistEndpoint("MIX") ?: return null,
                    )
                }

                renderer.isAlbum -> {
                    AlbumItem(
                        browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                        playlistId =
                            renderer.overlay
                                ?.musicItemThumbnailOverlayRenderer
                                ?.content
                                ?.musicPlayButtonRenderer
                                ?.playNavigationEndpoint
                                ?.anyWatchEndpoint
                                ?.playlistId
                                ?: renderer.menuPlaylistEndpoint("MUSIC_SHUFFLE")?.playlistId
                                ?: return null,
                        title =
                            renderer.flexColumns
                                .firstOrNull()
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists =
                            secondaryLine.getOrNull(1)?.oddElements()?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            } ?: return null,
                        year =
                            secondaryLine
                                .getOrNull(2)
                                ?.firstOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.badges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.isPlaylist -> {
                    PlaylistItem(
                        id =
                            renderer.navigationEndpoint
                                ?.browseEndpoint
                                ?.browseId
                                ?.removePrefix("VL") ?: return null,
                        title =
                            renderer.flexColumns
                                .firstOrNull()
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        author =
                            secondaryLine.getOrNull(1)?.firstOrNull()?.let {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            } ?: return null,
                        songCountText =
                            renderer.flexColumns
                                .getOrNull(1)
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.lastOrNull()
                                ?.text ?: return null,
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        playEndpoint =
                            renderer.overlay
                                ?.musicItemThumbnailOverlayRenderer
                                ?.content
                                ?.musicPlayButtonRenderer
                                ?.playNavigationEndpoint
                                ?.watchPlaylistEndpoint
                                ?: renderer.menuPlaylistEndpoint("MUSIC_SHUFFLE")
                                ?: return null,
                        shuffleEndpoint =
                            renderer.menuPlaylistEndpoint("MUSIC_SHUFFLE") ?: return null,
                        radioEndpoint =
                            renderer.menuPlaylistEndpoint("MIX"),
                    )
                }

                else -> {
                    null
                }
            }
        }
    }
}
