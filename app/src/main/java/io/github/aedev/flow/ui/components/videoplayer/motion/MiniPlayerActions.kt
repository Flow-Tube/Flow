package io.github.aedev.flow.ui.components.videoplayer.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.videoplayer.PlayerDraggableState

/**
 * The mini player's own actions for accessibility services, which cannot drag, pinch or fling it:
 * open, close, larger or smaller, the next corner, and tuck or show. Empty while the player is not
 * mini. The booleans read here change only when a gesture or an action settles.
 */
@Composable
internal fun rememberMiniPlayerActions(
    state: PlayerDraggableState,
    handler: DraggablePlayerGestureHandler,
    onClose: () -> Unit,
): Modifier {
    val isMini by remember(state) { derivedStateOf { state.expandFraction.value > MINI_ACTIONS_FRACTION } }
    // Always a semantics node, so the video box's modifier chain keeps its shape mid-gesture.
    if (!isMini) return Modifier.semantics { }
    val open = stringResource(R.string.mini_player_action_open)
    val close = stringResource(R.string.close)
    val larger = stringResource(R.string.mini_player_action_larger)
    val smaller = stringResource(R.string.mini_player_action_smaller)
    val nextCorner = stringResource(R.string.mini_player_action_next_corner)
    val tuck = stringResource(R.string.mini_player_action_tuck)
    val show = stringResource(R.string.mini_player_show)
    val isTucked = state.tuckedSide != null
    val isWide = state.restingMiniScale >= state.wideMiniScale * WIDE_SIZE_SHARE
    return Modifier.semantics {
        customActions =
            if (isTucked) {
                listOf(
                    CustomAccessibilityAction(show) {
                        handler.untuck()
                        true
                    },
                    CustomAccessibilityAction(close) {
                        onClose()
                        true
                    },
                )
            } else {
                listOfNotNull(
                    CustomAccessibilityAction(open) {
                        state.expand()
                        true
                    },
                    CustomAccessibilityAction(close) {
                        onClose()
                        true
                    },
                    CustomAccessibilityAction(if (isWide) smaller else larger) {
                        handler.toggleWideMode()
                        true
                    },
                    if (isWide) {
                        null
                    } else {
                        CustomAccessibilityAction(nextCorner) {
                            handler.moveToNextCorner()
                            true
                        }
                    },
                    if (isWide) {
                        null
                    } else {
                        CustomAccessibilityAction(tuck) {
                            handler.tuckToNearestSide()
                            true
                        }
                    },
                )
            }
    }
}

private const val MINI_ACTIONS_FRACTION = 0.999f
