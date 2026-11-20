package io.github.aedev.flow.ui.components.shorts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.data.shorts.SHORT_COMPLETE_FRACTION
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.shorts.ShortsPlayerPool
import io.github.aedev.flow.ui.screens.shorts.ShortsViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val POSITION_TICK_MS = 500L
private const val POSITION_WRITE_STEP_MS = 1_000L
private const val FIRST_HISTORY_TOUCH_MS = 1_500L
private const val HISTORY_SAVE_INTERVAL_MS = 5_000L
private const val ABANDON_POSITION_MS = 1_000L
private const val DWELL_SEED_MS = 8_000L
private const val AUTO_INTERVAL_MIN_SECONDS = 5
private const val AUTO_INTERVAL_MAX_SECONDS = 20

/**
 * Binds a reel page to its pooled player: attaching the surface, mirroring the player into
 * [pageState], recording history and engine signals, and the auto-advance modes.
 *
 * The player is observed through a [Player.Listener]; the only polling is a position tick that
 * exists while the reel is actually playing, so a paused or off-screen page costs no frames.
 */
@Composable
internal fun ShortsReelPlaybackEffects(
    playerPool: ShortsPlayerPool,
    playerView: PlayerView,
    short: ShortVideo,
    pageIndex: Int,
    isActive: Boolean,
    attachSurface: Boolean,
    settings: ShortsReelSettings,
    pageState: ShortsReelPageState,
    sessionState: ShortsReelSessionState,
    autoAdvanceState: ShortsReelAutoAdvanceState,
    sheetOpen: Boolean,
    viewModel: ShortsViewModel,
    onVideoEnded: () -> Unit,
    onAttachedPlayerChange: (Player?) -> Unit,
) {
    val context = LocalContext.current
    val ownershipGeneration by playerPool.ownershipGeneration.collectAsState()
    val latestSheetOpen by rememberUpdatedState(sheetOpen)

    LaunchedEffect(isActive, settings.playbackSpeed) {
        if (isActive) playerPool.setBasePlaybackSpeed(settings.playbackSpeed)
    }

    LaunchedEffect(isActive) {
        if (isActive) {
            playerPool.initialize(context)
            EnhancedMusicPlayerManager.pause()
        }
    }

    // Every page next to the playing one keeps its own player on its surface: the page leaving holds its
    // last frame, and a prepared neighbour has its first frame up before it slides in.
    LaunchedEffect(attachSurface, short.id, ownershipGeneration) {
        val player = if (attachSurface) playerPool.playerForAttach(pageIndex, short.id) else null
        playerView.player = player
        onAttachedPlayerChange(player)
        if (isActive && player?.isPlaying == true) pageState.hasStartedPlaying = true
    }

    LaunchedEffect(isActive, short.id) {
        if (isActive) pageState.hasStartedPlaying = false
    }

    val keepScreenOn = isActive && pageState.isPlaying
    LaunchedEffect(keepScreenOn) { playerView.keepScreenOn = keepScreenOn }

    fun advanceNow() {
        autoAdvanceState.deferredWhileSheetOpen = false
        autoAdvanceState.hasAutoAdvanced = true
        onVideoEnded()
    }

    fun requestAutoAdvance() {
        if (autoAdvanceState.hasAutoAdvanced) return
        if (latestSheetOpen) {
            autoAdvanceState.deferredWhileSheetOpen = true
            return
        }
        advanceNow()
    }

    LaunchedEffect(sheetOpen) {
        if (!sheetOpen && autoAdvanceState.deferredWhileSheetOpen && !autoAdvanceState.hasAutoAdvanced) advanceNow()
    }

    fun recordWatched(
        positionMs: Long,
        durationMs: Long,
    ) {
        if (sessionState.hasRecordedWatched) return
        sessionState.hasRecordedWatched = true
        viewModel.recordShortWatched(short, positionMs, durationMs)
    }

    fun recordProgress(
        positionMs: Long,
        durationMs: Long,
    ) {
        if (sessionState.hasRecordedWatched) return
        sessionState.hasTouchedHistory = true
        sessionState.lastProgressSavedAt = positionMs
        viewModel.recordShortProgress(short, positionMs, durationMs)
    }

    // Swiped away before the terminal watch fired: the abandonment is the engine's negative evidence.
    DisposableEffect(short.id, isActive) {
        onDispose {
            val position = pageState.currentPosition
            if (isActive && !sessionState.hasRecordedWatched && (pageState.hasStartedPlaying || position >= ABANDON_POSITION_MS)) {
                viewModel.recordShortProgress(short, position, pageState.duration)
                viewModel.recordShortAbandoned(short, position, pageState.duration)
            }
        }
    }

    DisposableEffect(isActive, pageIndex, short.id, ownershipGeneration, settings.playbackMode) {
        val player = playerPool.ownedPlayer(pageIndex, short.id)
        if (!isActive || player == null) return@DisposableEffect onDispose { }

        fun sync() {
            val playing = player.isPlaying
            if (pageState.isPlaying != playing) pageState.isPlaying = playing
            val buffering = player.playbackState == Player.STATE_BUFFERING
            if (pageState.isBuffering != buffering) pageState.isBuffering = buffering
            val duration = player.duration.coerceAtLeast(0L)
            if (duration != pageState.duration) pageState.duration = duration
            if (playing && !pageState.hasStartedPlaying) pageState.hasStartedPlaying = true
        }

        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = sync()

                override fun onPlaybackStateChanged(playbackState: Int) {
                    sync()
                    if (playbackState != Player.STATE_ENDED) return
                    val endedDuration = player.duration.coerceAtLeast(0L)
                    recordWatched(
                        positionMs = endedDuration.takeIf { it > 0L } ?: pageState.currentPosition,
                        durationMs = endedDuration,
                    )
                    if (settings.playbackMode == SHORTS_PLAYBACK_AUTO_NEXT || settings.playbackMode == SHORTS_PLAYBACK_AUTO_INTERVAL) {
                        requestAutoAdvance()
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    pageState.currentPosition = newPosition.positionMs.coerceAtLeast(0L)
                    // A looping Short never reaches STATE_ENDED; the wrap back to the start is its end,
                    // and the half-second tick can miss the last tenth after a seek.
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        val loopedDuration = player.duration.coerceAtLeast(0L)
                        if (loopedDuration > 0L) recordWatched(loopedDuration, loopedDuration)
                    }
                }
            }
        sync()
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(
        isActive,
        pageIndex,
        short.id,
        ownershipGeneration,
        pageState.isPlaying,
        settings.playbackMode,
        settings.autoScrollSeconds,
    ) {
        if (!isActive || !pageState.isPlaying) return@LaunchedEffect
        val player = playerPool.ownedPlayer(pageIndex, short.id) ?: return@LaunchedEffect
        while (true) {
            val position = player.currentPosition.coerceAtLeast(0L)
            val duration = player.duration.coerceAtLeast(0L)
            if (duration != pageState.duration) pageState.duration = duration
            if (
                pageState.currentPosition == 0L ||
                position < pageState.currentPosition ||
                abs(position - pageState.currentPosition) >= POSITION_WRITE_STEP_MS
            ) {
                pageState.currentPosition = position
            }

            if (!pageState.isDragging && !pageState.isBuffering) {
                if (!sessionState.hasTouchedHistory && position >= FIRST_HISTORY_TOUCH_MS) {
                    recordProgress(position, duration)
                } else if (sessionState.hasTouchedHistory && position - sessionState.lastProgressSavedAt >= HISTORY_SAVE_INTERVAL_MS) {
                    recordProgress(position, duration)
                }

                if (!sessionState.hasRecordedWatched && duration > 0L && position >= (duration * SHORT_COMPLETE_FRACTION).toLong()) {
                    recordWatched(position, duration)
                }

                if (!sessionState.hasReportedDwell && position >= DWELL_SEED_MS) {
                    sessionState.hasReportedDwell = true
                    viewModel.onReelDwelled(short)
                }

                if (settings.playbackMode == SHORTS_PLAYBACK_AUTO_INTERVAL && !autoAdvanceState.hasAutoAdvanced) {
                    val intervalMs = settings.autoScrollSeconds.coerceIn(AUTO_INTERVAL_MIN_SECONDS, AUTO_INTERVAL_MAX_SECONDS) * 1_000L
                    val shouldWaitForEnd = duration in 1..intervalMs
                    if (!shouldWaitForEnd && position >= intervalMs) {
                        recordWatched(position, duration.takeIf { it > 0L } ?: intervalMs)
                        requestAutoAdvance()
                    }
                }
            }
            delay(POSITION_TICK_MS)
        }
    }
}
