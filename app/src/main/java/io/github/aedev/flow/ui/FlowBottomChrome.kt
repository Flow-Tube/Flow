package io.github.aedev.flow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.components.layout.FlowBottomInsets
import io.github.aedev.flow.ui.components.layout.navigation.FlowNavigationDefaults
import io.github.aedev.flow.ui.components.layout.navigation.FlowNavigationScrollState
import io.github.aedev.flow.ui.components.shared.MediaMiniBarBounds
import io.github.aedev.flow.ui.components.shared.MediaMiniBarDefaults
import io.github.aedev.flow.ui.components.shared.mediaMiniBarBounds
import io.github.aedev.flow.ui.utils.LocalWindowSizeClass
import io.github.aedev.flow.ui.utils.isMediumWidth
import kotlinx.coroutines.flow.collectLatest

private val DockGap = 8.dp

/** What the shell draws over the bottom of every page, and where the collapsed mini bars rest across it. */
internal data class FlowBottomChrome(
    val insets: FlowBottomInsets,
    val miniBarBounds: MediaMiniBarBounds,
)

/**
 * The bottom chrome's insets, riding on the bar's and the mini player's own springs, and the mini bars'
 * span across a window [containerWidthPx] wide whose content starts after [railWidth].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberFlowBottomChrome(
    barPresent: Boolean,
    barShown: Boolean,
    miniPlayerShown: Boolean,
    systemBottomPx: Int,
    barHeight: Dp,
    containerWidthPx: Float,
    railWidth: Dp,
    navScrollState: FlowNavigationScrollState,
    miniPlayerBesideShrunkBar: Boolean,
): FlowBottomChrome {
    val density = LocalDensity.current
    val motionScheme = MaterialTheme.motionScheme
    val barFraction = remember { Animatable(if (barPresent) 1f else 0f) }
    LaunchedEffect(barPresent) {
        // The same springs as the bar's own slide, so whatever rides on it stays glued to it.
        barFraction.animateTo(
            targetValue = if (barPresent) 1f else 0f,
            animationSpec = if (barPresent) motionScheme.defaultSpatialSpec() else motionScheme.fastSpatialSpec(),
        )
    }
    val systemBottomState = rememberUpdatedState(with(density) { systemBottomPx.toDp() })
    val barHeightState = rememberUpdatedState(barHeight)
    val barShownState = rememberUpdatedState(barShown)
    val miniPlayerFraction = remember { Animatable(if (miniPlayerShown) 1f else 0f) }
    LaunchedEffect(miniPlayerShown) {
        miniPlayerFraction.animateTo(
            targetValue = if (miniPlayerShown) 1f else 0f,
            animationSpec = motionScheme.defaultSpatialSpec(),
        )
    }
    // Beside the shrunk bar, the mini player drops into the bar's row on the bar's own spring.
    val dock = remember { Animatable(0f) }
    LaunchedEffect(miniPlayerBesideShrunkBar, barPresent) {
        snapshotFlow { miniPlayerBesideShrunkBar && barPresent && navScrollState.isCollapsed }
            .collectLatest { docked -> dock.animateTo(if (docked) 1f else 0f, motionScheme.defaultSpatialSpec()) }
    }
    val miniPlayerShownState = rememberUpdatedState(miniPlayerShown)
    val miniPlayerHeightState = rememberUpdatedState(MediaMiniBarDefaults.Height + MediaMiniBarDefaults.BottomSpacer)
    val miniBarBounds =
        with(density) {
            mediaMiniBarBounds(
                containerWidthPx = containerWidthPx,
                startInsetPx = railWidth.toPx(),
                isCompactWidth = !LocalWindowSizeClass.current.isMediumWidth,
                compactMarginPx = MediaMiniBarDefaults.CompactMargin.toPx(),
                largeMarginPx = MediaMiniBarDefaults.LargeMargin.toPx(),
                maxWidthPx = MediaMiniBarDefaults.MaxWidth.toPx(),
            )
        }
    val shrunkBarEnd =
        with(density) {
            val toolbarPadding = FloatingToolbarDefaults.ContentPadding.calculateLeftPadding(LayoutDirection.Ltr)
            (MediaMiniBarDefaults.CompactMargin + toolbarPadding * 2 + FlowNavigationDefaults.FloatingItemSize + DockGap).toPx()
        }
    val dockShiftState = rememberUpdatedState((shrunkBarEnd - miniBarBounds.start).coerceAtLeast(0f))
    val dockedLiftState = rememberUpdatedState(FloatingToolbarDefaults.ScreenOffset - MediaMiniBarDefaults.BottomSpacer)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val miniPlayerSpanState =
        rememberUpdatedState(
            miniBarBounds.let { bounds ->
                val left = if (isRtl) containerWidthPx - bounds.start - bounds.width else bounds.start
                left..(left + bounds.width)
            },
        )
    val insets =
        remember {
            FlowBottomInsets(
                systemInset = systemBottomState,
                barHeight = barHeightState,
                barShown = barShownState,
                miniPlayerHeight = miniPlayerHeightState,
                miniPlayerShown = miniPlayerShownState,
                barFraction = { barFraction.value * (1f - navScrollState.hiddenFraction) },
                miniPlayerFraction = { miniPlayerFraction.value },
                miniPlayerSpanPx = miniPlayerSpanState,
                miniPlayerDock = { dock.value },
                miniPlayerDockShift = dockShiftState,
                dockedLift = dockedLiftState,
            )
        }
    return FlowBottomChrome(insets, miniBarBounds)
}
