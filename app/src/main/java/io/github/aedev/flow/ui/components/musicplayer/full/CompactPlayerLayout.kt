package io.github.aedev.flow.ui.components.musicplayer.full

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The phone layout: one column, with the cover taking whatever height the controls leave it. As
 * the queue is pulled up the column fades and the cover shrinks, read per frame in the draw phase.
 */
@Composable
internal fun CompactPlayerLayout(
    slots: NowPlayingSlots,
    artworkSize: Dp,
    queueFraction: () -> Float,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    // Lyrics shown in place take the cover's whole space, so the cover and its skip swipes leave.
    lyrics: (@Composable (Modifier) -> Unit)? = null,
) {
    val sidePadding = Modifier.padding(horizontal = PlayerHorizontalPadding)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (1f - queueFraction() / 0.4f).coerceIn(0f, 1f) },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        ) {
            slots.topBar(Modifier.statusBarsPadding())
            if (lyrics != null) {
                lyrics(Modifier.fillMaxWidth().weight(1f))
            } else {
                Spacer(modifier = Modifier.weight(1f))
                slots.artwork(
                    sidePadding
                        .size(artworkSize)
                        .graphicsLayer {
                            val scale = 1f - queueFraction() * 0.10f
                            scaleX = scale
                            scaleY = scale
                        },
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        slots.header(sidePadding)
        Spacer(modifier = Modifier.height(24.dp))
        slots.progress(sidePadding)
        Spacer(modifier = Modifier.height(26.dp))
        slots.controls(sidePadding)
        Spacer(modifier = Modifier.height(18.dp))
        slots.actions(sidePadding)
        Spacer(modifier = Modifier.height(bottomInset + 16.dp))
    }
}
