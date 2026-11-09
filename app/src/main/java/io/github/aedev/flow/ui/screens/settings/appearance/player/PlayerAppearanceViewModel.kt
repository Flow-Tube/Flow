package io.github.aedev.flow.ui.screens.settings.appearance.player

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP
import io.github.aedev.flow.data.local.DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP
import io.github.aedev.flow.data.local.FullscreenSidePanelWidth
import io.github.aedev.flow.data.local.GestureOverlayStyle
import io.github.aedev.flow.data.local.IdleProgressBar
import io.github.aedev.flow.data.local.MiniBarSwipeAction
import io.github.aedev.flow.data.local.MusicPlainControlColors
import io.github.aedev.flow.data.local.MusicPlayerBackgroundStyle
import io.github.aedev.flow.data.local.PlayerOverlayPreferences
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.QueueSwipeAction
import io.github.aedev.flow.data.local.ScrubPreviewStyle
import io.github.aedev.flow.data.local.SeekbarPaddingMode
import io.github.aedev.flow.data.local.ShortsPlayerUiMode
import io.github.aedev.flow.data.local.SliderStyle
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import javax.inject.Inject

@HiltViewModel
class PlayerAppearanceViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        private val overlayDefaults = PlayerOverlayPreferences()

        val sliderStyle = preferences.sliderStyle.asState(SliderStyle.COMPACT)
        val portraitSeekbarMode = preferences.portraitSeekbarPaddingMode.asState(SeekbarPaddingMode.FULL_WIDTH)
        val portraitSeekbarCustom = preferences.portraitSeekbarCustomPaddingDp.asState(DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP)
        val fullscreenSeekbarMode = preferences.fullscreenSeekbarPaddingMode.asState(SeekbarPaddingMode.DEFAULT)
        val fullscreenSeekbarCustom = preferences.fullscreenSeekbarCustomPaddingDp.asState(DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP)
        val idleProgressBar = preferences.idleProgressBar.asState(overlayDefaults.idleProgressBar)
        val scrubPreview = preferences.scrubPreviewStyle.asState(overlayDefaults.scrubPreviewStyle)
        val frameStepButtons = preferences.frameStepButtonsEnabled.asState(overlayDefaults.frameStepButtonsEnabled)
        val musicBackground = preferences.musicPlayerBackgroundStyle.asState(MusicPlayerBackgroundStyle.BLUR_GRADIENT)
        val hideMusicArtwork = preferences.hideMusicPlayerArtwork.asState(false)
        val followLightTheme = preferences.musicPlayerFollowLightTheme.asState(false)
        val artworkControlColors = preferences.musicArtworkControlColors.asState(true)
        val animatedArtwork = preferences.animatedArtwork.asState(false)
        val animatedArtworkWifiOnly = preferences.animatedArtworkWifiOnly.asState(true)
        val plainControlColors = preferences.musicPlainControlColors.asState(MusicPlainControlColors.MONOCHROME)
        val sleepTimerButton = preferences.musicSleepTimerButton.asState(false)
        val adaptivePlayerSize = preferences.adaptivePlayerSizeEnabled.asState(true)
        val ambientMode = preferences.videoAmbientModeEnabled.asState(false)
        val videoArtworkColors = preferences.videoArtworkColors.asState(true)
        val groupedQualitySelector = preferences.groupedQualitySelectorEnabled.asState(false)
        val paidPromotionLabel = preferences.paidPromotionLabel.asState(false)
        val gestureOverlay = preferences.gestureOverlayStyle.asState(GestureOverlayStyle.CIRCULAR)
        val fullscreenSidePanelWidth = preferences.fullscreenSidePanelWidth.asState(FullscreenSidePanelWidth.DEFAULT)
        val shortsUiMode = preferences.shortsPlayerUiMode.asState(ShortsPlayerUiMode.DEFAULT)
        val miniPlayerScale = preferences.miniPlayerScale.asState(MiniPlayerSize.NORMAL.scale)
        val miniPlayerSkip = preferences.miniPlayerShowSkipControls.asState(false)
        val miniPlayerNextPrev = preferences.miniPlayerShowNextPrevControls.asState(false)
        val miniBarSwipeLeft = preferences.miniBarSwipeLeftAction.asState(MiniBarSwipeAction.CLOSE)
        val miniBarSwipeRight = preferences.miniBarSwipeRightAction.asState(MiniBarSwipeAction.CLOSE)
        val queueSwipeLeft = preferences.queueSwipeLeftAction.asState(QueueSwipeAction.PLAY_NEXT)
        val queueSwipeRight = preferences.queueSwipeRightAction.asState(QueueSwipeAction.REMOVE)

        fun setSliderStyle(value: SliderStyle) = write { preferences.setSliderStyle(value) }

        fun setPortraitSeekbarMode(value: SeekbarPaddingMode) = write { preferences.setPortraitSeekbarPaddingMode(value) }

        fun setPortraitSeekbarCustom(value: Int) = write { preferences.setPortraitSeekbarCustomPaddingDp(value) }

        fun setFullscreenSeekbarMode(value: SeekbarPaddingMode) = write { preferences.setFullscreenSeekbarPaddingMode(value) }

        fun setFullscreenSeekbarCustom(value: Int) = write { preferences.setFullscreenSeekbarCustomPaddingDp(value) }

        fun setIdleProgressBar(value: IdleProgressBar) = write { preferences.setIdleProgressBar(value) }

        fun setScrubPreview(value: ScrubPreviewStyle) = write { preferences.setScrubPreviewStyle(value) }

        fun setFrameStepButtons(value: Boolean) = write { preferences.setFrameStepButtonsEnabled(value) }

        fun setMusicBackground(value: MusicPlayerBackgroundStyle) = write { preferences.setMusicPlayerBackgroundStyle(value) }

        fun setHideMusicArtwork(value: Boolean) = write { preferences.setHideMusicPlayerArtwork(value) }

        fun setFollowLightTheme(value: Boolean) = write { preferences.setMusicPlayerFollowLightTheme(value) }

        fun setArtworkControlColors(value: Boolean) = write { preferences.setMusicArtworkControlColors(value) }

        fun setAnimatedArtwork(value: Boolean) = write { preferences.setAnimatedArtwork(value) }

        fun setAnimatedArtworkWifiOnly(value: Boolean) = write { preferences.setAnimatedArtworkWifiOnly(value) }

        fun setPlainControlColors(value: MusicPlainControlColors) = write { preferences.setMusicPlainControlColors(value) }

        fun setSleepTimerButton(value: Boolean) = write { preferences.setMusicSleepTimerButton(value) }

        fun setAdaptivePlayerSize(value: Boolean) = write { preferences.setAdaptivePlayerSizeEnabled(value) }

        fun setAmbientMode(value: Boolean) = write { preferences.setVideoAmbientModeEnabled(value) }

        fun setVideoArtworkColors(value: Boolean) = write { preferences.setVideoArtworkColors(value) }

        fun setGroupedQualitySelector(value: Boolean) = write { preferences.setGroupedQualitySelectorEnabled(value) }

        fun setPaidPromotionLabel(value: Boolean) = write { preferences.setPaidPromotionLabel(value) }

        fun setGestureOverlay(value: GestureOverlayStyle) = write { preferences.setGestureOverlayStyle(value) }

        fun setFullscreenSidePanelWidth(value: FullscreenSidePanelWidth) = write { preferences.setFullscreenSidePanelWidth(value) }

        fun setShortsUiMode(value: ShortsPlayerUiMode) = write { preferences.setShortsPlayerUiMode(value) }

        fun setMiniPlayerSize(value: MiniPlayerSize) = write { preferences.setMiniPlayerScale(value.scale) }

        fun setMiniPlayerSkip(value: Boolean) = write { preferences.setMiniPlayerShowSkipControls(value) }

        fun setMiniPlayerNextPrev(value: Boolean) = write { preferences.setMiniPlayerShowNextPrevControls(value) }

        fun setMiniBarSwipeLeft(value: MiniBarSwipeAction) = write { preferences.setMiniBarSwipeLeftAction(value) }

        fun setMiniBarSwipeRight(value: MiniBarSwipeAction) = write { preferences.setMiniBarSwipeRightAction(value) }

        fun setQueueSwipeLeft(value: QueueSwipeAction) = write { preferences.setQueueSwipeLeftAction(value) }

        fun setQueueSwipeRight(value: QueueSwipeAction) = write { preferences.setQueueSwipeRightAction(value) }
    }

/** The three mini player sizes the setting offers, stored as the player's scale factor. */
enum class MiniPlayerSize(
    val scale: Float,
) {
    SMALL(0.35f),
    NORMAL(0.45f),
    LARGE(0.55f),
    ;

    companion object {
        fun nearest(scale: Float): MiniPlayerSize = entries.minBy { kotlin.math.abs(it.scale - scale) }
    }
}
