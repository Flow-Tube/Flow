package io.github.aedev.flow.ui.screens.playon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.MediaMiniBarBounds
import io.github.aedev.flow.ui.components.shared.MediaMiniBarDefaults
import io.github.aedev.flow.ui.components.shared.MediaPlayPauseButton
import kotlin.math.roundToInt

/**
 * The mini player for the device the user chose in "Play on", with music's mini player buttons: what
 * plays, "Playing on <name>", and transport that drives that device. Tapping it opens "Play on". It
 * rests where the shell's mini bars rest.
 */
@Composable
internal fun RemoteMiniBar(
    bounds: MediaMiniBarBounds,
    containerHeightPx: Float,
    restingBottomPx: () -> Float,
) {
    val playOn = LocalPlayOn.current ?: return
    val elsewhere by playOn.elsewhere.collectAsStateWithLifecycle()
    val remote = elsewhere ?: return
    val playing = remote.playing
    val busy by playOn.busy.collectAsStateWithLifecycle()
    val movingTo by playOn.movingTo.collectAsStateWithLifecycle()
    val transportTint = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val heightPx = with(density) { MediaMiniBarDefaults.Height.toPx() }
    val spacerPx = with(density) { MediaMiniBarDefaults.BottomSpacer.toPx() }
    Surface(
        shape = RoundedCornerShape(MediaMiniBarDefaults.CornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            Modifier
                .offset { IntOffset(bounds.start.roundToInt(), (containerHeightPx - restingBottomPx() - spacerPx - heightPx).roundToInt()) }
                .size(with(density) { bounds.width.toDp() }, MediaMiniBarDefaults.Height)
                .clickable { playOn.open.value = true },
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SessionArtwork(remote.now, MaterialTheme.shapes.medium, Modifier.padding(vertical = 10.dp).fillMaxHeight(), byHeight = true)
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
    }
}
