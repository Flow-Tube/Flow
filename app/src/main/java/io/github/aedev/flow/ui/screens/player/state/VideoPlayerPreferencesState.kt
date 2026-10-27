package io.github.aedev.flow.ui.screens.player.state

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.aedev.flow.data.local.DEFAULT_SPEED_SWIPE_STEP
import io.github.aedev.flow.data.local.DoubleTapSeekZone
import io.github.aedev.flow.data.local.GestureOverlayStyle
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.PlayerRelatedCardStyle
import io.github.aedev.flow.data.local.SpeedSwipeSide
import io.github.aedev.flow.player.stream.CaptionTrackResolver
import io.github.aedev.flow.ui.components.videoplayer.subtitle.SubtitleStyle

/**
 * One snapshot of every [PlayerPreferences] flow the video player overlay reads, plus the store
 * itself for the handful of writes the overlay performs. A fresh instance per composition keeps
 * the identity semantics of the individual `by collectAsState()` locals it replaced.
 */
@Stable
internal class VideoPlayerPreferencesState(
    val preferences: PlayerPreferences,
    val brightnessSwipeGesturesEnabled: Boolean,
    val rememberBrightnessEnabled: Boolean,
    val rememberedBrightnessLevel: Float,
    val volumeSwipeGesturesEnabled: Boolean,
    val seekSwipeGesturesEnabled: Boolean,
    val allowVolumeBoost: Boolean,
    val gestureOverlayStyle: GestureOverlayStyle,
    val hapticsEnabled: Boolean,
    val sbSubmitEnabled: Boolean,
    val sponsorBlockEnabled: Boolean,
    val doubleTapSeekSeconds: Int,
    val doubleTapSeekZone: DoubleTapSeekZone,
    val longPressPlaybackSpeed: Float,
    val speedSwipeSide: SpeedSwipeSide,
    val speedSwipeStep: Float,
    val disableShortsPlayer: Boolean,
    val showShortsPlayerPrompt: Boolean,
    val savedSubtitleStyle: SubtitleStyle,
    val rememberPlaybackSpeed: Boolean,
    val ambientModeEnabled: Boolean,
    val adaptivePlayerSizeEnabled: Boolean,
    val groupedQualitySelectorEnabled: Boolean,
    val lockModeEnabled: Boolean,
    val commentsEnabled: Boolean,
    val preferredSubtitleLanguage: String,
    val commentsPreviewEnabled: Boolean,
    val showRelatedVideos: Boolean,
    val relatedCardStyle: PlayerRelatedCardStyle,
    val feedColumns: HomeFeedColumns,
    val deArrowEnabled: Boolean,
    val tiltFullscreenRotationLocked: Boolean,
)

@Composable
internal fun rememberVideoPlayerPreferences(context: Context): VideoPlayerPreferencesState {
    val playerPreferences = remember { PlayerPreferences(context) }
    val brightnessSwipeGesturesEnabled by playerPreferences.brightnessSwipeGesturesEnabled.collectAsState(initial = true)
    val rememberBrightnessEnabled by playerPreferences.rememberBrightnessEnabled.collectAsState(initial = false)
    val rememberedBrightnessLevel by playerPreferences.rememberedBrightnessLevel.collectAsState(initial = -1f)
    val volumeSwipeGesturesEnabled by playerPreferences.volumeSwipeGesturesEnabled.collectAsState(initial = true)
    val seekSwipeGesturesEnabled by playerPreferences.seekSwipeGesturesEnabled.collectAsState(initial = true)
    val allowVolumeBoost by playerPreferences.allowVolumeBoost.collectAsState(initial = false)
    val gestureOverlayStyle by
        playerPreferences.gestureOverlayStyle.collectAsState(initial = GestureOverlayStyle.CIRCULAR)
    val hapticsEnabled by playerPreferences.playerHapticsEnabled.collectAsState(initial = true)
    val sbSubmitEnabled by playerPreferences.sbSubmitEnabled.collectAsState(initial = false)
    val sponsorBlockEnabled by playerPreferences.sponsorBlockEnabled.collectAsState(initial = false)
    val doubleTapSeekSeconds by playerPreferences.doubleTapSeekSeconds.collectAsState(initial = 10)
    val doubleTapSeekZone by playerPreferences.doubleTapSeekZone.collectAsState(initial = DoubleTapSeekZone.NORMAL)
    val longPressPlaybackSpeed by playerPreferences.longPressPlaybackSpeed.collectAsState(initial = 2.0f)
    val speedSwipeSide by playerPreferences.speedSwipeSide.collectAsState(initial = SpeedSwipeSide.OFF)
    val speedSwipeStep by playerPreferences.speedSwipeStep.collectAsState(initial = DEFAULT_SPEED_SWIPE_STEP)
    val disableShortsPlayer by playerPreferences.effectiveDisableShortsPlayer.collectAsState(initial = false)
    val showShortsPlayerPrompt by playerPreferences.showShortsPlayerPrompt.collectAsState(initial = true)
    val savedSubtitleStyle by playerPreferences.subtitleStyle.collectAsState(initial = SubtitleStyle())
    val rememberPlaybackSpeed by playerPreferences.rememberPlaybackSpeed.collectAsState(initial = false)
    val ambientModeEnabled by playerPreferences.videoAmbientModeEnabled.collectAsState(initial = false)
    val adaptivePlayerSizeEnabled by playerPreferences.adaptivePlayerSizeEnabled.collectAsState(initial = true)
    val groupedQualitySelectorEnabled by playerPreferences.groupedQualitySelectorEnabled.collectAsState(initial = false)
    val lockModeEnabled by playerPreferences.overlayLockModeEnabled.collectAsState(initial = false)
    val commentsEnabled by playerPreferences.commentsEnabled.collectAsState(initial = true)
    val preferredSubtitleLanguage by playerPreferences.preferredSubtitleLanguage
        .collectAsState(initial = CaptionTrackResolver.NO_PREFERRED_LANGUAGE)
    val commentsPreviewEnabled by playerPreferences.commentsPreviewEnabled.collectAsState(initial = true)
    val showRelatedVideos by playerPreferences.showRelatedVideos.collectAsState(initial = true)
    val relatedCardStyle by playerPreferences.playerRelatedCardStyle.collectAsState(initial = PlayerRelatedCardStyle.FULL_WIDTH)
    val feedColumns by playerPreferences.homeFeedColumns.collectAsState(initial = HomeFeedColumns.AUTO)
    val deArrowEnabled by playerPreferences.deArrowEnabled.collectAsState(initial = false)
    val tiltFullscreenRotationLocked by playerPreferences.tiltFullscreenRotationLocked.collectAsState(initial = false)

    return VideoPlayerPreferencesState(
        preferences = playerPreferences,
        brightnessSwipeGesturesEnabled = brightnessSwipeGesturesEnabled,
        rememberBrightnessEnabled = rememberBrightnessEnabled,
        rememberedBrightnessLevel = rememberedBrightnessLevel,
        volumeSwipeGesturesEnabled = volumeSwipeGesturesEnabled,
        seekSwipeGesturesEnabled = seekSwipeGesturesEnabled,
        allowVolumeBoost = allowVolumeBoost,
        gestureOverlayStyle = gestureOverlayStyle,
        hapticsEnabled = hapticsEnabled,
        sbSubmitEnabled = sbSubmitEnabled,
        sponsorBlockEnabled = sponsorBlockEnabled,
        doubleTapSeekSeconds = doubleTapSeekSeconds,
        doubleTapSeekZone = doubleTapSeekZone,
        longPressPlaybackSpeed = longPressPlaybackSpeed,
        speedSwipeSide = speedSwipeSide,
        speedSwipeStep = speedSwipeStep,
        disableShortsPlayer = disableShortsPlayer,
        showShortsPlayerPrompt = showShortsPlayerPrompt,
        savedSubtitleStyle = savedSubtitleStyle,
        rememberPlaybackSpeed = rememberPlaybackSpeed,
        ambientModeEnabled = ambientModeEnabled,
        adaptivePlayerSizeEnabled = adaptivePlayerSizeEnabled,
        groupedQualitySelectorEnabled = groupedQualitySelectorEnabled,
        lockModeEnabled = lockModeEnabled,
        commentsEnabled = commentsEnabled,
        preferredSubtitleLanguage = preferredSubtitleLanguage,
        commentsPreviewEnabled = commentsPreviewEnabled,
        showRelatedVideos = showRelatedVideos,
        relatedCardStyle = relatedCardStyle,
        feedColumns = feedColumns,
        deArrowEnabled = deArrowEnabled,
        tiltFullscreenRotationLocked = tiltFullscreenRotationLocked,
    )
}
