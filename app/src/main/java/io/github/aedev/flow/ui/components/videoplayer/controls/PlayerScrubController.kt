package io.github.aedev.flow.ui.components.videoplayer.controls

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.aedev.flow.player.EnhancedPlayerManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val LIVE_SCRUB_SEEK_INTERVAL_MS = 80L
private const val LIVE_SCRUB_IMMEDIATE_DELTA_MS = 750L

/**
 * The scrub state of the player controls: the position the overlay should render, whether a scrub
 * is in flight, and the two callbacks every seek bar drives.
 */
internal class PlayerScrubController(
    val isScrubbing: Boolean,
    val displayedPosition: () -> Long,
    val onScrubProgress: (Float, Long) -> Unit,
    val onScrubFinished: () -> Unit,
)

/**
 * The expanded seek bar and the thin always-visible one drive the same scrub, so the throttling
 * and hand-off logic lives here once instead of being copied into both call sites.
 */
@Composable
internal fun rememberPlayerScrubController(
    currentPosition: () -> Long,
    isLive: Boolean,
    onSeek: (Long) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
): PlayerScrubController {
    val livePosition by rememberUpdatedState(currentPosition)
    val scrubScope = rememberCoroutineScope()

    var scrubPosition by remember { mutableStateOf<Long?>(null) }
    var isScrubbing by remember { mutableStateOf(false) }
    var lastScrubSeekAt by remember { mutableLongStateOf(0L) }
    var lastScrubSeekPosition by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var pendingScrubSeekJob by remember { mutableStateOf<Job?>(null) }

    val displayedPosition: () -> Long = { scrubPosition ?: livePosition() }

    val onScrubProgress: (Float, Long) -> Unit = { progress, seekDuration ->
        val newPosition = (progress * seekDuration).toLong()

        scrubPosition = newPosition

        if (!isScrubbing) {
            isScrubbing = true
            onScrubbingChange(true)
            EnhancedPlayerManager.getInstance().setScrubbingModeEnabled(true)
        }

        // Live scrubbing only previews; the seek itself is issued once the thumb is released.
        if (!isLive) {
            pendingScrubSeekJob?.cancel()

            val now = SystemClock.elapsedRealtime()
            val remainingDelay = (LIVE_SCRUB_SEEK_INTERVAL_MS - (now - lastScrubSeekAt)).coerceAtLeast(0L)
            val movedFarEnough =
                lastScrubSeekPosition == Long.MIN_VALUE ||
                    abs(newPosition - lastScrubSeekPosition) >= LIVE_SCRUB_IMMEDIATE_DELTA_MS

            if (remainingDelay == 0L || movedFarEnough) {
                onSeek(newPosition)
                lastScrubSeekAt = now
                lastScrubSeekPosition = newPosition
            } else {
                pendingScrubSeekJob =
                    scrubScope.launch {
                        delay(remainingDelay)
                        val targetPosition = scrubPosition ?: return@launch
                        onSeek(targetPosition)
                        lastScrubSeekAt = SystemClock.elapsedRealtime()
                        lastScrubSeekPosition = targetPosition
                    }
            }
        }
    }

    val onScrubFinished: () -> Unit = {
        pendingScrubSeekJob?.cancel()
        pendingScrubSeekJob = null
        val manager = EnhancedPlayerManager.getInstance()
        manager.finishScrubPreview()
        scrubPosition?.let { targetPosition ->
            // Read before seeking: the seek itself uses up a skip-once segment.
            val landing = manager.seekLandingFor(targetPosition)
            onSeek(targetPosition)
            scrubPosition = landing
            lastScrubSeekPosition = targetPosition
        }
        lastScrubSeekAt = 0L
        lastScrubSeekPosition = Long.MIN_VALUE
        isScrubbing = false
        onScrubbingChange(false)
        EnhancedPlayerManager.getInstance().setScrubbingModeEnabled(false)
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingScrubSeekJob?.cancel()
            onScrubbingChange(false)
            EnhancedPlayerManager.getInstance().setScrubbingModeEnabled(false)
        }
    }

    val pendingScrubTarget = scrubPosition
    if (pendingScrubTarget != null && !isScrubbing) {
        val settledPosition = livePosition()
        LaunchedEffect(settledPosition, pendingScrubTarget) {
            if (abs(settledPosition - pendingScrubTarget) <= 1_000L) {
                scrubPosition = null
            }
        }
    }

    return PlayerScrubController(
        isScrubbing = isScrubbing,
        displayedPosition = displayedPosition,
        onScrubProgress = onScrubProgress,
        onScrubFinished = onScrubFinished,
    )
}
