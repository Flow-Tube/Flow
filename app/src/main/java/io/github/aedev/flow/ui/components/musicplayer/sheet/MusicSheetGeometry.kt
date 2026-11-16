package io.github.aedev.flow.ui.components.musicplayer.sheet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import io.github.aedev.flow.ui.components.shared.MediaMiniBarBounds
import kotlinx.coroutines.delay

/**
 * Every per-frame number of the morphing card, read only from layout, draw and outline lambdas so
 * a drag or a settle never recomposes the sheet. Inputs that change between compositions are held
 * as [State] so the functions always see the latest value without being re-created.
 */
@Stable
internal class MusicSheetGeometry(
    private val state: MusicPlayerSheetState,
    private val predictiveBackProgress: Animatable<Float, AnimationVector1D>,
    private val collapsedY: State<Float>,
    private val restingBottomPx: () -> Float,
    private val containerHeightPx: State<Float>,
    private val miniHeightPx: State<Float>,
    private val containerWidthPx: State<Float>,
    private val miniBounds: State<MediaMiniBarBounds>,
    private val dockShiftPx: () -> Float,
    private val collapsedRadiusPx: State<Float>,
) {
    fun fraction(): Float = (state.expansionFraction.value * (1f - predictiveBackProgress.value)).coerceIn(0f, 1f)

    fun baseTranslationY(): Float {
        val p = predictiveBackProgress.value
        return state.translationY.value * (1f - p) + collapsedY.value * p
    }

    fun visualTranslationY(): Float = baseTranslationY() - restingBottomPx() * (1f - fraction())

    fun cardHeightPx(): Float {
        val f = fraction()
        val baseY = baseTranslationY()
        val collapsed = collapsedY.value
        val mini = miniHeightPx.value
        return if (baseY <= collapsed) {
            (lerp(collapsed + mini, containerHeightPx.value, f) - baseY).coerceAtLeast(0f)
        } else {
            lerp(mini, containerHeightPx.value, f)
        }
    }

    /** The card's start edge: the mini player's resting start, opening out to the window's edge. */
    fun cardStartPx(): Float = lerp(miniBounds.value.start + dockShiftPx(), 0f, fraction())

    fun cardWidthPx(): Float = lerp((miniBounds.value.width - dockShiftPx()).coerceAtLeast(0f), containerWidthPx.value, fraction())

    fun containerWidthPx(): Float = containerWidthPx.value

    fun cornerRadiusPx(): Float = collapsedRadiusPx.value * (1f - fraction())
}

@Composable
internal fun rememberMusicSheetGeometry(
    state: MusicPlayerSheetState,
    predictiveBackProgress: Animatable<Float, AnimationVector1D>,
    collapsedYPx: Float,
    restingBottomPx: () -> Float,
    containerHeightPx: Float,
    miniHeightPx: Float,
    containerWidthPx: Float,
    miniBounds: MediaMiniBarBounds,
    dockShiftPx: () -> Float,
    collapsedRadiusPx: Float,
): MusicSheetGeometry {
    val collapsedY = rememberUpdatedState(collapsedYPx)
    val restingBottom = rememberUpdatedState(restingBottomPx)
    val containerHeight = rememberUpdatedState(containerHeightPx)
    val miniHeight = rememberUpdatedState(miniHeightPx)
    val containerWidth = rememberUpdatedState(containerWidthPx)
    val bounds = rememberUpdatedState(miniBounds)
    val dockShift = rememberUpdatedState(dockShiftPx)
    val collapsedRadius = rememberUpdatedState(collapsedRadiusPx)
    return remember(state, predictiveBackProgress) {
        MusicSheetGeometry(
            state = state,
            predictiveBackProgress = predictiveBackProgress,
            collapsedY = collapsedY,
            restingBottomPx = { restingBottom.value() },
            containerHeightPx = containerHeight,
            miniHeightPx = miniHeight,
            containerWidthPx = containerWidth,
            miniBounds = bounds,
            dockShiftPx = { dockShift.value() },
            collapsedRadiusPx = collapsedRadius,
        )
    }
}

/**
 * The full player composes as soon as expanding starts, or 650 ms after the sheet first shows, and
 * then stays composed (warm) for the sheet's life, across track changes, so no expand ever waits
 * for it and no track change rebuilds it.
 */
@Composable
internal fun rememberShouldRenderFullPlayer(state: MusicPlayerSheetState): Boolean {
    var warmed by remember { mutableStateOf(false) }
    LaunchedEffect(state.anchor) {
        if (warmed) return@LaunchedEffect
        if (state.isExpanded) {
            warmed = true
        } else {
            delay(650)
            warmed = true
        }
    }
    val shouldRender by remember(state) {
        derivedStateOf {
            state.isExpanded || state.expansionFraction.value > 0.015f || warmed
        }
    }
    return shouldRender
}

/**
 * Reads its radius at outline time; the card's size changes every morph frame, so the outline is
 * re-created (and the new radius picked up) without allocating a shape per frame.
 */
internal class MusicSheetDynamicShape(
    private val radiusPxProvider: () -> Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radiusPx = radiusPxProvider().coerceAtLeast(0f)
        return Outline.Rounded(
            RoundRect(
                rect = Rect(0f, 0f, size.width, size.height),
                cornerRadius = CornerRadius(radiusPx, radiusPx),
            ),
        )
    }
}
