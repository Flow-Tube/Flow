package io.github.aedev.flow.ui.screens.playon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.RemoteSlot
import io.github.aedev.flow.ui.components.shared.MediaMiniBarBounds
import io.github.aedev.flow.ui.components.shared.MediaMiniBarDefaults
import io.github.aedev.flow.ui.components.shared.MediaPlayPauseButton
import kotlin.math.roundToInt

/** What "Play on" puts in the shell's mini player slot now. */
@Composable
internal fun remoteSlot(): RemoteSlot {
    val playOn = LocalPlayOn.current ?: return RemoteSlot(session = false, offered = false)
    val elsewhere by playOn.elsewhere.collectAsStateWithLifecycle()
    val offered by playOn.playingElsewhere.collectAsStateWithLifecycle()
    return RemoteSlot(session = elsewhere?.now?.music == false, offered = offered != null)
}

/**
 * The mini player for another device, resting where the shell's mini bars rest. For the device the
 * user chose in "Play on" it has music's mini player buttons, driving that device, and a tap opens
 * "Play on". For a device playing on its own it offers to play it here or to close, and a tap
 * starts driving that device.
 */
@Composable
internal fun RemoteMiniBar(
    bounds: MediaMiniBarBounds,
    containerHeightPx: Float,
    restingBottomPx: () -> Float,
    dockShiftPx: () -> Float,
) {
    val playOn = LocalPlayOn.current ?: return
    val elsewhere by playOn.elsewhere.collectAsStateWithLifecycle()
    val offered by playOn.playingElsewhere.collectAsStateWithLifecycle()
    val driven = elsewhere
    val other = offered
    when {
        driven != null -> {
            RemoteBarFrame(bounds, containerHeightPx, restingBottomPx, dockShiftPx, onClick = { playOn.open.value = true }) {
                DrivenContent(playOn, driven)
            }
        }

        other != null -> {
            val label = stringResource(R.string.play_on_control_device, other.name)
            RemoteBarFrame(
                bounds,
                containerHeightPx,
                restingBottomPx,
                dockShiftPx,
                onClick = { playOn.drive(other) },
                onClickLabel = label,
            ) {
                OfferedContent(playOn, other)
            }
        }
    }
}

@Composable
private fun RemoteBarFrame(
    bounds: MediaMiniBarBounds,
    containerHeightPx: Float,
    restingBottomPx: () -> Float,
    dockShiftPx: () -> Float,
    onClick: () -> Unit,
    onClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val density = LocalDensity.current
    val heightPx = with(density) { MediaMiniBarDefaults.Height.toPx() }
    val spacerPx = with(density) { MediaMiniBarDefaults.BottomSpacer.toPx() }
    Surface(
        shape = RoundedCornerShape(MediaMiniBarDefaults.CornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            Modifier
                .offset {
                    IntOffset(
                        (bounds.start + dockShiftPx()).roundToInt(),
                        (containerHeightPx - restingBottomPx() - spacerPx - heightPx).roundToInt(),
                    )
                }.layout { measurable, _ ->
                    val width = (bounds.width - dockShiftPx()).roundToInt().coerceAtLeast(0)
                    val height = heightPx.roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(width, height) { placeable.place(0, 0) }
                }.clickable(onClickLabel = onClickLabel, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** Art, title and "Playing on <name>", taking the room the buttons leave. */
@Composable
private fun RowScope.SessionLines(
    remote: Elsewhere,
    lookUpArt: Boolean,
) {
    SessionArtwork(
        remote.now,
        MaterialTheme.shapes.medium,
        Modifier.padding(vertical = 10.dp).fillMaxHeight(),
        byHeight = true,
        lookUpArt = lookUpArt,
    )
    Column(Modifier.padding(horizontal = 12.dp).weight(1f), verticalArrangement = Arrangement.Center) {
        Text(remote.now.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(R.string.play_on_playing_on, remote.name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RowScope.DrivenContent(
    playOn: PlayOnViewModel,
    remote: Elsewhere,
) {
    val playing = remote.playing
    val busy by playOn.busy.collectAsStateWithLifecycle()
    val movingTo by playOn.movingTo.collectAsStateWithLifecycle()
    val transportTint = MaterialTheme.colorScheme.onSurfaceVariant
    SessionLines(remote, lookUpArt = true)
    IconButton(onClick = { playOn.controlElsewhere("previous") }) {
        Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.previous), tint = transportTint)
    }
    MediaPlayPauseButton(
        isPlaying = playing,
        isBuffering = busy && movingTo == null,
        onClick = { playOn.controlElsewhere(if (playing) "pause" else "play") },
    )
    IconButton(onClick = { playOn.controlElsewhere("next") }) {
        Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.next), tint = transportTint)
    }
}

/** "Play here" and close for a device playing on its own; album art only when already known, so it asks the music service nothing. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.OfferedContent(
    playOn: PlayOnViewModel,
    remote: Elsewhere,
) {
    val busy by playOn.busy.collectAsStateWithLifecycle()
    SessionLines(remote, lookUpArt = false)
    FilledTonalButton(
        onClick = { playOn.playHere(remote) },
        enabled = !busy,
        contentPadding = ButtonDefaults.ExtraSmallContentPadding,
        modifier = Modifier.heightIn(min = ButtonDefaults.ExtraSmallContainerHeight),
    ) {
        Text(stringResource(R.string.play_on_play_here), maxLines = 1)
    }
    IconButton(onClick = { playOn.closeHint(remote) }) {
        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
