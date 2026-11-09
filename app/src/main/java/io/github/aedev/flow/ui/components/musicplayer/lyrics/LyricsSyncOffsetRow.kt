package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aedev.flow.R
import java.util.Locale

@Composable
internal fun LyricsSyncOffsetRow(
    offsetMs: Long,
    onAdjust: (Long) -> Unit,
    onReset: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val offsetActive = offsetMs != 0L
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // M3E connected button group: 2dp seams, round outer ends, the pressed
        // segment widens with a spring while its inner corners relax — the same
        // press-morph idiom as the player's main transport buttons.
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OffsetStepSegment(deltaMs = -500L, edge = OffsetSegmentEdge.START, onAdjust = onAdjust)
            OffsetStepSegment(deltaMs = -100L, edge = OffsetSegmentEdge.INNER, onAdjust = onAdjust)

            val chipWeight by animateFloatAsState(
                targetValue = if (offsetActive) 1.7f else 1.4f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "syncChipWeight",
            )
            val chipContainer by animateColorAsState(
                targetValue =
                    if (offsetActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                label = "syncChipContainer",
            )
            val chipContent by animateColorAsState(
                targetValue =
                    if (offsetActive) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                label = "syncChipContent",
            )
            Box(
                modifier =
                    Modifier
                        .weight(chipWeight)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .background(chipContainer)
                        .clickable(enabled = offsetActive) {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onReset()
                        },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(
                        targetState = offsetMs,
                        transitionSpec = {
                            // The value rolls like a counter: up when the offset grows.
                            if (targetState > initialState) {
                                (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                            } else {
                                (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
                            }
                        },
                        label = "syncOffsetValue",
                    ) { value ->
                        val valueText = if (value != 0L) String.format(Locale.US, "%+.1f", value / 1000f) else "0"
                        Text(
                            text = stringResource(R.string.lyrics_sync_offset_value, valueText),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = chipContent,
                            maxLines = 1,
                        )
                    }
                    AnimatedVisibility(
                        visible = offsetActive,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.lyrics_sync_reset),
                            modifier = Modifier.size(14.dp),
                            tint = chipContent,
                        )
                    }
                }
            }

            OffsetStepSegment(deltaMs = 100L, edge = OffsetSegmentEdge.INNER, onAdjust = onAdjust)
            OffsetStepSegment(deltaMs = 500L, edge = OffsetSegmentEdge.END, onAdjust = onAdjust)
        }

        IconButton(
            onClick = onHide,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.close),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class OffsetSegmentEdge { START, INNER, END }

@Composable
private fun RowScope.OffsetStepSegment(
    deltaMs: Long,
    edge: OffsetSegmentEdge,
    onAdjust: (Long) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val weight by animateFloatAsState(
        targetValue = if (pressed) 1.35f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "syncStepWeight",
    )
    val innerRadius by animateDpAsState(
        targetValue = if (pressed) 18.dp else 10.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "syncStepCorner",
    )
    val shape =
        when (edge) {
            OffsetSegmentEdge.START -> {
                RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp, topEnd = innerRadius, bottomEnd = innerRadius)
            }

            OffsetSegmentEdge.END -> {
                RoundedCornerShape(topStart = innerRadius, bottomStart = innerRadius, topEnd = 26.dp, bottomEnd = 26.dp)
            }

            OffsetSegmentEdge.INNER -> {
                RoundedCornerShape(innerRadius)
            }
        }
    Box(
        modifier =
            Modifier
                .weight(weight)
                .fillMaxHeight()
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(interactionSource = interactionSource, indication = ripple()) {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onAdjust(deltaMs)
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = String.format(Locale.US, "%+.1f", deltaMs / 1000f),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
        )
    }
}
