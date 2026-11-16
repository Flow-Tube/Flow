package io.github.aedev.flow.ui.components.videoplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.MiniBarSwipeAction
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.ui.components.shared.MediaPlayPauseButton
import io.github.aedev.flow.ui.components.shared.MediaSeekBar
import io.github.aedev.flow.utils.formatDurationMillis
import kotlinx.coroutines.delay

private const val PROGRESS_INTERVAL_MS = 1_000L

@Immutable
internal data class PolledPlayback(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    val fraction: Float get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** The background video's position, read once a second while it plays and the screen is started. */
@Composable
internal fun rememberPolledPlayback(isPlaying: Boolean): State<PolledPlayback> {
    val playback = remember { mutableStateOf(PolledPlayback()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(isPlaying, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                val manager = EnhancedPlayerManager.getInstance()
                playback.value = PolledPlayback(manager.getCurrentPosition(), manager.getDuration())
                if (isPlaying) delay(PROGRESS_INTERVAL_MS)
            } while (isPlaying)
        }
    }
    return playback
}

/** Seek bar and previous, play and next under the queue opened from the background bar. */
@Composable
internal fun BackgroundQueueTransport(
    actions: VideoBarSwipeActions,
    modifier: Modifier = Modifier,
) {
    val manager = EnhancedPlayerManager.getInstance()
    val playerState by manager.playerState.collectAsStateWithLifecycle()
    val isPlaying = playerState.playWhenReady
    val playback by rememberPolledPlayback(isPlaying)
    var scrubFraction by remember { mutableStateOf<Float?>(null) }

    // Transport follows the timeline, as in the main player: time and skips run left to right.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            MediaSeekBar(
                value = { scrubFraction ?: playback.fraction },
                onValueChange = { scrubFraction = it },
                onValueChangeFinished = {
                    scrubFraction?.let { manager.seekTo((it * playback.durationMs).toLong()) }
                    scrubFraction = null
                },
                enabled = playback.durationMs > 0L,
                duration = playback.durationMs,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = formatDurationMillis(playback.positionMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { actions.commit(MiniBarSwipeAction.PREVIOUS)?.perform?.invoke() },
                    enabled = actions.isAvailable(MiniBarSwipeAction.PREVIOUS),
                ) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.previous))
                }
                MediaPlayPauseButton(
                    isPlaying = isPlaying,
                    isBuffering = playerState.isBuffering,
                    onClick = { if (isPlaying) manager.pause() else manager.play() },
                )
                IconButton(
                    onClick = { actions.commit(MiniBarSwipeAction.NEXT)?.perform?.invoke() },
                    enabled = actions.isAvailable(MiniBarSwipeAction.NEXT),
                ) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.next))
                }
                Text(
                    text = formatDurationMillis(playback.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
