package io.github.aedev.flow.ui.components.shared

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.utils.flowWindowDpSize
import kotlinx.coroutines.launch

private fun sheetSpring() =
    spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )

private const val DISMISS_PROGRESS_THRESHOLD = 0.55f
private const val DISMISS_VELOCITY_THRESHOLD = 1_200f
private const val DEFAULT_EXPANDED_HEIGHT_FRACTION = 0.75f

/**
 * Three quarters of the window: the height a media sheet falls back to when its host does not
 * measure one for it.
 *
 * Read from the window rather than from `LocalConfiguration`, which reports the configuration's
 * screen size and so ignores multi-window and freeform bounds.
 */
@Composable
fun defaultSheetExpandedHeight(): Dp = flowWindowDpSize().height * DEFAULT_EXPANDED_HEIGHT_FRACTION

/**
 * Handle onto a live [FlowBottomSheet], so a control inside the sheet can run the same exit
 * animation a downward drag runs instead of tearing the sheet out of the tree.
 */
@Stable
class FlowBottomSheetState {
    internal var dismissRequest: ((after: () -> Unit) -> Unit)? = null

    /** Runs the exit animation, then the sheet's `onDismiss`, then [after]. */
    fun dismiss(after: () -> Unit = {}) {
        dismissRequest?.invoke(after)
    }
}

@Composable
fun rememberFlowBottomSheetState(): FlowBottomSheetState = remember { FlowBottomSheetState() }

/**
 * The app's one drag-to-dismiss bottom sheet: a progress `Animatable` between [collapsedHeight] and
 * either [expandedHeight] or the content's own height, moved with `translationY` inside a
 * `graphicsLayer` so a drag never recomposes the tree above it.
 *
 * Hand-rolled rather than taken from Material 3 because neither shipped sheet fits these hosts:
 * `ModalBottomSheet` is modal — it dims and blocks the video these sheets must sit *under* and
 * resize, and it owns its own window; `BottomSheetScaffold` owns the whole screen layout, while
 * these sheets are slots inside the draggable player overlay and are also hosted, non-dismissible,
 * inside the fullscreen side panel. Everything else here is the platform: `Animatable`,
 * `detectVerticalDragGestures`, `VelocityTracker`, `BackHandler`, and M3's own `DragHandle`,
 * `SheetMaxWidth`, `ExpandedShape` and `ContainerColor`.
 *
 * @param expandedHeight fixed height for the sheet; when null the sheet wraps its content, bounded
 *   by [maxHeight].
 * @param collapsedHeight the height the sheet animates down to before reporting [onDismiss]; the
 *   player leaves it above zero while the video is resizing to meet the sheet.
 * @param dismissible when false the sheet neither drags nor animates — it is a hosted panel, and
 *   back still dismisses it immediately, as the fullscreen side panel relies on.
 * @param onProgressChange 0 at [collapsedHeight] and 1 at the expanded height, emitted every frame
 *   of a drag or animation.
 * @param onBack invoked instead of dismissing when the sheet has its own back destination, as the
 *   paged settings sheet does.
 * @param header drawn above [content] and handed the drag modifier, so a title row drags the sheet
 *   like the handle does. The slot owns the drag handle; when it is null the sheet draws a bare
 *   M3 handle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    state: FlowBottomSheetState = rememberFlowBottomSheetState(),
    maxHeight: Dp? = null,
    expandedHeight: Dp? = null,
    collapsedHeight: Dp = 0.dp,
    dismissible: Boolean = true,
    dismissOnOutsideTap: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    onBack: (() -> Unit)? = null,
    onProgressChange: (Float) -> Unit = {},
    onVisibleHeightChange: (Float) -> Unit = {},
    header: (@Composable ColumnScope.(dragModifier: Modifier) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestOnBack by rememberUpdatedState(onBack)
    val latestOnProgressChange by rememberUpdatedState(onProgressChange)
    val latestOnVisibleHeightChange by rememberUpdatedState(onVisibleHeightChange)
    val collapsedHeightPx = with(LocalDensity.current) { collapsedHeight.toPx() }.coerceAtLeast(0f)
    val latestCollapsedHeightPx by rememberUpdatedState(collapsedHeightPx)

    val progress = remember { Animatable(0f) }
    var sheetHeightPx by remember { mutableIntStateOf(0) }
    var isAnimatingOut by remember { mutableStateOf(false) }

    fun dragRangePx(heightPx: Float): Float = (heightPx - collapsedHeightPx.coerceAtMost(heightPx)).coerceAtLeast(1f)

    fun animateIn() {
        scope.launch { progress.animateTo(targetValue = 1f, animationSpec = sheetSpring()) }
    }

    fun animateOut(after: () -> Unit = {}) {
        if (isAnimatingOut) return
        if (!dismissible) {
            latestOnDismiss()
            after()
            return
        }
        isAnimatingOut = true
        scope.launch {
            progress.animateTo(targetValue = 0f, animationSpec = sheetSpring())
            latestOnDismiss()
            after()
        }
    }

    LaunchedEffect(expandedHeight, collapsedHeight, dismissible) {
        if (isAnimatingOut) return@LaunchedEffect
        if (!dismissible) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.animateTo(targetValue = 1f, animationSpec = sheetSpring())
    }

    LaunchedEffect(Unit) {
        snapshotFlow { progress.value }
            .collect { value -> latestOnProgressChange(value) }
    }

    LaunchedEffect(Unit) {
        snapshotFlow {
            val collapsed = latestCollapsedHeightPx.coerceAtMost(sheetHeightPx.toFloat())
            collapsed + (sheetHeightPx - collapsed) * progress.value
        }.collect { visibleHeight -> latestOnVisibleHeightChange(visibleHeight) }
    }

    DisposableEffect(state, dismissible) {
        state.dismissRequest = { after -> animateOut(after) }
        onDispose { state.dismissRequest = null }
    }

    BackHandler {
        val handleBack = latestOnBack
        if (handleBack != null) handleBack() else animateOut()
    }

    val dragModifier =
        if (!dismissible) {
            Modifier
        } else {
            Modifier.pointerInput(sheetHeightPx, collapsedHeightPx, isAnimatingOut) {
                if (sheetHeightPx <= 0) return@pointerInput
                val rangePx = dragRangePx(sheetHeightPx.toFloat())
                val velocityTracker = VelocityTracker()
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        if (isAnimatingOut) return@detectVerticalDragGestures
                        velocityTracker.addPointerInputChange(change)
                        scope.launch {
                            progress.snapTo((progress.value - dragAmount / rangePx).coerceIn(0f, 1f))
                        }
                    },
                    onDragCancel = {
                        velocityTracker.resetTracking()
                        if (!isAnimatingOut) animateIn()
                    },
                    onDragEnd = {
                        val velocityY = velocityTracker.calculateVelocity().y
                        velocityTracker.resetTracking()
                        when {
                            velocityY > DISMISS_VELOCITY_THRESHOLD ||
                                progress.value < DISMISS_PROGRESS_THRESHOLD -> animateOut()

                            else -> animateIn()
                        }
                    },
                )
            }
        }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (dismissOnOutsideTap) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { animateOut() }
                        },
            )
        }
        Surface(
            modifier =
                Modifier
                    .widthIn(max = BottomSheetDefaults.SheetMaxWidth)
                    .fillMaxWidth()
                    .then(if (expandedHeight != null) Modifier.height(expandedHeight) else Modifier)
                    .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier)
                    .onSizeChanged { size -> sheetHeightPx = size.height }
                    .graphicsLayer {
                        translationY = dragRangePx(size.height) * (1f - progress.value)
                    },
            shape = shape,
            color = containerColor,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
            ) {
                if (header != null) {
                    header(dragModifier)
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(dragModifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        BottomSheetDefaults.DragHandle()
                    }
                }
                content()
            }
        }
    }
}
