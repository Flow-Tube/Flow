package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.innertube.models.Album
import io.github.aedev.flow.innertube.models.AlbumItem
import io.github.aedev.flow.innertube.models.Artist
import io.github.aedev.flow.innertube.models.ArtistItem
import io.github.aedev.flow.innertube.models.BrowseEndpoint
import io.github.aedev.flow.innertube.models.MusicCarouselShelfRenderer
import io.github.aedev.flow.innertube.models.MusicTwoRowItemRenderer
import io.github.aedev.flow.innertube.models.PlaylistItem
import io.github.aedev.flow.innertube.models.SectionListRenderer
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.models.YTItem
import io.github.aedev.flow.innertube.models.filterExplicit
import io.github.aedev.flow.innertube.models.filterVideoSongs
import io.github.aedev.flow.innertube.models.oddElements

data class HomePage(
    val chips: List<Chip>?,
    val sections: List<Section>,
    val continuation: String? = null,
) {
    data class Chip(
        val title: String,
        val endpoint: BrowseEndpoint?,
        val deselectEndPoint: BrowseEndpoint?,
    ) {
        companion object {
            fun fromChipCloudChipRenderer(renderer: SectionListRenderer.Header.ChipCloudRenderer.Chip): Chip? {
                return Chip(
                    title =
                        renderer.chipCloudChipRenderer.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text ?: return null,
                    endpoint = renderer.chipCloudChipRenderer.navigationEndpoint.browseEndpoint,
                    deselectEndPoint = renderer.chipCloudChipRenderer.onDeselectedCommand?.browseEndpoint,
                )
            }
        }
    }

    data class Section(
        val title: String,
        val label: String?,
        val thumbnail: String?,
        val endpoint: BrowseEndpoint?,
        val items: List<YTItem>,
    ) {
        companion object {
            /**
             * [includeSongRows] keeps a shelf's list rows, which is how a chip's Quick picks arrives. The
             * normal home composes its own Quick picks, so it asks only for the two-row cards.
             */
            fun fromMusicCarouselShelfRenderer(
                renderer: MusicCarouselShelfRenderer,
                includeSongRows: Boolean = false,
            ): Section? {
                return Section(
                    title =
                        renderer.header
                            ?.musicCarouselShelfBasicHeaderRenderer
                            ?.title
                            ?.runs
                            ?.firstOrNull()
                            ?.text ?: return null,
                    label =
                        renderer.header.musicCarouselShelfBasicHeaderRenderer.strapline
                            ?.runs
                            ?.firstOrNull()
                            ?.text,
                    thumbnail =
                        renderer.header.musicCarouselShelfBasicHeaderRenderer.thumbnail
                            ?.musicThumbnailRenderer
                            ?.getThumbnailUrl(),
                    endpoint =
                        renderer.header.musicCarouselShelfBasicHeaderRenderer.moreContentButton
                            ?.buttonRenderer
                            ?.navigationEndpoint
                            ?.browseEndpoint,
                    items =
                        renderer.contents
                            .mapNotNull { content ->
                                content.musicTwoRowItemRenderer?.let(::fromMusicTwoRowItemRenderer)
                                    ?: content.musicResponsiveListItemRenderer
                                        ?.takeIf { includeSongRows }
                                        ?.let(RelatedPage::fromMusicResponsiveListItemRenderer)
                            }.ifEmpty {
                                return null
                            },
                )
            }

            internal fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer): YTItem? {
                return when {
                    renderer.isSong -> {
                        val subtitleRuns = renderer.subtitle?.runs ?: return null
                        val (artistRuns, albumRuns) =
                            subtitleRuns.partition { run ->
                                run.navigationEndpoint
                                    ?.browseEndpoint
                                    ?.browseId
                                    ?.startsWith("UC") == true
                            }
                        val artists =
                            artistRuns.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                                )
                            }
                        val viewCountText =
                            subtitleRuns
                                .find {
                                    it.text.endsWith("views", ignoreCase = true) ||
                                        it.text.endsWith("view", ignoreCase = true)
                                }?.text

                        SongItem(
                            id = renderer.navigationEndpoint.watchEndpoint?.videoId ?: return null,
                            title =
                                renderer.title.runs
                                    ?.firstOrNull()
                                    ?.text ?: return null,
                            artists = artists,
                            album =
                                albumRuns
                                    .firstOrNull { run ->
                                        run.navigationEndpoint
                                            ?.browseEndpoint
                                            ?.browseId
                                            ?.startsWith("MPREb_") == true
                                    }?.let { run ->
                                        val endpoint = run.navigationEndpoint?.browseEndpoint ?: return null
                                        Album(
                                            name = run.text,
                                            id = endpoint.browseId,
                                        )
                                    },
                            duration = null,
                            musicVideoType = renderer.musicVideoType,
                            thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                            explicit =
                                renderer.subtitleBadges?.any {
                                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                                } == true,
                            viewCountText = viewCountText,
                        )
                    }

                    renderer.isAlbum -> {
                        AlbumItem(
                            browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                            playlistId =
                                renderer.thumbnailOverlay
                                    ?.musicItemThumbnailOverlayRenderer
                                    ?.content
                                    ?.musicPlayButtonRenderer
                                    ?.playNavigationEndpoint
                                    ?.watchPlaylistEndpoint
                                    ?.playlistId ?: return null,
                            title =
                                renderer.title.runs
                                    ?.firstOrNull()
                                    ?.text ?: return null,
                            artists =
                                renderer.subtitle?.runs?.oddElements()?.drop(1)?.map {
                                    Artist(
                                        name = it.text,
                                        id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                    )
                                },
                            year = null,
                            thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                            explicit =
                                renderer.subtitleBadges?.find {
                                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                                } != null,
                        )
                    }

                    renderer.isPlaylist -> {
                        PlaylistItem(
                            id =
                                renderer.navigationEndpoint.browseEndpoint
                                    ?.browseId
                                    ?.removePrefix("VL") ?: return null,
                            title =
                                renderer.title.runs
                                    ?.firstOrNull()
                                    ?.text ?: return null,
                            author =
                                Artist(
                                    name =
                                        renderer.subtitle
                                            ?.runs
                                            ?.lastOrNull()
                                            ?.text ?: return null,
                                    id = null,
                                ),
                            songCountText = null,
                            thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                            playEndpoint =
                                renderer.thumbnailOverlay
                                    ?.musicItemThumbnailOverlayRenderer
                                    ?.content
                                    ?.musicPlayButtonRenderer
                                    ?.playNavigationEndpoint
                                    ?.watchPlaylistEndpoint ?: return null,
                            shuffleEndpoint =
                                renderer.menu
                                    ?.menuRenderer
                                    ?.items
                                    ?.find {
                                        it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                                    }?.menuNavigationItemRenderer
                                    ?.navigationEndpoint
                                    ?.watchPlaylistEndpoint ?: return null,
                            radioEndpoint =
                                renderer.menu.menuRenderer.items
                                    .find {
                                        it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                                    }?.menuNavigationItemRenderer
                                    ?.navigationEndpoint
                                    ?.watchPlaylistEndpoint,
                        )
                    }

                    renderer.isArtist -> {
                        ArtistItem(
                            id = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                            title =
                                renderer.title.runs
                                    ?.lastOrNull()
                                    ?.text ?: return null,
                            thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                            shuffleEndpoint =
                                renderer.menu
                                    ?.menuRenderer
                                    ?.items
                                    ?.find {
                                        it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                                    }?.menuNavigationItemRenderer
                                    ?.navigationEndpoint
                                    ?.watchPlaylistEndpoint ?: return null,
                            radioEndpoint =
                                renderer.menu.menuRenderer.items
                                    .find {
                                        it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                                    }?.menuNavigationItemRenderer
                                    ?.navigationEndpoint
                                    ?.watchPlaylistEndpoint ?: return null,
                        )
                    }

                    else -> {
                        null
                    }
                }
            }
        }
    }

    fun filterExplicit(enabled: Boolean = true) =
        if (enabled) {
            copy(
                sections =
                    sections.map {
                        it.copy(items = it.items.filterExplicit())
                    },
            )
        } else {
            this
        }

    fun filterVideoSongs(disableVideos: Boolean = false) =
        if (disableVideos) {
            copy(
                sections =
                    sections.map { section ->
                        section.copy(items = section.items.filterVideoSongs(true))
                    },
            )
        } else {
            this
        }
}
