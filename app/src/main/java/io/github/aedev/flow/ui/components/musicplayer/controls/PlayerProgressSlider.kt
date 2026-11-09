package io.github.aedev.flow.ui.components.musicplayer.controls

import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SliderStyle
import io.github.aedev.flow.ui.components.musicplayer.common.formatTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerProgressSlider(
    positionProvider: () -> Long,
    duration: Long,
    onSeekTo: (Long) -> Unit,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val currentPosition = positionProvider()
    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreviewPosition by remember { mutableFloatStateOf(currentPosition.toFloat()) }
    val seekPreviewScope = rememberCoroutineScope()
    var clearSeekPreviewJob by remember { mutableStateOf<Job?>(null) }
    val sliderEnd = duration.toFloat().coerceAtPositive(1f)
    val isInteracting = isDragged || isPressed || isSeeking
    val displayedPosition =
        if (isInteracting) {
            seekPreviewPosition.coerceIn(0f, sliderEnd)
        } else {
            currentPosition.toFloat().coerceIn(0f, sliderEnd)
        }
    val displayedPositionMs = displayedPosition.toLong()

    LaunchedEffect(currentPosition, sliderEnd, isInteracting) {
        if (!isInteracting) {
            seekPreviewPosition = currentPosition.toFloat().coerceIn(0f, sliderEnd)
        }
    }

    val animatedTrackHeight by animateDpAsState(
        targetValue = if (isInteracting) 22.dp else 16.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "trackHeight",
    )

    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { PlayerPreferences(context) }
    val sliderStyle by preferences.sliderStyle.collectAsState(initial = SliderStyle.COMPACT)

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val haptic = LocalHapticFeedback.current

            fun handleSeekPreview(value: Float) {
                clearSeekPreviewJob?.cancel()
                seekPreviewPosition = value.coerceIn(0f, sliderEnd)
                isSeeking = true
                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }

            fun commitSeekPreview() {
                if (isSeeking) {
                    onSeekTo(seekPreviewPosition.toLong())
                }
                clearSeekPreviewJob?.cancel()
                clearSeekPreviewJob =
                    seekPreviewScope.launch {
                        delay(200)
                        isSeeking = false
                    }
            }

            when (sliderStyle) {
                SliderStyle.SQUIGGLY -> {
                    SquigglySlider(
                        value = displayedPosition,
                        onValueChange = { handleSeekPreview(it) },
                        onValueChangeFinished = { commitSeekPreview() },
                        valueRange = 0f..sliderEnd,
                        isPlaying = isPlaying,
                        interactionSource = interactionSource,
                    )
                }

                SliderStyle.EXPRESSIVE_WAVY -> {
                    ExpressiveWavySlider(
                        value = displayedPosition,
                        onValueChange = { handleSeekPreview(it) },
                        onValueChangeFinished = { commitSeekPreview() },
                        valueRange = 0f..sliderEnd,
                        isPlaying = isPlaying,
                    )
                }

                else -> {
                    val spec = expressiveSliderSpec(sliderStyle)
                    ExpressivePlayerSlider(
                        value = displayedPosition,
                        onValueChange = { handleSeekPreview(it) },
                        onValueChangeFinished = { commitSeekPreview() },
                        valueRange = 0f..sliderEnd,
                        trackHeight = if (sliderStyle == SliderStyle.DEFAULT) animatedTrackHeight else spec.trackHeight,
                        thumbHeight = spec.thumbHeight,
                        thumbTrackGap = spec.thumbTrackGap,
                        interactionSource = interactionSource,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatTime(displayedPositionMs),
                style = MaterialTheme.typography.labelSmall,
                color = if (isInteracting) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontWeight = if (isInteracting) FontWeight.Bold else FontWeight.Medium,
            )
            Text(
                formatTime(duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun Float.coerceAtPositive(minimumValue: Float): Float = if (this < minimumValue) minimumValue else this
