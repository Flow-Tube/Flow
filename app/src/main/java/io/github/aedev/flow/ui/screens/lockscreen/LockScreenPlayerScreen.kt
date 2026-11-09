package io.github.aedev.flow.ui.screens.lockscreen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.MusicPlayerBackgroundStyle
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.RepeatMode
import io.github.aedev.flow.ui.components.musicplayer.common.rememberMusicPlayerAppearance
import io.github.aedev.flow.ui.components.musicplayer.controls.PlayerProgressSlider
import io.github.aedev.flow.ui.components.musicplayer.controls.PlayerSegmentInnerShape
import io.github.aedev.flow.ui.components.musicplayer.controls.PlayerSegmentLeadingShape
import io.github.aedev.flow.ui.components.musicplayer.controls.PlayerSegmentTrailingShape
import io.github.aedev.flow.ui.components.musicplayer.controls.PlayerToggleButton
import io.github.aedev.flow.ui.components.musicplayer.full.PlayerBackground
import io.github.aedev.flow.ui.components.shared.MediaPlaybackControls

private val UnlockDistance = 96.dp
private val ActionGroupHeight = 42.dp
private val ActionGroupSpacing = 4.dp

/**
 * The current song over the lock screen, drawn like the music player's immersive style: the cover fills
 * the top and fades into its own blurred colours, with the word-synced lyric line, the seek bar in the
 * user's chosen style, the controls and a like / shuffle / repeat group below. Everything else stays
 * behind the lock.
 */
@Composable
internal fun LockScreenPlayerScreen(
    onUnlock: () -> Unit,
    lyricsViewModel: LockScreenLyricsViewModel = hiltViewModel(),
) {
    val track by EnhancedMusicPlayerManager.currentTrack.collectAsStateWithLifecycle()
    val state by EnhancedMusicPlayerManager.playerState.collectAsStateWithLifecycle()
    val position by EnhancedMusicPlayerManager.currentPosition.collectAsStateWithLifecycle()
    val lyrics by lyricsViewModel.lyrics.collectAsStateWithLifecycle()
    val song = track ?: return
    PreciseProgressWhileShown()

    val appearance = rememberMusicPlayerAppearance(song.highResThumbnailUrl, MusicPlayerBackgroundStyle.IMMERSIVE)
    val unlockPx = with(LocalDensity.current) { UnlockDistance.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    MaterialTheme(colorScheme = appearance.playerScheme) {
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerBackground(
                thumbnailUrl = song.highResThumbnailUrl,
                style = MusicPlayerBackgroundStyle.IMMERSIVE,
                backdrop = appearance.backdrop,
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .draggable(
                            state = rememberDraggableState { delta -> dragged += delta },
                            orientation = Orientation.Vertical,
                            onDragStarted = { dragged = 0f },
                            onDragStopped = { if (dragged < -unlockPx) onUnlock() },
                        ).safeDrawingPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Spacer(Modifier.weight(1f))
                TrackText(song)
                lyrics?.takeIf { it.videoId == song.videoId && it.lines.isNotEmpty() }?.let { shown ->
                    LockScreenLyricLine(
                        lyrics = shown,
                        isPlaying = state.isPlaying,
                        positionKey = state.position / POSITION_KEY_MS,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                MaterialTheme(colorScheme = appearance.controlScheme) {
                    PlayerProgressSlider(
                        positionProvider = { position },
                        duration = state.duration,
                        onSeekTo = EnhancedMusicPlayerManager::seekTo,
                        isPlaying = state.isPlaying,
                    )
                    Spacer(Modifier.height(8.dp))
                    MediaPlaybackControls(
                        isPlaying = state.isPlaying,
                        isBuffering = state.isBuffering,
                        onPreviousClick = EnhancedMusicPlayerManager::playPrevious,
                        onPlayPauseToggle = EnhancedMusicPlayerManager::togglePlayPause,
                        onNextClick = EnhancedMusicPlayerManager::playNext,
                    )
                    Spacer(Modifier.height(16.dp))
                    LockScreenActionGroup()
                }
                Spacer(Modifier.height(16.dp))
                UnlockHint(onUnlock, Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun TrackText(song: MusicTrack) {
    Text(
        text = song.title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = song.artist,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Like, shuffle and repeat as one group, in the music player's action row style. */
@Composable
private fun LockScreenActionGroup() {
    val liked by EnhancedMusicPlayerManager.isLiked.collectAsStateWithLifecycle()
    val shuffle by EnhancedMusicPlayerManager.shuffleEnabled.collectAsStateWithLifecycle()
    val repeat by EnhancedMusicPlayerManager.repeatMode.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier.fillMaxWidth().height(ActionGroupHeight),
        horizontalArrangement = Arrangement.spacedBy(ActionGroupSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerToggleButton(
            checked = liked,
            checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = stringResource(if (liked) R.string.unlike else R.string.like),
            uncheckedShape = PlayerSegmentLeadingShape,
            onClick = EnhancedMusicPlayerManager::emitToggleLikeEvent,
        )
        PlayerToggleButton(
            checked = shuffle,
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            icon = Icons.Rounded.Shuffle,
            contentDescription = stringResource(R.string.shuffle),
            uncheckedShape = PlayerSegmentInnerShape,
            onClick = EnhancedMusicPlayerManager::toggleShuffle,
        )
        PlayerToggleButton(
            checked = repeat != RepeatMode.OFF,
            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            icon = if (repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            contentDescription = stringResource(R.string.repeat),
            uncheckedShape = PlayerSegmentTrailingShape,
            onClick = EnhancedMusicPlayerManager::toggleRepeat,
        )
    }
}

@Composable
private fun UnlockHint(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClick = onUnlock)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = stringResource(R.string.lock_screen_player_unlock),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The seek bar moves on the player's precise tick while the screen shows it, as it does in the music player. */
@Composable
private fun PreciseProgressWhileShown() {
    LifecycleStartEffect(Unit) {
        EnhancedMusicPlayerManager.acquirePreciseProgress()
        onStopOrDispose { EnhancedMusicPlayerManager.releasePreciseProgress() }
    }
}

/** The player's coarse position ticks once a second; a new second re-reads the lyric line, so a seek shows soon. */
private const val POSITION_KEY_MS = 1_000L
