package io.github.aedev.flow.ui.screens.settings.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvTouchSettingsTest {
    private val touchOnly =
        listOf(
            PlaybackGesturesIndex.doubleTapSeek,
            PlaybackGesturesIndex.seekZoneWidth,
            PlaybackGesturesIndex.brightnessGesture,
            PlaybackGesturesIndex.rememberBrightness,
            PlaybackGesturesIndex.volumeGesture,
            PlaybackGesturesIndex.volumeBoost,
            PlaybackGesturesIndex.speedSwipe,
            PlaybackGesturesIndex.speedSwipeStep,
            PlaybackGesturesIndex.seekGesture,
            PlaybackGesturesIndex.moreVideosGesture,
            PlaybackGesturesIndex.chapterSkipGesture,
            PlaybackIndex.longPressSpeed,
            PlaybackIndex.castButton,
            PlaybackIndex.lockButton,
            PlaybackIndex.musicSideSwipes,
            PlaybackIndex.lockScreenPlayer,
            AppearanceIndex.appIcon,
            AppearanceIndex.appLogo,
            AppearanceIndex.haptics,
            AppearanceIndex.hapticsIgnoreSystem,
            PlayerAppearanceIndex.gestureOverlay,
            PlayerAppearanceIndex.queueSwipeLeft,
            PlayerAppearanceIndex.queueSwipeRight,
            PlayerAppearanceIndex.miniBarSwipeLeft,
            PlayerAppearanceIndex.miniBarSwipeRight,
        )

    @Test
    fun `touch-only options can't be reached on a tv`() {
        touchOnly.forEach { assertThat(DestinationIndex.isReachable(it, tv = true)).isFalse() }
    }

    @Test
    fun `touch-only options stay on phones`() {
        touchOnly.forEach { assertThat(DestinationIndex.isReachable(it, tv = false)).isTrue() }
    }

    @Test
    fun `controls a remote can use stay on tv`() {
        listOf(PlaybackGesturesIndex.controlsWhileLoading, PlaybackIndex.captionsButton, AppearanceIndex.marquee)
            .forEach { assertThat(DestinationIndex.isReachable(it, tv = true)).isTrue() }
    }
}
