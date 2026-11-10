package io.github.aedev.flow.ui.components.musicplayer.controls

import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SliderStyle
import io.github.aedev.flow.ui.components.musicplayer.common.formatTime
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
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
    val dpad = LocalDpadNavigation.current
    val shellInteractionSource = remember { MutableInteractionSource() }
    val shellFocused by shellInteractionSource.collectIsFocusedAsState()
    val isEmphasized = isInteracting || shellFocused
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
        targetValue = if (isEmphasized) 22.dp else 16.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "trackHeight",
    )

    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { PlayerPreferences(context) }
    val sliderStyle by preferences.sliderStyle.collectAsState(initial = SliderStyle.COMPACT)
    val seekSeconds by preferences.doubleTapSeekSeconds.collectAsState(initial = DpadSeekStep.FALLBACK_SECONDS)

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
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

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                if (dpad) {
                    // One focus stop that seeks by the user's seek step: the sliders' own key handling moves
                    // a percentage of the track, and the wavy style takes no focus at all.
                    Modifier
                        .border(
                            width = DpadFocusRingWidth,
                            color = if (shellFocused) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            shape = MaterialTheme.shapes.small,
                        ).onKeyEvent { event ->
                            val direction =
                                when (event.key) {
                                    Key.DirectionLeft -> -1
                                    Key.DirectionRight -> 1
                                    else -> return@onKeyEvent false
                                }
                            when (event.type) {
                                KeyEventType.KeyDown -> {
                                    val from = if (isSeeking) seekPreviewPosition.toLong() else positionProvider()
                                    val target =
                                        DpadSeekStep.target(
                                            fromMs = from,
                                            direction = direction,
                                            seekSeconds = seekSeconds,
                                            repeatCount = event.nativeKeyEvent.repeatCount,
                                            durationMs = duration,
                                        )
                                    handleSeekPreview(target.toFloat())
                                    true
                                }

                                KeyEventType.KeyUp -> {
                                    commitSeekPreview()
                                    true
                                }

                                else -> {
                                    false
                                }
                            }
                        }.focusable(interactionSource = shellInteractionSource)
                } else {
                    Modifier
                },
        ) {
            val sliderModifier = if (dpad) Modifier.focusProperties { canFocus = false } else Modifier

            when (sliderStyle) {
                SliderStyle.SQUIGGLY -> {
                    SquigglySlider(
                        value = displayedPosition,
                        onValueChange = { handleSeekPreview(it) },
                        onValueChangeFinished = { commitSeekPreview() },
                        valueRange = 0f..sliderEnd,
                        isPlaying = isPlaying,
                        interactionSource = interactionSource,
                        modifier = sliderModifier,
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
                        modifier = sliderModifier,
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
                color = if (isEmphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontWeight = if (isEmphasized) FontWeight.Bold else FontWeight.Medium,
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

private val DpadFocusRingWidth = 2.dp

private fun Float.coerceAtPositive(minimumValue: Float): Float = if (this < minimumValue) minimumValue else this
