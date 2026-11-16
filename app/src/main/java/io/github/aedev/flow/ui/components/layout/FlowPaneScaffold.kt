package io.github.aedev.flow.ui.components.layout

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import io.github.aedev.flow.ui.utils.flowWindowDpSize

/**
 * The library's [androidx.compose.material3.adaptive.currentWindowAdaptiveInfo] (or its V2 form
 * with [breakpointsV2]), sized from [flowWindowDpSize] so the panes agree with the navigation
 * chrome after a rotation. Only the posture, and with it a foldable's hinge, is taken from the
 * library's own reading.
 */
@Composable
fun flowWindowAdaptiveInfo(breakpointsV2: Boolean = false): WindowAdaptiveInfo {
    val windowSize = flowWindowDpSize()
    val posture = currentWindowAdaptiveInfo().windowPosture
    return remember(windowSize, posture, breakpointsV2) {
        val breakpoints = if (breakpointsV2) WindowSizeClass.BREAKPOINTS_V2 else WindowSizeClass.BREAKPOINTS_V1
        val sizeClass = breakpoints.computeWindowSizeClass(windowSize.width.value, windowSize.height.value)
        WindowAdaptiveInfo(sizeClass, posture)
    }
}

/**
 * The library's pane directive for this window, including the hinge on a book-posture foldable,
 * with auto-focus off. On every navigation the scaffold would otherwise request focus on the
 * destination pane, which lands on its first text field and opens the keyboard.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun rememberFlowPaneScaffoldDirective(): PaneScaffoldDirective {
    val defaults = calculatePaneScaffoldDirective(flowWindowAdaptiveInfo(breakpointsV2 = true))
    return remember(defaults) {
        PaneScaffoldDirective(
            maxHorizontalPartitions = defaults.maxHorizontalPartitions,
            horizontalPartitionSpacerSize = defaults.horizontalPartitionSpacerSize,
            maxVerticalPartitions = defaults.maxVerticalPartitions,
            verticalPartitionSpacerSize = defaults.verticalPartitionSpacerSize,
            defaultPanePreferredWidth = defaults.defaultPanePreferredWidth,
            defaultPanePreferredHeight = defaults.defaultPanePreferredHeight,
            excludedBounds = defaults.excludedBounds,
            shouldAutoFocusCurrentDestination = false,
        )
    }
}
