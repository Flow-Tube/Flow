package io.github.aedev.flow.ui.screens.lockscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.ui.components.shared.MediaPlaybackControls
import io.github.aedev.flow.utils.formatDurationMillis
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val UnlockDistance = 96.dp

/** The current song, its controls and a clock; everything else on the phone stays behind the lock. */
@Composable
internal fun LockScreenPlayerScreen(onUnlock: () -> Unit) {
    val track by EnhancedMusicPlayerManager.currentTrack.collectAsStateWithLifecycle()
    val state by EnhancedMusicPlayerManager.playerState.collectAsStateWithLifecycle()
    val liked by EnhancedMusicPlayerManager.isLiked.collectAsStateWithLifecycle()
    val song = track ?: return

    val unlockPx = with(LocalDensity.current) { UnlockDistance.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .draggable(
                    state = rememberDraggableState { delta -> dragged += delta },
                    orientation = Orientation.Vertical,
                    onDragStarted = { dragged = 0f },
                    onDragStopped = { if (dragged < -unlockPx) onUnlock() },
                ).safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LockScreenClock()
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = song.highResThumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .aspectRatio(1f, matchHeightConstraintsFirst = true)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleLarge,
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
                )
            }
            IconToggleButton(checked = liked, onCheckedChange = { EnhancedMusicPlayerManager.emitToggleLikeEvent() }) {
                Icon(
                    imageVector = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = stringResource(if (liked) R.string.unlike else R.string.like),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatDurationMillis(state.position),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatDurationMillis(state.duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        MediaPlaybackControls(
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            onPreviousClick = EnhancedMusicPlayerManager::playPrevious,
            onPlayPauseToggle = EnhancedMusicPlayerManager::togglePlayPause,
            onNextClick = EnhancedMusicPlayerManager::playNext,
        )
        Spacer(Modifier.height(24.dp))
        Column(
            modifier =
                Modifier
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
}

/** Moves once a minute on the system's time tick, and only while the screen shows it. */
@Composable
private fun LockScreenClock() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalTime.now()) }
    LifecycleStartEffect(Unit) {
        now = LocalTime.now()
        val ticks =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    now = LocalTime.now()
                }
            }
        val filter =
            IntentFilter(Intent.ACTION_TIME_TICK).apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
        ContextCompat.registerReceiver(context, ticks, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onStopOrDispose { context.unregisterReceiver(ticks) }
    }
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(context)) "Hm" else "hm")
    Text(
        text = now.format(DateTimeFormatter.ofPattern(pattern)),
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
