package io.github.aedev.flow.ui.screens.player.effects

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FullscreenExitOrientationTest {
    private fun exit(
        leavingFullscreen: Boolean = true,
        isLandscape: Boolean = true,
        autoRotateOn: Boolean = true,
        isLargeWindow: Boolean = false,
        isSheetCollapsed: Boolean = false,
        portraitLockHeld: Boolean = false,
    ) = orientationAfterFullscreen(leavingFullscreen, isLandscape, autoRotateOn, isLargeWindow, isSheetCollapsed, portraitLockHeld)

    @Test
    fun `leaving fullscreen sideways on a phone holds portrait`() {
        assertThat(exit()).isEqualTo(FullscreenExitOrientation.PIN_PORTRAIT)
    }

    @Test
    fun `minimizing from fullscreen sideways follows the sensor`() {
        assertThat(exit(isSheetCollapsed = true)).isEqualTo(FullscreenExitOrientation.RELEASE)
    }

    @Test
    fun `rotation lock and large windows never hold portrait`() {
        assertThat(exit(autoRotateOn = false)).isEqualTo(FullscreenExitOrientation.RELEASE)
        assertThat(exit(isLargeWindow = true)).isEqualTo(FullscreenExitOrientation.RELEASE)
    }

    @Test
    fun `a resume that never left fullscreen keeps a held pin`() {
        assertThat(exit(leavingFullscreen = false, portraitLockHeld = true)).isEqualTo(FullscreenExitOrientation.KEEP)
        assertThat(exit(leavingFullscreen = false)).isEqualTo(FullscreenExitOrientation.RELEASE)
    }
}
