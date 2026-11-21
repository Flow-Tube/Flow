package io.github.aedev.flow.ui.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.ui.components.videoplayer.overlay.SponsorBlockSkipButton

/**
 * The TV player's skip button. It reads the position itself, a second at a time, so the player
 * screen does not recompose on every position tick; a video with no segments reads nothing.
 */
@Composable
internal fun TvSponsorSkipButton(
    sponsorSegments: List<SponsorBlockSegment>,
    positionMs: () -> Long,
    controlsVisible: Boolean,
    playbackEnded: Boolean,
    modifier: Modifier = Modifier,
) {
    if (sponsorSegments.isEmpty()) return
    val manager = remember { EnhancedPlayerManager.getInstance() }
    val skippedOnce by manager.sbSkippedOnce.collectAsStateWithLifecycle()
    val position by rememberUpdatedState(positionMs)
    val currentPositionMs by remember { derivedStateOf { (position() / 1_000L) * 1_000L } }
    SponsorBlockSkipButton(
        sponsorSegments = sponsorSegments,
        currentPositionMs = currentPositionMs,
        categoryActions = manager.sbCategoryActions,
        skippedOnceUuids = skippedOnce,
        controlsVisible = controlsVisible,
        playbackEnded = playbackEnded,
        onSkipClick = manager::skipToSegmentEnd,
        modifier = modifier,
    )
}
