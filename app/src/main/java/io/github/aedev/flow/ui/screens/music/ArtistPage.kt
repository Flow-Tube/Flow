package io.github.aedev.flow.ui.screens.music

import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.ArtistDetails
import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.music.MusicArtistInsights
import io.github.aedev.flow.ui.LocalStatusBarHero
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.music.common.rememberMusicCollectionColorScheme
import io.github.aedev.flow.ui.components.music.detail.ArtistBio
import io.github.aedev.flow.ui.components.music.detail.ArtistHero
import io.github.aedev.flow.ui.components.music.detail.ArtistQueue
import io.github.aedev.flow.ui.components.music.detail.MusicHeroTopBar
import io.github.aedev.flow.ui.components.music.detail.artistHeroPhotoHeight
import io.github.aedev.flow.ui.components.music.header.MusicSectionAction
import io.github.aedev.flow.ui.components.music.header.MusicSectionHeader
import io.github.aedev.flow.ui.components.music.item.MusicCollectionCard
import io.github.aedev.flow.ui.components.music.section.MusicArtistShelf
import io.github.aedev.flow.ui.components.music.section.MusicCollectionShelf
import io.github.aedev.flow.ui.components.music.section.MusicShelf
import io.github.aedev.flow.ui.components.music.sheet.LocalMusicMenus
import io.github.aedev.flow.ui.components.music.sheet.toCollectionActionItem
import io.github.aedev.flow.ui.components.shared.dpadDownTo
import io.github.aedev.flow.ui.utils.reachUnderStatusBar
import kotlinx.coroutines.launch

private const val TOP_TRACKS_SHOWN = 5
private val VideoCardHeight = 124.dp
private const val VIDEO_ASPECT_RATIO = 16f / 9f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtistPage(
    artistDetails: ArtistDetails,
    onBackClick: () -> Unit,
    onTrackClick: (MusicTrack, List<MusicTrack>) -> Unit,
    onAlbumClick: (MusicPlaylist) -> Unit,
    onArtistClick: (String) -> Unit,
    onFollowClick: () -> Unit,
    modifier: Modifier = Modifier,
    downloadedTrackIds: Set<String> = emptySet(),
    insights: MusicArtistInsights? = null,
    knownRelatedArtistIds: Set<String> = emptySet(),
    onSeeAllClick: (String, String?) -> Unit = { _, _ -> },
    onPlayWatchPlaylist: suspend (playlistId: String, params: String?) -> Boolean = { _, _ -> false },
    onStartRadio: (MusicTrack) -> Unit = {},
    aiNotice: ArtistAiNotice? = null,
    onShowAiArtist: () -> Unit = {},
) {
    val scrollState = rememberLazyListState()
    val contentFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var startingQueue by remember { mutableStateOf<ArtistQueue?>(null) }

    // The artist's own shuffle and mix are fetched first; a missing or empty one falls back to
    // what the page already holds.
    fun playWatchPlaylist(
        queue: ArtistQueue,
        playlistId: String?,
        params: String?,
        fallback: () -> Unit,
    ) {
        if (startingQueue != null) return
        if (playlistId == null) return fallback()
        scope.launch {
            startingQueue = queue
            val played =
                try {
                    onPlayWatchPlaylist(playlistId, params)
                } finally {
                    startingQueue = null
                }
            if (!played) fallback()
        }
    }

    // The bar turns solid once the photo has slid up behind it, before the name and buttons reach it.
    val density = LocalDensity.current
    val solidBarAfterPx =
        with(density) {
            (
                artistHeroPhotoHeight() - WindowInsets.statusBars.asPaddingValues().calculateTopPadding() -
                    TopAppBarDefaults.TopAppBarExpandedHeight
            ).roundToPx()
        }
    val showBarTitle by remember(solidBarAfterPx) {
        derivedStateOf {
            scrollState.firstVisibleItemIndex > 0 || scrollState.firstVisibleItemScrollOffset > solidBarAfterPx
        }
    }

    var descriptionExpanded by remember { mutableStateOf(false) }
    val musicMenus = LocalMusicMenus.current

    val pageScheme = rememberMusicCollectionColorScheme(artistDetails.thumbnailUrl.ifEmpty { artistDetails.bannerUrl })

    val statusBarHero = LocalStatusBarHero.current
    val heroOwner = remember { Any() }
    DisposableEffect(statusBarHero, showBarTitle) {
        if (!showBarTitle) statusBarHero?.claim(heroOwner)
        onDispose { statusBarHero?.release(heroOwner) }
    }
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)

    MaterialTheme(colorScheme = pageScheme) {
        Scaffold(
            modifier =
                modifier
                    .fillMaxSize()
                    .reachUnderStatusBar(statusBarTopPx),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                MusicHeroTopBar(
                    title = artistDetails.name,
                    showTitle = showBarTitle,
                    onBack = onBackClick,
                    contentFocus = contentFocus,
                ) { iconColors ->
                    IconButton(
                        modifier = Modifier.dpadDownTo(contentFocus),
                        onClick = {
                            clipboardManager.setText(AnnotatedString("https://music.youtube.com/channel/${artistDetails.channelId}"))
                            Toast.makeText(context, context.getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
                        },
                        colors = iconColors,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Link,
                            contentDescription = stringResource(R.string.share_link_cd),
                        )
                    }
                }
            },
        ) { _ ->
            LazyColumn(
                state = scrollState,
                contentPadding = PaddingValues(bottom = flowBottomContentPadding(32.dp)),
                modifier = Modifier.fillMaxSize().focusRequester(contentFocus),
            ) {
                item(key = "hero") {
                    val topTracks = artistDetails.topTracks
                    ArtistHero(
                        artist = artistDetails,
                        startingQueue = startingQueue,
                        onFollowClick = onFollowClick,
                        onPlayClick = {
                            if (topTracks.isNotEmpty()) onTrackClick(topTracks.first(), topTracks)
                        },
                        onShuffleClick = {
                            playWatchPlaylist(ArtistQueue.SHUFFLE, artistDetails.shufflePlaylistId, artistDetails.shuffleParams) {
                                if (topTracks.isNotEmpty()) onTrackClick(topTracks.random(), topTracks.shuffled())
                            }
                        },
                        onRadioClick =
                            artistDetails.radioPlaylistId?.let { radioId ->
                                {
                                    playWatchPlaylist(ArtistQueue.RADIO, radioId, artistDetails.radioParams) {
                                        topTracks.firstOrNull()?.let(onStartRadio)
                                    }
                                }
                            },
                    )
                }

                artistAiNotice(aiNotice, onShowAiArtist)

                if (artistDetails.description.isNotEmpty()) {
                    item(key = "bio") {
                        ArtistBio(
                            description = artistDetails.description,
                            links = artistDetails.descriptionLinks,
                            isExpanded = descriptionExpanded,
                            onToggleExpanded = { descriptionExpanded = !descriptionExpanded },
                        )
                    }
                }

                if (artistDetails.topTracks.isNotEmpty()) {
                    item(key = "top_songs_header") {
                        val browseId = artistDetails.topTracksBrowseId
                        MusicSectionHeader(
                            title = stringResource(R.string.filter_popular),
                            action =
                                if (browseId != null) {
                                    MusicSectionAction.SeeAll { onSeeAllClick(browseId, artistDetails.topTracksParams) }
                                } else {
                                    null
                                },
                        )
                    }
                    segmentedTracks(
                        id = "top_songs",
                        tracks = artistDetails.topTracks.take(TOP_TRACKS_SHOWN),
                        queue = artistDetails.topTracks,
                        downloadedTrackIds = downloadedTrackIds,
                        onTrackClick = onTrackClick,
                        onTrackMenu = musicMenus::openSong,
                    )
                }

                if (insights != null && insights.topTracks.isNotEmpty()) {
                    item(key = "history_header") {
                        MusicSectionHeader(
                            title = stringResource(R.string.section_your_history),
                            subtitle = insights.summaryLine(),
                        )
                    }
                    segmentedTracks(
                        id = "history",
                        tracks = insights.topTracks.take(TOP_TRACKS_SHOWN),
                        queue = insights.topTracks,
                        downloadedTrackIds = downloadedTrackIds,
                        onTrackClick = onTrackClick,
                        onTrackMenu = musicMenus::openSong,
                    )
                }

                if (artistDetails.singles.isNotEmpty()) {
                    item(key = "singles") {
                        MusicCollectionShelf(
                            title = stringResource(R.string.section_singles),
                            collections = artistDetails.singles,
                            keyNamespace = "singles",
                            onCollectionClick = onAlbumClick,
                            onCollectionMenu = { musicMenus.openCollection(it.toCollectionActionItem(isAlbum = true)) },
                            action = seeAllAction(artistDetails.singlesBrowseId, artistDetails.singlesParams, onSeeAllClick),
                            collectionSubtitle = { it.collectionSubtitle(showAuthor = false) },
                        )
                    }
                }

                if (artistDetails.albums.isNotEmpty()) {
                    item(key = "albums") {
                        MusicCollectionShelf(
                            title = stringResource(R.string.filter_albums),
                            collections = artistDetails.albums,
                            keyNamespace = "albums",
                            onCollectionClick = onAlbumClick,
                            onCollectionMenu = { musicMenus.openCollection(it.toCollectionActionItem(isAlbum = true)) },
                            action = seeAllAction(artistDetails.albumsBrowseId, artistDetails.albumsParams, onSeeAllClick),
                            collectionSubtitle = { it.collectionSubtitle(showAuthor = false) },
                        )
                    }
                }

                if (artistDetails.videos.isNotEmpty()) {
                    item(key = "videos") {
                        MusicShelf(
                            title = stringResource(R.string.tab_videos),
                            items = artistDetails.videos,
                            key = { "videos:${it.videoId}" },
                            action = MusicSectionAction.PlayAll { onTrackClick(artistDetails.videos.first(), artistDetails.videos) },
                        ) { video ->
                            MusicCollectionCard(
                                title = video.title,
                                subtitle = video.videoSubtitle(),
                                thumbnailUrl = video.thumbnailUrl,
                                thumbnailHeight = VideoCardHeight,
                                aspectRatio = VIDEO_ASPECT_RATIO,
                                mediaId = video.videoId,
                                onClick = { onTrackClick(video, listOf(video)) },
                            )
                        }
                    }
                }

                if (artistDetails.featuredOn.isNotEmpty()) {
                    item(key = "featured_on") {
                        MusicCollectionShelf(
                            title = stringResource(R.string.section_featured_on),
                            collections = artistDetails.featuredOn,
                            keyNamespace = "featured_on",
                            onCollectionClick = onAlbumClick,
                            onCollectionMenu = { musicMenus.openCollection(it.toCollectionActionItem(isAlbum = false)) },
                            collectionSubtitle = { it.collectionSubtitle(showAuthor = true) },
                        )
                    }
                }

                if (artistDetails.relatedArtists.isNotEmpty()) {
                    item(key = "related_artists") {
                        MusicArtistShelf(
                            title = stringResource(R.string.section_fans_also_like),
                            artists = artistDetails.relatedArtists,
                            key = { "related:${it.channelId}" },
                            name = { it.name },
                            thumbnailUrl = { it.thumbnailUrl },
                            subtitle = { artist ->
                                stringResource(R.string.artist_known_related_badge)
                                    .takeIf { artist.channelId in knownRelatedArtistIds }
                            },
                            onArtistClick = { onArtistClick(it.channelId) },
                        )
                    }
                }
            }
        }
    }
}
