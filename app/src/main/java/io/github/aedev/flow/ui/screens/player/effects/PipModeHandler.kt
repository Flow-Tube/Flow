package io.github.aedev.flow.ui.screens.player.effects

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.BackgroundPlaybackPolicy
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.PictureInPictureHelper
import io.github.aedev.flow.player.videoHandlesPipNext
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel

private const val TAG = "PipModeHandler"

/**
 * Effect to register PiP broadcast receiver for play/pause controls
 */
@Composable
private fun PipBroadcastReceiverEffect(
    context: Context,
    onNext: () -> Unit,
    onBackground: () -> Unit,
) {
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnBackground by rememberUpdatedState(onBackground)
    DisposableEffect(Unit) {
        val receiver =
            PictureInPictureHelper.createPipActionReceiver(
                onPlay = { EnhancedPlayerManager.getInstance().play() },
                onPause = { EnhancedPlayerManager.getInstance().pause() },
                onNext = { if (videoHandlesPipNext(PictureInPictureHelper.shortsNavigation)) currentOnNext() },
                onClose = {
                    GlobalPlayerState.requestDismiss()
                    EnhancedPlayerManager.getInstance().stop()
                    EnhancedPlayerManager.getInstance().stopBackgroundService()
                },
                onBackground = { currentOnBackground() },
            )

        ContextCompat.registerReceiver(
            context,
            receiver,
            PictureInPictureHelper.getPipIntentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister PiP receiver", e)
            }
        }
    }
}

/**
 * Effect to update PiP params when playback state changes
 */
@Composable
private fun PipParamsUpdateEffect(
    isPlaying: Boolean,
    autoPipEnabled: Boolean,
    isBackgroundPlaybackMode: Boolean,
    videoAspectRatio: Float,
    hasNext: Boolean,
    activity: Activity?,
) {
    LaunchedEffect(isPlaying, autoPipEnabled, isBackgroundPlaybackMode, videoAspectRatio, hasNext) {
        val nextChanged = PictureInPictureHelper.videoHasNext != hasNext
        PictureInPictureHelper.setVideoHasNext(hasNext)
        // Related videos landing for a video behind Shorts must not overwrite the Shorts window's buttons.
        if (nextChanged && !videoHandlesPipNext(PictureInPictureHelper.shortsNavigation)) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
            val autoEnterEnabled =
                BackgroundPlaybackPolicy.shouldEnterAutoPip(
                    autoPipEnabled = autoPipEnabled,
                    isVideoPlaying = isPlaying,
                    explicitBackgroundPlaybackActive = GlobalPlayerState.isExplicitBackgroundPlaybackActive.value,
                    soundOnlyBackground = isBackgroundPlaybackMode,
                )
            PictureInPictureHelper.updatePipParams(
                activity = activity,
                aspectRatio = videoAspectRatio,
                isPlaying = isPlaying,
                autoEnterEnabled = autoEnterEnabled,
                offerBackground = true,
            )
        }
    }

    DisposableEffect(activity) {
        onDispose {
            PictureInPictureHelper.setVideoHasNext(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                PictureInPictureHelper.updatePipParams(
                    activity = activity,
                    aspectRatio = videoAspectRatio,
                    isPlaying = false,
                    autoEnterEnabled = false,
                )
            }
        }
    }
}

/**
 * Collects PiP preferences from DataStore
 */
@Composable
internal fun rememberPipPreferences(context: Context): PipPreferences {
    val preferences = remember(context) { PlayerPreferences(context) }
    val autoPipEnabled by preferences.autoPipEnabled.collectAsStateWithLifecycle(initialValue = false)
    val manualPipButtonEnabled by preferences.manualPipButtonEnabled.collectAsStateWithLifecycle(initialValue = true)

    return PipPreferences(
        autoPipEnabled = autoPipEnabled,
        manualPipButtonEnabled = manualPipButtonEnabled,
    )
}

/**
 * Data class holding PiP preferences
 */
internal data class PipPreferences(
    val autoPipEnabled: Boolean,
    val manualPipButtonEnabled: Boolean,
)

/**
 * All-in-one composable that sets up all PiP-related effects
 */
@Composable
internal fun SetupPipEffects(
    context: Context,
    activity: Activity?,
    isPlaying: Boolean,
    isBackgroundPlaybackMode: Boolean,
    videoAspectRatio: Float,
    pipPreferences: PipPreferences,
    hasNext: Boolean,
    playerViewModel: VideoPlayerViewModel,
) {
    // Register broadcast receiver
    PipBroadcastReceiverEffect(
        context = context,
        onNext = { playerViewModel.playNext() },
        // A PiP window can't be sent back; finishing closes it and onDestroy keeps the background audio.
        onBackground = {
            playerViewModel.startBackgroundPlayback()
            activity?.finish()
        },
    )

    // Update PiP params
    PipParamsUpdateEffect(
        isPlaying = isPlaying,
        autoPipEnabled = pipPreferences.autoPipEnabled,
        isBackgroundPlaybackMode = isBackgroundPlaybackMode,
        videoAspectRatio = videoAspectRatio,
        hasNext = hasNext,
        activity = activity,
    )
}
