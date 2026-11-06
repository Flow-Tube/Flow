package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import org.schabi.newpipe.extractor.stream.StreamSegment

private val EdgeAlignedHeight = 14.dp
private val ExpandedRowHeight = 32.dp

/** Colours the track needs, resolved once in composition so the draw lambda stays theme-free. */
internal data class SeekTrackColors(
    val track: Color,
    val buffered: Color,
    val active: Color,
    val playhead: Color,
    val mark: Color,
)

/**
 * Seek bar with buffered progress, chapter gaps and SponsorBlock segments painted over the track.
 *
 * Two shapes, and they are built differently on purpose:
 *
 * - Expanded (in the controls overlay) is a real [Slider]. Its custom look lives in the `track`
 *   slot, so Material owns dragging, press-to-position, keyboard and accessibility.
 * - Edge-aligned (the thin strip under the video when controls are hidden) is
 *   [EdgeAlignedSeekbar], which cannot be a [Slider] — its documentation records why.
 *
 * Both read the playhead out of one [SliderState]: it is a snapshot-backed float, so the track and
 * the thumb pick a tick up in the layout and draw phases and this composable is not invalidated.
 */
@Composable
fun MediaSeekBar(
    /**
     * Progress provider rather than a value: the playhead is written several times a second, and
     * reading it — here or at the call site — subscribes a composable to that tick. It is collected
     * into [SliderState] instead, so nothing recomposes between drags.
     */
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    chapters: List<StreamSegment> = emptyList(),
    sponsorSegments: List<SponsorBlockSegment> = emptyList(),
    /**
     * Per-category colour overrides chosen by the user in SponsorBlock settings. Categories absent
     * from the map fall back to the shared defaults.
     */
    sponsorColors: Map<String, Color> = emptyMap(),
    /** Fractions of the bar to dot, in ascending order. Only painted: they take no touches. */
    commentMarks: List<Float> = emptyList(),
    duration: Long = 0L,
    bufferedValue: Float = 0f,
    edgeAligned: Boolean = false,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val thumbFillColor = MaterialTheme.colorScheme.surface
    val trackColors =
        SeekTrackColors(
            track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
            buffered = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.56f),
            active = primaryColor,
            playhead = thumbFillColor,
            // The bar sits over the video in every theme, so onSurface would be dark on dark in a light one.
            mark = PlayerScrimContent,
        )
    val thumbStateLayerColor = primaryColor.copy(alpha = 0.18f)

    var edgePointerActive by remember { mutableStateOf(false) }

    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isInteracting = isPressed || isDragged || edgePointerActive

    // Seeded without read observation so the bar is correct on its first frame without that read
    // subscribing this composable to the playhead for the rest of its life.
    val sliderState = remember { SliderState(value = Snapshot.withoutReadObservation { value() }) }

    // While the thumb is held the component shows the dragged position; otherwise it follows the
    // playhead. `isScrubbing` is only ever set in the same handler that writes the state, so the
    // displayed value can never be a stale leftover from a previous drag.
    val isScrubbing = remember { mutableStateOf(false) }
    val currentValue by rememberUpdatedState(value)
    LaunchedEffect(sliderState) {
        snapshotFlow { currentValue() }.collect { fraction ->
            if (!isScrubbing.value) sliderState.value = fraction
        }
    }

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val onScrub =
        remember(sliderState) {
            { newValue: Float ->
                isScrubbing.value = true
                sliderState.value = newValue
                currentOnValueChange(newValue)
            }
        }
    val onScrubFinished: () -> Unit =
        remember(sliderState) {
            {
                isScrubbing.value = false
                currentOnValueChangeFinished?.invoke()
                Unit
            }
        }
    sliderState.onValueChange = onScrub
    sliderState.onValueChangeFinished = onScrubFinished

    // Animated in the draw phase rather than through Modifier.height: the track used to grow by
    // animating a layout constraint, which forced a layout pass on every frame of the touch
    // response. The Canvas is a fixed box and only the painted band changes size.
    val trackExpansion = remember { Animatable(0f) }
    val thumbScale = remember { Animatable(0f) }

    LaunchedEffect(isInteracting) {
        trackExpansion.animateTo(
            targetValue = if (isInteracting) 1f else 0f,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
        )
    }

    LaunchedEffect(isInteracting) {
        thumbScale.animateTo(
            targetValue = if (isInteracting) 1f else 0f,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
        )
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (edgeAligned) Alignment.BottomCenter else Alignment.TopStart,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(if (edgeAligned) EdgeAlignedHeight else ExpandedRowHeight),
            contentAlignment = if (edgeAligned) Alignment.BottomCenter else Alignment.Center,
        ) {
            if (edgeAligned) {
                EdgeAlignedSeekbar(
                    displayValueProvider = { sliderState.value },
                    enabled = enabled,
                    chapters = chapters,
                    sponsorSegments = sponsorSegments,
                    sponsorColors = sponsorColors,
                    commentMarks = commentMarks,
                    duration = duration,
                    bufferedValue = bufferedValue,
                    colors = trackColors,
                    expansionProvider = { trackExpansion.value },
                    thumbScaleProvider = { thumbScale.value },
                    onScrub = onScrub,
                    onPointerActiveChange = { active ->
                        edgePointerActive = active
                        if (!active) onScrubFinished()
                    },
                )
            } else {
                // A timeline runs left to right in every language, and the track below draws it that way.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    @OptIn(ExperimentalMaterial3Api::class)
                    Slider(
                        state = sliderState,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        interactionSource = interactionSource,
                        colors = SliderDefaults.colors(thumbColor = thumbFillColor),
                        track = { state ->
                            Canvas(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(ActiveTrackHeight),
                            ) {
                                drawSeekTrack(
                                    fraction = state.coercedValueAsFraction,
                                    expansion = trackExpansion.value,
                                    chapters = chapters,
                                    sponsorSegments = sponsorSegments,
                                    sponsorColors = sponsorColors,
                                    commentMarks = commentMarks,
                                    duration = duration,
                                    bufferedValue = bufferedValue,
                                    colors = trackColors,
                                    bottomAligned = false,
                                )
                            }
                        },
                        thumb = {
                            Box(
                                modifier =
                                    Modifier
                                        .size(16.dp)
                                        .graphicsLayer {
                                            val scale = thumbScale.value
                                            scaleX = scale
                                            scaleY = scale
                                        }.drawBehind {
                                            if (isInteracting) {
                                                drawCircle(
                                                    color = thumbStateLayerColor,
                                                    radius = 20.dp.toPx(),
                                                )
                                            }
                                        },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .matchParentSize()
                                            .background(thumbFillColor, CircleShape)
                                            .border(3.dp, primaryColor, CircleShape),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
