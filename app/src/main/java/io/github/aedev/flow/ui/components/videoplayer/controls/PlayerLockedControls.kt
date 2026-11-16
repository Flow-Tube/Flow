package io.github.aedev.flow.ui.components.videoplayer.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.MediaSeekBar
import io.github.aedev.flow.ui.theme.PlayerLiveIndicator
import io.github.aedev.flow.ui.theme.PlayerScrim

/** Backdrop for the unlock button, a shade darker than a normal affordance so it reads as modal. */
private const val UNLOCK_AFFORDANCE_ALPHA = 0.42f

private val UnlockButtonSize = 44.dp
private val UnlockIconSize = 24.dp

@Composable
internal fun BoxScope.PlayerLockedControls(
    isOverlayVisible: Boolean,
    positionProvider: () -> Long,
    duration: Long,
    isLive: Boolean,
    isFullscreen: Boolean,
    showRemainingTime: Boolean,
    seekbarContent: PlayerSeekbarContent,
    pillHeight: Dp,
    topPadding: Dp,
    seekbarHorizontalPadding: Dp,
    seekbarBottomPadding: Dp,
    onRevealUnlock: () -> Unit,
    onUnlock: () -> Unit,
) {
    LockModeTouchShield(
        onRevealUnlock = onRevealUnlock,
        onUnlock = onUnlock,
        modifier = Modifier.matchParentSize(),
    )

    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    AnimatedVisibility(
        visible = isOverlayVisible,
        enter = fadeIn(animationSpec = fadeSpec),
        exit = fadeOut(animationSpec = fadeSpec),
        modifier = Modifier.align(Alignment.TopEnd),
    ) {
        PlayerPillIconButton(
            onClick = onUnlock,
            icon = Icons.Rounded.LockOpen,
            contentDescription = stringResource(R.string.player_unlock_controls),
            buttonSize = UnlockButtonSize,
            iconSize = UnlockIconSize,
            containerColor = PlayerScrim.copy(alpha = UNLOCK_AFFORDANCE_ALPHA),
            modifier =
                Modifier
                    .padding(top = topPadding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    AnimatedVisibility(
        visible = isOverlayVisible,
        enter = fadeIn(animationSpec = fadeSpec),
        exit = fadeOut(animationSpec = fadeSpec),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = seekbarHorizontalPadding)
                    .padding(bottom = seekbarBottomPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerTimePill(
                positionProvider = positionProvider,
                duration = duration,
                isLive = isLive,
                showRemainingTime = showRemainingTime,
                onClick = null,
                modifier =
                    Modifier
                        .height(pillHeight)
                        .align(Alignment.Start),
            )
            LockedSeekbar(
                positionProvider = positionProvider,
                duration = duration,
                isLive = isLive,
                isFullscreen = isFullscreen,
                content = seekbarContent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Read-only progress. Locking exists so a pocket or a passing hand cannot scrub, so this reports
 * position without accepting input.
 */
@Composable
private fun LockedSeekbar(
    positionProvider: () -> Long,
    duration: Long,
    isLive: Boolean,
    isFullscreen: Boolean,
    content: PlayerSeekbarContent,
    modifier: Modifier = Modifier,
) {
    if (isLive && duration <= 0L) {
        Box(
            modifier =
                modifier
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(PlayerLiveIndicator),
        )
        return
    }

    // Derived rather than read: a live timeline's duration is recomputed from the playhead, and a
    // plain read here would recompose the locked bar on every tick for a value that rarely moves.
    val seekDuration by remember(duration, isLive, positionProvider) {
        derivedStateOf { if (isLive) duration.coerceAtLeast(positionProvider()) else duration }
    }
    MediaSeekBar(
        value = {
            if (seekDuration > 0) {
                (positionProvider().toFloat() / seekDuration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        },
        onValueChange = {},
        enabled = false,
        chapters = content.chapters,
        sponsorSegments = content.sponsorSegments,
        sponsorColors = content.sponsorColors,
        commentMarks = content.commentMarks,
        duration = seekDuration,
        bufferedValue = content.bufferedPercentage,
        edgeAligned = !isFullscreen,
        modifier = modifier,
    )
}
