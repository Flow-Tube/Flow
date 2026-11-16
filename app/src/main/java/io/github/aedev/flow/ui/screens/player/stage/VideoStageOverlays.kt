package io.github.aedev.flow.ui.screens.player.stage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.videoplayer.UpcomingVideoOverlay
import io.github.aedev.flow.ui.components.videoplayer.gesture.PlayerSpeedBoost
import io.github.aedev.flow.ui.components.videoplayer.overlay.PaidPromotionChip
import io.github.aedev.flow.ui.components.videoplayer.overlay.PlayerGestureOverlays
import io.github.aedev.flow.ui.components.videoplayer.overlay.cutoutTopInset
import io.github.aedev.flow.ui.theme.PlayerScrim
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * The overlays that sit above the video but outside the pinch transform: gesture feedback, the
 * zoom read-out, a premiere countdown and the playback error card.
 */
@Composable
internal fun BoxScope.VideoStageOverlays(session: VideoPlayerStageSession) {
    val screenState = session.screenState
    val playerUiState = session.uiState
    val video = session.video
    val playerViewModel = session.viewModel
    val prefs = session.prefs

    PlayerGestureOverlays(
        screenState = screenState,
        allowVolumeBoost = prefs.allowVolumeBoost,
        style = prefs.gestureOverlayStyle,
        seekZoneFraction = prefs.doubleTapSeekZone.sideFraction,
        speedSwipeSide = prefs.speedSwipeSide,
        speedBoostSpeed =
            PlayerSpeedBoost.boostedPlaybackSpeed(
                currentSpeed = screenState.normalSpeed,
                targetSpeed = prefs.longPressPlaybackSpeed,
            ),
    )

    AnimatedVisibility(
        visible = screenState.showZoomIndicator,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .padding(
                    top =
                        (if (screenState.isFullscreen) 28.dp else 16.dp) +
                            cutoutTopInset(screenState.isFullscreenPortrait),
                ),
    ) {
        Surface(
            color = PlayerScrimPanel,
            shape = CircleShape,
        ) {
            Text(
                text = String.format(Locale.US, "%.1fx", screenState.zoomScale),
                style = MaterialTheme.typography.labelLarge,
                color = PlayerScrimContent,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    playerUiState.paidPromotion?.let { label ->
        var elapsed by remember(video.id) { mutableStateOf(false) }
        val visible = !elapsed && !screenState.showControls
        LaunchedEffect(video.id) {
            snapshotFlow { !screenState.showControls }.first { it }
            delay(label.durationMs)
            elapsed = true
        }
        PaidPromotionChip(
            text = label.text,
            visible = visible,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 12.dp + cutoutTopInset(screenState.isFullscreenPortrait)),
        )
    }
    if (playerUiState.isUpcoming) {
        UpcomingVideoOverlay(
            title = video.title,
            releaseTimeMs = playerUiState.upcomingReleaseTimeMs,
            isReminderSet = playerUiState.isUpcomingReminderSet,
            onToggleReminder = playerViewModel::toggleUpcomingReminder,
            modifier = Modifier.align(Alignment.Center),
        )
    }

    // ── Error overlay — icon + title, plus a retry in fullscreen ──
    // The body panel below the video carries the full recovery actions, but fullscreen does not
    // mount it, so without this a fullscreen failure is a dead end: a message and no way to act on
    // it short of guessing that leaving fullscreen reveals one.
    val errorMsg = playerUiState.error
    if (errorMsg != null && !playerUiState.isUpcoming) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(PlayerScrim.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier =
                    Modifier
                        .padding(horizontal = 32.dp)
                        .widthIn(max = 380.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = stringResource(R.string.ui_playback_error),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = errorMsg,
                    color = PlayerScrimContent,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                if (screenState.isFullscreen) {
                    Button(onClick = playerViewModel::retryLoadVideo) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                        Text(text = stringResource(R.string.retry))
                    }
                }
            }
        }
    }
}
