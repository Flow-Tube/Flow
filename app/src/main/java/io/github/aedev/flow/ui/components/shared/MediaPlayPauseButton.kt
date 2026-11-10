package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R

private val PlayButtonSize = 48.dp
private val PlayButtonPressedWidth = 60.dp
private val PlayButtonPlayingCorner = 14.dp

/**
 * A mini bar's play or pause with the full music player's motion: the corners ease between a
 * rounded square while playing and a circle while paused, and a press stretches the button on the
 * same elastic spring.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaPlayPauseButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val width by animateDpAsState(
        targetValue = if (pressed) PlayButtonPressedWidth else PlayButtonSize,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 720f),
        label = "miniPlayWidth",
    )
    val corner by animateDpAsState(
        targetValue = if (isPlaying) PlayButtonPlayingCorner else PlayButtonSize / 2,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "miniPlayCorner",
    )
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(width = width, height = PlayButtonSize),
        shape = RoundedCornerShape(corner),
        interactionSource = interactionSource,
    ) {
        if (isBuffering) {
            FlowSpinner(
                modifier = Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
            )
        }
    }
}
