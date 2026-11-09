package io.github.aedev.flow.ui.components.videoplayer

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.components.videoplayer.motion.DraggablePlayerGeometry
import io.github.aedev.flow.ui.components.videoplayer.motion.OpenOriginRect
import io.github.aedev.flow.ui.components.videoplayer.motion.lerpClamped
import io.github.aedev.flow.ui.theme.PlayerGround

private val MiniPlayerShadowElevation = 8.dp

/**
 * Sizes the video box once at its expanded size and morphs it toward the mini player or an open's
 * origin card with a graphics layer, reading every animated value in the layout and draw phases.
 */
internal fun Modifier.draggablePlayerVideoBox(
    state: PlayerDraggableState,
    geometry: DraggablePlayerGeometry,
    statusBarHeight: Float,
    videoHeight: () -> Float,
    portraitFsTravel: () -> Float,
    openRect: () -> OpenOriginRect?,
    miniRestingY: () -> Float,
    cornerRadius: () -> Float,
): Modifier =
    this
        .layout { measurable, constraints ->
            val boxHeight = videoHeight()
            state.portraitFsTravel = portraitFsTravel()
            val targetW =
                geometry.expandedVideoWidth
                    .toInt()
                    .coerceIn(1, constraints.maxWidth.coerceAtLeast(1))
            val targetH =
                boxHeight
                    .toInt()
                    .coerceIn(1, constraints.maxHeight.coerceAtLeast(1))
            val placeable =
                measurable.measure(
                    constraints.copy(
                        minWidth = targetW,
                        maxWidth = targetW,
                        minHeight = targetH,
                        maxHeight = targetH,
                    ),
                )
            layout(targetW, targetH) { placeable.place(0, 0) }
        }.graphicsLayer {
            val fraction = state.expandFraction.value
            val origin = openRect()
            val endWidth =
                origin?.width ?: geometry
                    .miniBoxWidth(geometry.baseMiniWidth * state.miniSizeScale.value)
                    .coerceAtMost(geometry.maxWideWidth)
            val visualScale =
                lerpClamped(
                    1f,
                    endWidth / geometry.expandedVideoWidth.coerceAtLeast(1f),
                    fraction,
                )
            val drag = if (fraction > 0.6f) 1f else state.expandDragScale.value
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = visualScale * drag
            scaleY = visualScale * drag
            val windowW = geometry.expandedVideoWidth * visualScale
            val windowH = size.height * visualScale
            val expandedTopY =
                statusBarHeight + state.portraitFsFraction.coerceAtLeast(0f) * portraitFsTravel()
            translationX =
                lerpClamped(0f, origin?.left ?: state.offsetX.value, fraction) +
                windowW * (1f - drag) / 2f
            translationY =
                lerpClamped(
                    expandedTopY,
                    origin?.top ?: miniRestingY(),
                    fraction,
                ) + windowH * (1f - drag) / 2f
            shadowElevation =
                if (origin == null && fraction > 0.95f) {
                    MiniPlayerShadowElevation.toPx() / geometry.visualMiniScale
                } else {
                    0f
                }
            shape = RoundedCornerShape(cornerRadius())
            clip = false
        }.drawBehind {
            val r = cornerRadius()
            drawRoundRect(
                color = PlayerGround,
                cornerRadius = CornerRadius(r, r),
            )
        }
