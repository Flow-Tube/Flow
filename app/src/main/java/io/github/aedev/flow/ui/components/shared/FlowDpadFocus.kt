package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.zIndex

/**
 * True where a remote's D-pad moves focus instead of a finger, as on Android TV. Shared components
 * read it to lift the focused item and to keep one focus stop per item. It is false on phones and
 * tablets, so nothing there changes.
 */
val LocalDpadNavigation = staticCompositionLocalOf { false }

object FocusLiftDefaults {
    /** Cards and tiles: Google's TV focus scale for medium-sized items. */
    const val CARD_SCALE = 1.05f

    /** Icon buttons and other small targets, which need a larger step to read as focused. */
    const val SMALL_SCALE = 1.1f
}

/**
 * Scales the item up while it holds D-pad focus and raises it over its neighbours, the TV
 * counterpart of [pressScale]. A no-op unless [LocalDpadNavigation] is on.
 *
 * Pass the same [interactionSource] as the item's clickable. The scale is read inside
 * [graphicsLayer], so the animation never recomposes; only the z-order flips in composition.
 */
@Composable
fun Modifier.focusLift(
    interactionSource: InteractionSource,
    liftedScale: Float = FocusLiftDefaults.CARD_SCALE,
): Modifier {
    if (!LocalDpadNavigation.current) return this
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale =
        animateFloatAsState(
            targetValue = if (isFocused) liftedScale else 1f,
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "focusLift",
        )
    return zIndex(if (isFocused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
}

/**
 * Keeps a secondary target inside an item, such as a card's avatar or ⋮ button, out of D-pad focus,
 * so the remote moves card to card. On TV the item's long press or the Menu key reaches the same
 * actions.
 */
@Composable
fun Modifier.skipDpadFocus(): Modifier = if (LocalDpadNavigation.current) focusProperties { canFocus = false } else this

/**
 * Sends Down from a button on a bar laid over scrolling content into [target], that content. The
 * content runs under the bar, so its focus area starts above the bar's buttons and the D-pad finds
 * nothing below them. A no-op unless [LocalDpadNavigation] is on.
 */
@Composable
fun Modifier.dpadDownTo(target: FocusRequester): Modifier = if (LocalDpadNavigation.current) focusProperties { down = target } else this

/**
 * Lets a remote reach a shelf's full-width header from the row of cards under it. The D-pad picks the
 * nearest item by centre, and a card always lines up better with the cards of the next shelf than with
 * a header spanning the page, so the header was skipped both ways. Down into the row from above stops
 * at [header] first, and Up out of the row goes to it. A no-op unless [LocalDpadNavigation] is on.
 */
@Composable
fun Modifier.dpadShelfRow(
    header: FocusRequester,
    headerHasFocus: () -> Boolean,
): Modifier =
    if (LocalDpadNavigation.current) {
        focusProperties {
            onEnter = { if (requestedFocusDirection == FocusDirection.Down && !headerHasFocus()) header.requestFocus() }
            onExit = { if (requestedFocusDirection == FocusDirection.Up) header.requestFocus() }
        }.focusGroup()
    } else {
        this
    }

/**
 * Runs [onMenu] when the remote's Menu key is released while this item or something inside it holds
 * focus: the TV way to open an item's quick actions. A no-op unless [LocalDpadNavigation] is on.
 */
@Composable
fun Modifier.onMenuKey(onMenu: () -> Unit): Modifier =
    if (LocalDpadNavigation.current) {
        onKeyEvent { event ->
            if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
                onMenu()
                true
            } else {
                false
            }
        }
    } else {
        this
    }

/**
 * Makes a row or grid one focus group that, on a D-pad, hands focus back to the item that last held
 * it when focus comes back in, falling back to [fallback] (by default the nearest item) the first
 * time. A no-op unless [LocalDpadNavigation] is on.
 */
@Composable
fun Modifier.dpadFocusGroup(fallback: FocusRequester = FocusRequester.Default): Modifier =
    if (LocalDpadNavigation.current) focusRestorer(fallback).focusGroup() else this

/**
 * The item a remote should land on when its page comes back, such as the card a video was played from:
 * the full-screen player replaces the page, so the page's own focus memory is gone when it returns.
 */
class DpadReturnFocus {
    var key: String? by mutableStateOf(null)
}

/** Provided by a D-pad shell; null elsewhere, which turns [dpadReturnFocus] off. */
val LocalDpadReturnFocus = staticCompositionLocalOf<DpadReturnFocus?> { null }

/** Takes focus once when this item, identified by [key], is the one [LocalDpadReturnFocus] is waiting for. */
@Composable
fun Modifier.dpadReturnFocus(key: String): Modifier {
    val target = LocalDpadReturnFocus.current
    if (target == null || target.key != key) return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) {
        // Lazy lists place their items in the first layout pass, after this effect starts.
        withFrameNanos {}
        if (requester.requestFocus()) target.key = null
    }
    return focusRequester(requester)
}

/**
 * Focus for content that enters an [androidx.compose.animation.AnimatedContent] after its first frame:
 * the content it replaces leaves with whatever a remote had focused in it, so the new content takes
 * focus instead of it falling back to the first field on screen. Content shown from the start leaves
 * focus where it is. A no-op unless [LocalDpadNavigation] is on.
 */
@Composable
fun AnimatedVisibilityScope.dpadFocusOnEnter(): Modifier {
    if (!LocalDpadNavigation.current) return Modifier
    val requester = remember { FocusRequester() }
    val entering = remember { transition.currentState == EnterExitState.PreEnter }
    LaunchedEffect(requester) {
        if (!entering) return@LaunchedEffect
        // Lazy lists inside place their items in the first layout pass, after this effect starts.
        withFrameNanos {}
        requester.requestFocus()
    }
    return Modifier.focusRequester(requester).focusGroup()
}
