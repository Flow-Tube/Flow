package io.github.aedev.flow.ui.screens.player.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerResizeModeTest {
    private fun mode(
        isFullscreen: Boolean = true,
        spherical: Boolean = false,
        override: Int? = null,
        zoomToFill: Boolean = true,
        videoAspectRatio: Float = 16f / 9f,
        portraitLayout: Boolean = false,
    ) = effectiveResizeMode(isFullscreen, spherical, override, zoomToFill, videoAspectRatio, portraitLayout)

    @Test
    fun `the page player always fits, whatever the button or the setting say`() {
        assertThat(mode(isFullscreen = false)).isEqualTo(PlayerResizeMode.FIT)
        assertThat(mode(isFullscreen = false, override = PlayerResizeMode.ZOOM)).isEqualTo(PlayerResizeMode.FIT)
    }

    @Test
    fun `the 360 view always fits`() {
        assertThat(mode(spherical = true)).isEqualTo(PlayerResizeMode.FIT)
        assertThat(mode(spherical = true, override = PlayerResizeMode.FILL)).isEqualTo(PlayerResizeMode.FIT)
    }

    @Test
    fun `fullscreen fits with the setting off`() {
        assertThat(mode(zoomToFill = false)).isEqualTo(PlayerResizeMode.FIT)
    }

    @Test
    fun `the setting zooms a landscape video in landscape and a portrait video in portrait`() {
        assertThat(mode()).isEqualTo(PlayerResizeMode.ZOOM)
        assertThat(mode(videoAspectRatio = 9f / 16f, portraitLayout = true)).isEqualTo(PlayerResizeMode.ZOOM)
    }

    @Test
    fun `the setting leaves a video facing the other way fitted`() {
        assertThat(mode(portraitLayout = true)).isEqualTo(PlayerResizeMode.FIT)
        assertThat(mode(videoAspectRatio = 9f / 16f)).isEqualTo(PlayerResizeMode.FIT)
        assertThat(mode(videoAspectRatio = 1f)).isEqualTo(PlayerResizeMode.FIT)
        assertThat(mode(videoAspectRatio = 1f, portraitLayout = true)).isEqualTo(PlayerResizeMode.FIT)
    }

    @Test
    fun `the button's choice wins over the setting for the session`() {
        assertThat(mode(override = PlayerResizeMode.FIT)).isEqualTo(PlayerResizeMode.FIT)
        assertThat(mode(zoomToFill = false, override = PlayerResizeMode.FILL)).isEqualTo(PlayerResizeMode.FILL)
        assertThat(mode(portraitLayout = true, override = PlayerResizeMode.ZOOM)).isEqualTo(PlayerResizeMode.ZOOM)
    }

    @Test
    fun `next cycles fit fill zoom`() {
        assertThat(PlayerResizeMode.next(PlayerResizeMode.FIT)).isEqualTo(PlayerResizeMode.FILL)
        assertThat(PlayerResizeMode.next(PlayerResizeMode.FILL)).isEqualTo(PlayerResizeMode.ZOOM)
        assertThat(PlayerResizeMode.next(PlayerResizeMode.ZOOM)).isEqualTo(PlayerResizeMode.FIT)
    }
}
