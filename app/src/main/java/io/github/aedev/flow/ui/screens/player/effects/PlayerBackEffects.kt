package io.github.aedev.flow.ui.screens.player.effects

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.aedev.flow.ui.components.videoplayer.PlayerDraggableState
import io.github.aedev.flow.ui.components.videoplayer.PlayerSheetValue
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import io.github.aedev.flow.ui.utils.LocalWindowIsLandscape
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Share of the slide to the page that a full Back gesture scrubs before it is released. */
private const val PORTRAIT_BACK_REACH = 0.3f

/**
 * Back leaves fullscreen, then scrubs the expanded sheet down with the finger and collapses it on
 * commit. While the player is touch-locked, back only reveals the lock; it is registered last so it
 * wins over both.
 */
@Composable
internal fun PlayerBackEffects(
    playerSheetState: PlayerDraggableState,
    screenState: PlayerScreenState,
    isInPipMode: Boolean,
) {
    // Portrait fullscreen leaves by sliding within the window, so Back can scrub that slide; landscape
    // has to wait for the window to turn and keeps the instant exit.
    val isLandscape = LocalWindowIsLandscape.current
    val slidesOut = screenState.isFullscreen && screenState.isFullscreenPortrait && !isLandscape && !isInPipMode
    BackHandler(enabled = screenState.isFullscreen && !slidesOut) {
        screenState.isFullscreen = false
        screenState.isFullscreenPortrait = false
    }

    var portraitBackInFlight by remember { mutableStateOf(false) }
    val springBack = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    PredictiveBackHandler(enabled = slidesOut || portraitBackInFlight) { progress ->
        portraitBackInFlight = true
        val reach = playerSheetState.portraitFsTravel * PORTRAIT_BACK_REACH
        try {
            progress.collect { event -> screenState.exitDragOffsetY = -event.progress * reach }
            playerSheetState.exitPortraitFullscreenFrom(screenState.exitDragOffsetY, 0f)
            screenState.isFullscreen = false
            screenState.isFullscreenPortrait = false
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                animate(screenState.exitDragOffsetY, 0f, animationSpec = springBack) { value, _ ->
                    screenState.exitDragOffsetY = value
                }
            }
            throw e
        } finally {
            portraitBackInFlight = false
        }
    }

    // Stays enabled for the whole scrub: the sheet's currentValue flips to Collapsed halfway
    // through, and disabling the handler mid-gesture would cancel it as if the user had backed out.
    var backScrubInFlight by remember { mutableStateOf(false) }
    PredictiveBackHandler(
        enabled =
            (playerSheetState.currentValue == PlayerSheetValue.Expanded || backScrubInFlight) &&
                !isInPipMode &&
                !screenState.isFullscreen,
    ) { progress ->
        backScrubInFlight = true
        try {
            playerSheetState.beginBackScrub()
            progress.collect { event -> playerSheetState.scrubBack(event.progress) }
            playerSheetState.collapse()
        } catch (e: CancellationException) {
            playerSheetState.expand()
            // Returning normally from here, before the flow was collected, makes activity-compose
            // throw "You must collect the progress flow" (#1307).
            throw e
        } finally {
            backScrubInFlight = false
        }
    }

    BackHandler(enabled = screenState.isTouchLocked && !isInPipMode) {
        screenState.revealLockOverlay()
        screenState.onInteraction()
    }
}
