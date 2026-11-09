package io.github.aedev.flow.ui.components.layout.navigation

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.BottomAppBarScrollBehavior
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberBottomAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection

/**
 * Moves the bottom bar with the content's scroll: down and out while the user scrolls down, back up
 * on the way up, following the finger and settling on Material's own snap and fling, the same way
 * the chips under Home's top bar move. Material ships no scroll behaviour for the navigation bar,
 * so this reuses the bottom app bar's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
class FlowNavigationScrollState internal constructor(
    private val behavior: BottomAppBarScrollBehavior,
) {
    val nestedScrollConnection: NestedScrollConnection
        get() = behavior.nestedScrollConnection

    /** Flips once the bar is more hidden than shown; safe to read in composition. */
    val isBarVisible: Boolean by derivedStateOf { behavior.state.collapsedFraction < 0.5f }

    /** How far the bar has been scrolled away, 0 shown to 1 hidden. Read in layout or draw only. */
    val hiddenFraction: Float
        get() = behavior.state.collapsedFraction.coerceIn(0f, 1f)

    /** How far down the bar has been scrolled, in px. Read in layout or draw only. */
    val offsetPx: Float
        get() = -behavior.state.heightOffset

    internal fun setBarHeight(heightPx: Float) {
        val state = behavior.state
        state.heightOffsetLimit = -heightPx
        state.heightOffset = state.heightOffset.coerceAtLeast(-heightPx)
    }

    internal suspend fun show(animationSpec: AnimationSpec<Float>) {
        val state = behavior.state
        if (state.heightOffset == 0f) return
        animate(state.heightOffset, 0f, animationSpec = animationSpec) { value, _ -> state.heightOffset = value }
    }
}

/**
 * @param routeKey the bar comes back whenever this changes.
 * @param locked keeps the bar where it is, for surfaces that page vertically themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberFlowNavigationScrollState(
    hideOnScroll: Boolean,
    routeKey: String,
    locked: Boolean,
): FlowNavigationScrollState {
    val canScroll by rememberUpdatedState(hideOnScroll && !locked)
    val behavior =
        BottomAppBarDefaults.exitAlwaysScrollBehavior(
            state = rememberBottomAppBarState(),
            canScroll = remember { { canScroll } },
        )
    val state = remember(behavior) { FlowNavigationScrollState(behavior) }
    val showSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(state, routeKey, hideOnScroll) { state.show(showSpec) }
    return state
}
