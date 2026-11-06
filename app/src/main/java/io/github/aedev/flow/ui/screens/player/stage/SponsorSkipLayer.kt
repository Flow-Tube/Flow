package io.github.aedev.flow.ui.screens.player.stage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.aedev.flow.data.local.SponsorBlockSkipButtonStyle
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.ui.components.videoplayer.overlay.SponsorBlockSkipButton
import io.github.aedev.flow.ui.components.videoplayer.placedWhen

/** The floating skip button, laid over the video area only and never over the body below it. */
@Composable
internal fun BoxScope.SponsorSkipLayer(
    session: VideoPlayerStageSession,
    sponsorSegments: List<SponsorBlockSegment>,
    expandedPlayerBottom: Dp,
    playerWidth: Dp,
    expandedSurfacesPlaced: () -> Boolean,
    style: SponsorBlockSkipButtonStyle,
    endPadding: Dp,
    bottomPadding: Dp,
) {
    val offForVideo by EnhancedPlayerManager.getInstance().sponsorBlockOffForVideo.collectAsState()
    if (offForVideo) return
    val skippedOnce by EnhancedPlayerManager.getInstance().sbSkippedOnce.collectAsState()
    val screenState = session.screenState
    val playerState = session.playerState

    val sponsorButtonPositionMs by remember {
        derivedStateOf { (screenState.currentPosition / 1_000L) * 1_000L }
    }
    val sponsorLayerModifier =
        if (screenState.isFullscreen || expandedPlayerBottom <= 0.dp) {
            Modifier.fillMaxHeight()
        } else {
            Modifier.height(expandedPlayerBottom)
        }
    Box(
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .width(playerWidth)
                .then(sponsorLayerModifier)
                .zIndex(3f)
                .placedWhen(expandedSurfacesPlaced),
    ) {
        SponsorBlockSkipButton(
            sponsorSegments = sponsorSegments,
            currentPositionMs = sponsorButtonPositionMs,
            categoryActions = EnhancedPlayerManager.getInstance().sbCategoryActions,
            skippedOnceUuids = skippedOnce,
            controlsVisible = screenState.showControls,
            playbackEnded = playerState.hasEnded,
            style = style,
            onSkipClick = { endPositionMs ->
                EnhancedPlayerManager.getInstance().skipToSegmentEnd(endPositionMs)
            },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = endPadding,
                        bottom = bottomPadding,
                    ),
        )
    }
}
