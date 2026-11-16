package io.github.aedev.flow.ui.components.layout.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.NavBarLabels
import io.github.aedev.flow.data.local.NavBarLook
import io.github.aedev.flow.ui.components.shared.MediaMiniBarDefaults
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SCRUB_SCALE = 1.035f
private val BelowIndicatorTop = 4.dp

/** One slot of the floating bar: a tab, or the "More" slot when [tab] is null. */
private data class FloatingNavSlot(
    val tab: FlowTab?,
    val name: String,
)

/**
 * The bottom bar in its floating style: Material's floating toolbar holding one row of tabs with a single
 * indicator that travels between them. Its front edge leads on the fast spring and its back edge follows on
 * the default one, so it stretches on the way, and every slot's width springs with it. Dragging along the
 * bar scrubs the indicator across the tabs and opens the one under the finger on release.
 *
 * Material's navigation items each draw their own indicator, so none can travel between them; the row is
 * built from foundation primitives inside Material's toolbar container instead.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FlowFloatingNavigationBar(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    look: NavBarLook,
    modifier: Modifier = Modifier,
) {
    val (barTabs, overflowTabs) = splitTabsForOverflow(tabs, MAX_BAR_TABS)
    val moreName = stringResource(R.string.nav_more)
    val slots =
        barTabs.map { FloatingNavSlot(it, stringResource(it.labelRes)) } +
            listOfNotNull(FloatingNavSlot(null, moreName).takeIf { overflowTabs.isNotEmpty() })
    val open =
        when (selectedTab) {
            null -> -1
            in barTabs -> barTabs.indexOf(selectedTab)
            in overflowTabs -> slots.lastIndex
            else -> -1
        }
    val labels = if (open < 0 && look.labels == NavBarLabels.SELECTED) NavBarLabels.NEVER else look.labels

    val metrics = rememberFloatingNavMetrics()
    val density = LocalDensity.current
    val sidePadding = FloatingToolbarDefaults.ContentPadding.calculateLeftPadding(LayoutDirection.Ltr)
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val maxRowWidth = with(density) { windowWidth - 2 * (MediaMiniBarDefaults.CompactMargin + sidePadding).toPx() }
    val measurer = rememberTextMeasurer()
    val besideStyle = MaterialTheme.typography.labelLarge
    val belowStyle = MaterialTheme.typography.labelMedium
    val nameWidths =
        remember(slots, labels, besideStyle, belowStyle, density) {
            val style = if (labels == NavBarLabels.ALWAYS) belowStyle else besideStyle
            slots.map {
                measurer
                    .measure(it.name, style, maxLines = 1, softWrap = false)
                    .size.width
                    .toFloat()
            }
        }
    val geometry =
        remember(nameWidths, open, labels, maxRowWidth, metrics) {
            floatingNavGeometry(nameWidths, open.coerceAtLeast(0), labels, maxRowWidth, collapsed = false, metrics = metrics)
        }

    var scrubbing by remember { mutableStateOf(false) }
    val scrubScale by animateFloatAsState(
        targetValue = if (scrubbing) SCRUB_SCALE else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "navScrubScale",
    )
    var menuOpen by remember { mutableStateOf(false) }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(ShortNavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = MediaMiniBarDefaults.CompactMargin)
                .padding(bottom = FloatingToolbarDefaults.ScreenOffset),
        contentAlignment = Alignment.BottomCenter,
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
            // Items bring their own height; the toolbar's vertical padding would make the bar taller than 64 dp.
            contentPadding = PaddingValues(horizontal = sidePadding),
            modifier =
                Modifier.graphicsLayer {
                    scaleX = scrubScale
                    scaleY = scrubScale
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
        ) {
            FloatingNavRow(
                slots = slots,
                open = open,
                geometry = geometry,
                metrics = metrics,
                scrub = look.scrub,
                pop = look.iconPop,
                onScrubbing = { scrubbing = it },
                onSelect = { index ->
                    val tab = slots.getOrNull(index)?.tab
                    if (tab != null) {
                        onTabSelected(tab)
                    } else if (overflowTabs.isNotEmpty()) {
                        menuOpen = true
                    }
                },
            )
            if (overflowTabs.isNotEmpty()) {
                // The menu anchors to the end of the bar, where "More" sits.
                Box {
                    FlowTabOverflowMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        tabs = overflowTabs,
                        selectedTab = selectedTab,
                        onTabSelected = onTabSelected,
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingNavRow(
    slots: List<FloatingNavSlot>,
    open: Int,
    geometry: FloatingNavGeometry,
    metrics: FloatingNavMetrics,
    scrub: Boolean,
    pop: Boolean,
    onScrubbing: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val colors = ShortNavigationBarItemDefaults.colors()
    val haptics = LocalHapticFeedback.current
    var hover by remember { mutableIntStateOf(-1) }
    val target = if (hover >= 0) geometry.indicatorSpan(hover, metrics) else geometry.indicatorStart..geometry.indicatorEnd
    val indicatorStart = remember { Animatable(target.start) }
    val indicatorEnd = remember { Animatable(target.endInclusive) }
    LaunchedEffect(target.start, target.endInclusive) {
        val forward = target.start > indicatorStart.value
        launch { indicatorStart.animateTo(target.start, if (forward) motion.defaultSpatialSpec() else motion.fastSpatialSpec()) }
        launch { indicatorEnd.animateTo(target.endInclusive, if (forward) motion.fastSpatialSpec() else motion.defaultSpatialSpec()) }
    }
    val currentGeometry by rememberUpdatedState(geometry)
    val ltr by rememberUpdatedState(LocalLayoutDirection.current == LayoutDirection.Ltr)
    val currentSelect by rememberUpdatedState(onSelect)
    val indicatorColor = colors.selectedIndicatorColor
    val belowNames = geometry.names == FloatingNavNames.BELOW

    Layout(
        content = {
            slots.forEachIndexed { index, slot ->
                key(slot.tab, slot.name) {
                    val selected = index == open
                    val target = geometry.widths.getOrElse(index) { 0f }
                    val width = animateFloatAsState(target, motion.defaultSpatialSpec(), label = "navItemWidth")
                    FloatingNavItem(
                        icon = slot.tab?.icon(selected) ?: Icons.Filled.MoreHoriz,
                        name = slot.name,
                        selected = selected,
                        names = geometry.names,
                        width = width,
                        namedWidth = target,
                        contentColor =
                            when {
                                !selected -> colors.unselectedIconColor
                                belowNames -> colors.selectedIconColor
                                else -> colors.selectedTextColorStartIconPosition
                            },
                        pop = pop,
                        onClick = { currentSelect(index) },
                    )
                }
            }
        },
        modifier =
            Modifier
                .drawBehind {
                    if (open < 0) return@drawBehind
                    val height = if (belowNames) FloatingNavBelowIndicatorHeight.toPx() else size.height
                    val top = if (belowNames) BelowIndicatorTop.toPx() else 0f
                    var start = indicatorStart.value
                    var end = maxOf(indicatorEnd.value, start + height)
                    if (layoutDirection == LayoutDirection.Rtl) {
                        val mirroredStart = size.width - end
                        end = size.width - start
                        start = mirroredStart
                    }
                    drawRoundRect(
                        color = indicatorColor,
                        topLeft = Offset(start, top),
                        size = Size(end - start, height),
                        cornerRadius = CornerRadius(height / 2f),
                    )
                }.pointerInput(scrub) {
                    if (!scrub) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val drag =
                            awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                ?: return@awaitEachGesture

                        fun hoverAt(x: Float) {
                            val index = currentGeometry.itemAt(if (ltr) x else size.width - x)
                            if (index != hover) {
                                hover = index
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        }
                        onScrubbing(true)
                        hoverAt(drag.position.x)
                        val finished =
                            horizontalDrag(drag.id) { change ->
                                change.consume()
                                hoverAt(change.position.x)
                            }
                        val chosen = hover
                        hover = -1
                        onScrubbing(false)
                        if (finished && chosen >= 0) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            currentSelect(chosen)
                        }
                    }
                },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)) }
        val starts = IntArray(placeables.size)
        var x = 0f
        placeables.forEachIndexed { index, placeable ->
            starts[index] = x.roundToInt()
            x += placeable.width
            val next = placeables.getOrNull(index + 1)
            if (next != null) x += metrics.gap * minOf(1f, placeable.width / metrics.iconItem, next.width / metrics.iconItem)
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(x.roundToInt(), height) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(starts[index], (height - placeable.height) / 2) }
        }
    }
}
