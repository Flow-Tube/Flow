package io.github.aedev.flow.ui.components.music.sheet

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.JoinInner
import androidx.compose.material.icons.outlined.QueuePlayNext
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.layout.navigation.LocalMediaNavigator
import io.github.aedev.flow.ui.components.shared.ArtworkThumbnail
import io.github.aedev.flow.ui.components.shared.ChannelAvatarStack
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.quickactions.MediaDetailsPage
import io.github.aedev.flow.ui.components.shared.quickactions.MediaDetailsSubject
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionRow
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsDefaults
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsGroup
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsHeader
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsPageHeader
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsPrimaryGroup
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsSheet
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsViewModel
import io.github.aedev.flow.ui.components.shared.quickactions.QuickPrimaryAction
import io.github.aedev.flow.ui.components.shared.quickactions.actionRow
import io.github.aedev.flow.ui.components.shared.quickactions.sharedQuickActionsViewModel
import io.github.aedev.flow.ui.screens.music.MusicPlayerViewModel
import io.github.aedev.flow.ui.screens.music.sharedMusicPlayerViewModel
import io.github.aedev.flow.utils.YouTubeLink
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private enum class SongMenuPage { Actions, Artists, Details, Save }

/** The player's "Pause crossfade" row; [paused] while crossfade is off until the app restarts. */
@Immutable
class CrossfadePauseRow(
    val paused: Boolean,
    val onToggle: () -> Unit,
)

/**
 * A song's menu. [onAudioEffectsClick], [onSleepTimerClick] and [crossfadePause] are the player's own
 * settings, so their rows appear only when the player passes them.
 */
@Composable
fun MusicQuickActionsSheet(
    track: MusicTrack,
    onDismiss: () -> Unit,
    onAudioEffectsClick: (() -> Unit)? = null,
    onSleepTimerClick: (() -> Unit)? = null,
    crossfadePause: CrossfadePauseRow? = null,
    viewModel: MusicPlayerViewModel = sharedMusicPlayerViewModel(),
    quickActions: QuickActionsViewModel = sharedQuickActionsViewModel(),
) {
    var page by rememberSaveable(track.videoId) { mutableStateOf(SongMenuPage.Actions) }
    val toActions = { page = SongMenuPage.Actions }
    val artists by produceState(track.menuArtists(), track) { value = viewModel.resolveArtists(track) }
    val avatars by produceState(emptyMap<String, String>(), artists) {
        value = quickActions.channelAvatars(artists.mapNotNull { it.id })
    }

    if (page == SongMenuPage.Save) {
        SaveSongSheet(track = track, onDismiss = onDismiss)
        return
    }

    QuickActionsSheet(
        onDismiss = onDismiss,
        page = page,
        onBack = if (page == SongMenuPage.Actions) null else toActions,
    ) { sheet ->
        val close = sheet::close
        when (page) {
            SongMenuPage.Details -> {
                QuickActionsPageHeader(title = stringResource(R.string.details_metadata), onBack = toActions, onClose = close)
                MediaDetailsPage(
                    subject =
                        MediaDetailsSubject(
                            videoId = track.videoId,
                            title = track.title,
                            author = track.artist,
                            album = track.album,
                            channelId = track.channelId,
                            viewCount = track.views,
                            durationSeconds = track.duration,
                        ),
                )
            }

            SongMenuPage.Artists -> {
                MusicArtistsPage(artists = artists, avatars = avatars, onBack = toActions, onClose = close)
            }

            else -> {
                QuickActionsHeader(title = track.title, subtitle = track.artist) {
                    ArtworkThumbnail(
                        thumbnailUrl = track.listThumbnailUrl,
                        size = QuickActionsDefaults.SquareArtworkSize,
                        placeholder = Icons.Default.MusicNote,
                    )
                }
                val isDeviceFile = LocalMediaIds.isLocal(track.videoId)
                if (!isDeviceFile) {
                    SongPrimaryActions(track, viewModel, onSave = { sheet.hideThen { page = SongMenuPage.Save } }, onDismiss = close)
                }
                QuickActionsGroup(
                    title = stringResource(R.string.playback_header),
                    rows = playbackRows(track, viewModel, quickActions, onAudioEffectsClick, onSleepTimerClick, crossfadePause, close),
                )
                // A file on the device has no artist page, feed or online details to offer.
                if (isDeviceFile) return@QuickActionsSheet
                QuickActionsGroup(
                    title = stringResource(R.string.quick_action_go_to),
                    rows =
                        goToRows(
                            track = track,
                            artists = artists,
                            avatars = artists.mapNotNull { avatars[it.id] },
                            onOpenArtists = { page = SongMenuPage.Artists },
                            onDismiss = close,
                        ),
                )
                QuickActionsGroup(
                    title = stringResource(R.string.recommendations_header),
                    rows = feedbackRows(track, viewModel, quickActions, close),
                )
                QuickActionsGroup(
                    title = stringResource(R.string.more_header),
                    rows =
                        listOf(
                            QuickActionRow("details") { shape ->
                                FlowNavRow(
                                    title = stringResource(R.string.details_metadata),
                                    leadingIcon = Icons.Outlined.Info,
                                    onClick = { page = SongMenuPage.Details },
                                    shape = shape,
                                )
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun SongPrimaryActions(
    track: MusicTrack,
    viewModel: MusicPlayerViewModel,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val downloadedIds by remember(viewModel) {
        viewModel.uiState.map { it.downloadedTrackIds }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(emptySet())
    val isDownloaded = track.videoId in downloadedIds
    val shareSong = rememberSongShareAction()
    QuickActionsPrimaryGroup(
        listOf(
            QuickPrimaryAction(
                icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                label = stringResource(R.string.add_to_playlist),
                onClick = onSave,
            ),
            QuickPrimaryAction(
                icon = Icons.Outlined.Download,
                checkedIcon = Icons.Filled.DownloadDone,
                label = stringResource(if (isDownloaded) R.string.downloaded else R.string.download),
                checked = isDownloaded,
                onClick = {
                    if (!isDownloaded) {
                        viewModel.downloadTrack(track)
                        onDismiss()
                    }
                },
            ),
            QuickPrimaryAction(
                icon = Icons.Outlined.Share,
                label = stringResource(R.string.share),
                onClick = {
                    shareSong(track)
                    onDismiss()
                },
            ),
        ),
    )
}

@Composable
private fun playbackRows(
    track: MusicTrack,
    viewModel: MusicPlayerViewModel,
    quickActions: QuickActionsViewModel,
    onAudioEffectsClick: (() -> Unit)?,
    onSleepTimerClick: (() -> Unit)?,
    crossfadePause: CrossfadePauseRow?,
    onDismiss: () -> Unit,
): List<QuickActionRow> {
    val navigator = LocalMediaNavigator.current
    return buildList {
        if (viewModel.canStartRadio(track)) {
            add(
                actionRow("radio", Icons.Outlined.Radio, stringResource(R.string.start_radio), stringResource(R.string.start_radio_desc)) {
                    viewModel.startRadio(track)
                    onDismiss()
                },
            )
        }
        add(
            actionRow(
                "play_next",
                Icons.Outlined.QueuePlayNext,
                stringResource(R.string.play_next),
                stringResource(R.string.play_next_desc),
            ) {
                viewModel.playNext(track)
                onDismiss()
            },
        )
        add(
            actionRow(
                "add_to_queue",
                Icons.AutoMirrored.Outlined.PlaylistPlay,
                stringResource(R.string.add_to_queue),
                stringResource(R.string.add_to_queue_desc),
            ) {
                viewModel.addToQueue(track)
                onDismiss()
            },
        )
        if (!LocalMediaIds.isLocal(track.videoId)) {
            add(
                actionRow(
                    "watch_video",
                    Icons.Outlined.SmartDisplay,
                    stringResource(R.string.music_watch_video),
                    stringResource(R.string.music_watch_video_desc),
                ) {
                    quickActions.watchVideo(track) { navigator.openLink(YouTubeLink.Video(it, isMusic = false)) }
                    onDismiss()
                },
            )
        }
        onAudioEffectsClick?.let { open ->
            add(
                actionRow("audio_effects", Icons.Outlined.GraphicEq, stringResource(R.string.audio_effects)) {
                    open()
                    onDismiss()
                },
            )
        }
        onSleepTimerClick?.let { open ->
            add(
                actionRow("sleep_timer", Icons.Outlined.Bedtime, stringResource(R.string.sleep_timer)) {
                    open()
                    onDismiss()
                },
            )
        }
        crossfadePause?.let { row ->
            add(
                if (row.paused) {
                    actionRow("crossfade_pause", Icons.Outlined.JoinInner, stringResource(R.string.crossfade_resume)) {
                        row.onToggle()
                        onDismiss()
                    }
                } else {
                    actionRow(
                        "crossfade_pause",
                        Icons.Outlined.JoinInner,
                        stringResource(R.string.crossfade_pause),
                        stringResource(R.string.crossfade_pause_desc),
                    ) {
                        row.onToggle()
                        onDismiss()
                    }
                },
            )
        }
    }
}

/** The artist, avatars stacked like a collaboration card when there are several, then the album. */
@Composable
private fun goToRows(
    track: MusicTrack,
    artists: List<MusicArtist>,
    avatars: List<String>,
    onOpenArtists: () -> Unit,
    onDismiss: () -> Unit,
): List<QuickActionRow> {
    val navigator = LocalMediaNavigator.current
    val ringColor = MaterialTheme.colorScheme.surfaceContainerHigh
    return buildList {
        if (artists.isNotEmpty()) {
            val several = artists.size > 1
            add(
                QuickActionRow("artist") { shape ->
                    FlowNavRow(
                        title = if (several) artists.joinToString { it.name } else artists.first().name.ifBlank { track.artist },
                        onClick = {
                            if (several) {
                                onOpenArtists()
                            } else {
                                navigator.openArtist(artists.first().id.orEmpty())
                                onDismiss()
                            }
                        },
                        shape = shape,
                        showChevron = several,
                        leadingContent = {
                            ChannelAvatarStack(
                                urls = avatars,
                                contentDescription = null,
                                avatarSize = QuickActionsDefaults.AvatarSize,
                                ringColor = ringColor,
                            )
                        },
                    )
                },
            )
        }
        val albumId = track.albumId
        if (!albumId.isNullOrBlank()) {
            add(
                actionRow("album", Icons.Outlined.Album, track.album.ifBlank { stringResource(R.string.view_album) }) {
                    navigator.openAlbum(albumId)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun feedbackRows(
    track: MusicTrack,
    viewModel: MusicPlayerViewModel,
    quickActions: QuickActionsViewModel,
    onDismiss: () -> Unit,
): List<QuickActionRow> {
    val artistName = track.artists.firstOrNull()?.name ?: track.artist
    return listOf(
        actionRow(
            "not_interested",
            Icons.Outlined.ThumbDown,
            stringResource(R.string.not_interested),
            stringResource(R.string.not_interested_desc),
        ) {
            viewModel.notInterested(track)
            quickActions.announce(R.string.feedback_not_interested_applied, artistName)
            onDismiss()
        },
        actionRow(
            key = "dont_recommend",
            icon = Icons.Outlined.Block,
            title = stringResource(R.string.dont_recommend_artist, artistName),
            supporting = stringResource(R.string.dont_recommend_artist_desc),
            destructive = true,
        ) {
            viewModel.dontRecommendArtist(track)
            quickActions.announce(R.string.feedback_artist_blocked, artistName)
            onDismiss()
        },
    )
}
