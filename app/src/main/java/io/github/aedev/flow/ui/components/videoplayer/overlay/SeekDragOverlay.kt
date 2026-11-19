package io.github.aedev.flow.ui.components.videoplayer.overlay

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.stream.StoryboardLevel
import io.github.aedev.flow.player.stream.StoryboardSpec
import io.github.aedev.flow.ui.components.videoplayer.controls.SeekPreviewThumbnail
import io.github.aedev.flow.ui.components.videoplayer.controls.SeekPreviewWidth
import io.github.aedev.flow.ui.theme.PlayerScrim
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimPanel
import io.github.aedev.flow.utils.formatDurationMillis
import kotlin.math.abs

/**
 * Target-time preview shown while dragging horizontally to seek.
 *
 * The drag only commits on release, so this is the sole feedback the gesture gives: the frame it
 * will land on when the video has a storyboard, the absolute time, and how far that is from where
 * playback currently sits.
 */
@Composable
internal fun SeekDragOverlay(
    isVisible: Boolean,
    targetMs: () -> Long,
    deltaMs: () -> Long,
    modifier: Modifier = Modifier,
    storyboard: List<StoryboardLevel> = emptyList(),
) {
    val frameWidthPx = with(LocalDensity.current) { SeekPreviewWidth.roundToPx() }
    val level = remember(storyboard, frameWidthPx) { StoryboardSpec.levelFor(storyboard, frameWidthPx) }
    AnimatedVisibility(
        visible = isVisible,
        enter =
            fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                scaleIn(animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(), initialScale = 0.92f),
        exit =
            fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                scaleOut(MaterialTheme.motionScheme.fastSpatialSpec(), targetScale = 0.94f),
        modifier = modifier,
    ) {
        val target = targetMs()
        val delta = deltaMs()
        Column(
            modifier =
                Modifier
                    .clip(MaterialTheme.shapes.large)
                    .background(PlayerScrimPanel)
                    .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (level != null) {
                SeekPreviewThumbnail(level = level, positionProvider = targetMs)
                Spacer(modifier = Modifier.height(10.dp))
            }
            Text(
                text = formatDurationMillis(target, padMinutes = true),
                color = PlayerScrimContent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text =
                    stringResource(
                        if (delta < 0L) R.string.player_seek_delta_back else R.string.player_seek_delta_forward,
                        formatDurationMillis(abs(delta)),
                    ),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
