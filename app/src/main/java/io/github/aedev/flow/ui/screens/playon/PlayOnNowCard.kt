package io.github.aedev.flow.ui.screens.playon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.SliderStyle
import io.github.aedev.flow.sync2.live.LiveMessage
import io.github.aedev.flow.ui.components.musicplayer.controls.ExpressivePlayerSlider
import io.github.aedev.flow.ui.components.musicplayer.controls.expressiveSliderSpec
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.MediaPlaybackControls
import io.github.aedev.flow.ui.components.shared.deviceKindIcon

private val CardPadding = 16.dp
private val ArtHeight = 72.dp
private val LabelHeight = 28.dp
private val LabelIconSize = 16.dp

/**
 * What plays on the active device: its art, title and artist, where it plays, the music player's
 * own transport and the device's volume. [buffering] spins play/pause while a command is on its way.
 */
@Composable
internal fun PlayOnNowCard(
    session: PlayOnSession,
    buffering: Boolean,
    onControl: (cmd: String, vol: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = session.now
    val playing = now.state == LiveMessage.PLAYING
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier,
    ) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CardPadding)) {
                SessionArtwork(now, MaterialTheme.shapes.medium, Modifier.height(ArtHeight), byHeight = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        now.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (now.by.isNotEmpty()) {
                        Text(
                            now.by,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DeviceLabel(session.device, Modifier.padding(top = 4.dp))
                }
            }
            MediaPlaybackControls(
                isPlaying = playing,
                isBuffering = buffering,
                onPreviousClick = { onControl("previous", null) },
                onPlayPauseToggle = { onControl(if (playing) "pause" else "play", null) },
                onNextClick = { onControl("next", null) },
            )
            VolumeBar(session.device.did, now.vol) { onControl("volume", it) }
        }
    }
}

/** Where the session plays, as a label rather than a button: nothing happens on a tap. */
@Composable
private fun DeviceLabel(
    device: PlayOnDevice,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.height(LabelHeight),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(deviceKindIcon(device.kind), contentDescription = null, modifier = Modifier.size(LabelIconSize))
            Text(
                deviceName(device),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The device's volume on the music player's thick slider; it follows the device unless being dragged. */
@Composable
private fun VolumeBar(
    did: String,
    volume: Int,
    onChange: (Int) -> Unit,
) {
    var dragging by remember(did) { mutableStateOf(false) }
    var dragged by remember(did) { mutableFloatStateOf(volume / 100f) }
    val slider = expressiveSliderSpec(SliderStyle.THICK)
    val description = stringResource(R.string.play_on_volume)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            Icons.AutoMirrored.Rounded.VolumeDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        ExpressivePlayerSlider(
            value = if (dragging) dragged else volume / 100f,
            onValueChange = {
                dragging = true
                dragged = it
            },
            onValueChangeFinished = {
                dragging = false
                onChange((dragged * 100).toInt())
            },
            valueRange = 0f..1f,
            trackHeight = slider.trackHeight,
            thumbHeight = slider.thumbHeight,
            thumbTrackGap = slider.thumbTrackGap,
            modifier = Modifier.weight(1f).semantics { contentDescription = description },
        )
        Icon(
            Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The card's place when nothing plays on any device. */
@Composable
internal fun PlayOnNothingCard(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier,
    ) {
        FlowEmptyState(
            title = stringResource(R.string.play_on_nothing),
            subtitle = stringResource(R.string.play_on_nothing_hint),
            icon = Icons.Outlined.SpeakerGroup,
        )
    }
}

/** "This phone" and the like for this device, its own name for another one. */
@Composable
internal fun deviceName(device: PlayOnDevice): String {
    if (!device.thisDevice) return device.name
    return stringResource(
        when (device.kind) {
            "tv" -> R.string.play_on_this_tv
            "tablet" -> R.string.play_on_this_tablet
            else -> R.string.play_on_this_phone
        },
    )
}
