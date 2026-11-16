package io.github.aedev.flow.ui.components.layout

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Everything the app draws over the bottom of every page: the gesture area, the navigation bar
 * and the collapsed music mini player. Pages run to the bottom edge and pad their own scrolling
 * content by [contentBottom], so a list scrolled to the end stops with its last item clear of all
 * three, and nothing but the page is ever painted behind them.
 *
 * [contentBottom] and [barBottom] are settled values: they change once when the bar or the mini
 * player finishes appearing or hiding, never per animation frame. Anything that should ride along
 * with the bar or the mini player while they move reads the px functions from a layout, offset or
 * draw lambda. [miniPlayerHeight] is the space the mini player takes, its gap above the bar included.
 */
@Stable
class FlowBottomInsets(
    private val systemInset: State<Dp>,
    private val barHeight: State<Dp>,
    private val barShown: State<Boolean>,
    private val miniPlayerHeight: State<Dp>,
    private val miniPlayerShown: State<Boolean>,
    private val barFraction: () -> Float,
    private val miniPlayerFraction: () -> Float,
    private val miniPlayerSpanPx: State<ClosedFloatingPointRange<Float>> = mutableStateOf(0f..Float.MAX_VALUE),
    private val miniPlayerDock: () -> Float = { 0f },
    private val miniPlayerDockShift: State<Float> = mutableStateOf(0f),
    private val dockedLift: State<Dp> = mutableStateOf(0.dp),
) {
    /** Bottom space the settled chrome covers: gesture area, the bar when shown, the mini player when shown. */
    val contentBottom: Dp get() = systemInset.value + barBottom + if (miniPlayerShown.value) miniPlayerHeight.value else 0.dp

    /** The bar's height while it is shown and settled, else zero. */
    val barBottom: Dp get() = if (barShown.value) barHeight.value else 0.dp

    /** The gesture area and the bar, without the mini player: for surfaces the mini player does not reach. */
    val navigationBottom: Dp get() = systemInset.value + barBottom

    /** The system gesture or button area alone. */
    val systemBottom: Dp get() = systemInset.value

    /** What the settled bar and mini player add above the gesture area, for pages that pad that area themselves. */
    val chromeAboveSystem: Dp get() = contentBottom - systemInset.value

    /**
     * Px from the window's bottom edge to where the mini player rests right now, following the bar, or
     * dropping into the floating bar's row while it sits beside the shrunk bar.
     */
    fun miniPlayerBaselinePx(density: Density): Float =
        with(density) {
            val system = systemInset.value.toPx()
            val aboveBar = system + barHeight.value.toPx() * barFraction()
            val inRow = system + dockedLift.value.toPx()
            aboveBar + (inRow - aboveBar) * miniPlayerDock()
        }

    /** Px the mini player's start edge moves in, and its width shrinks, to sit beside the shrunk floating bar. */
    fun miniPlayerDockShiftPx(): Float = miniPlayerDockShift.value * miniPlayerDock()

    /** Px from the window's bottom edge to the top of the chrome right now, for things that float above it. */
    fun floatingBottomPx(density: Density): Float =
        miniPlayerBaselinePx(density) + with(density) { miniPlayerHeight.value.toPx() } * miniPlayerFraction()

    /**
     * Like [floatingBottomPx] for an element spanning [leftPx] to [rightPx] in the window: it clears
     * the mini player only when the two overlap across the width, as a centred card on a wide
     * window leaves the corners free.
     */
    fun floatingBottomPx(
        density: Density,
        leftPx: Float,
        rightPx: Float,
    ): Float {
        val span = miniPlayerSpanPx.value
        val overlaps = rightPx > span.start && leftPx < span.endInclusive
        return if (overlaps) floatingBottomPx(density) else miniPlayerBaselinePx(density)
    }

    companion object {
        /** No chrome: previews, tests and surfaces outside the app shell. */
        val None =
            FlowBottomInsets(
                systemInset = mutableStateOf(0.dp),
                barHeight = mutableStateOf(0.dp),
                barShown = mutableStateOf(false),
                miniPlayerHeight = mutableStateOf(0.dp),
                miniPlayerShown = mutableStateOf(false),
                barFraction = { 0f },
                miniPlayerFraction = { 0f },
            )
    }
}

/** Provided once by the app shell; the instance never changes, only the states behind it. */
val LocalFlowBottomInsets = staticCompositionLocalOf { FlowBottomInsets.None }

/** The end padding a scrolling page needs: the settled chrome plus [extra] breathing room. */
@Composable
fun flowBottomContentPadding(extra: Dp = 16.dp): Dp = LocalFlowBottomInsets.current.contentBottom + extra

/** [PaddingValues] for a scrolling page whose bottom must clear the app's bottom chrome. */
@Composable
fun flowContentPadding(
    horizontal: Dp = 0.dp,
    top: Dp = 0.dp,
    extraBottom: Dp = 16.dp,
): PaddingValues =
    PaddingValues(
        start = horizontal,
        top = top,
        end = horizontal,
        bottom = flowBottomContentPadding(extraBottom),
    )

/**
 * Lifts a floating element (a FAB, a snackbar, a toolbar) above the bottom chrome, riding along as
 * it moves. The lift includes the mini player only where the element is actually above it.
 */
fun Modifier.floatAboveBottomChrome(insets: FlowBottomInsets): Modifier = this then FloatAboveBottomChromeElement(insets)

private data class FloatAboveBottomChromeElement(
    val insets: FlowBottomInsets,
) : ModifierNodeElement<FloatAboveBottomChromeNode>() {
    override fun create() = FloatAboveBottomChromeNode(insets)

    override fun update(node: FloatAboveBottomChromeNode) {
        node.insets = insets
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "floatAboveBottomChrome"
    }
}

private class FloatAboveBottomChromeNode(
    var insets: FlowBottomInsets,
) : Modifier.Node(),
    LayoutModifierNode {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            // Read in placement, so a moving bar or mini player re-places without a new measure.
            val left = coordinates?.positionInWindow()?.x
            val lift =
                if (left == null) {
                    insets.floatingBottomPx(this@measure)
                } else {
                    insets.floatingBottomPx(this@measure, left, left + placeable.width)
                }
            placeable.place(0, -lift.roundToInt())
        }
    }
}
