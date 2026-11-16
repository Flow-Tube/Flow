package io.github.aedev.flow.ui.components.videoplayer.controls

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerTopBarMetricsTest {
    @Test
    fun `the inline player's action row ends below its buttons`() {
        val bottom = playerTopBarActionRowBottom(isFullscreen = false, isPortraitFullscreen = false, cutoutTop = 24.dp)

        assertThat(bottom).isEqualTo(48.dp)
    }

    @Test
    fun `portrait fullscreen clears at least 16 dp above the row`() {
        val noCutout = playerTopBarActionRowBottom(isFullscreen = true, isPortraitFullscreen = true, cutoutTop = 0.dp)
        val tallCutout = playerTopBarActionRowBottom(isFullscreen = true, isPortraitFullscreen = true, cutoutTop = 40.dp)

        assertThat(noCutout).isEqualTo(72.dp)
        assertThat(tallCutout).isEqualTo(96.dp)
    }

    @Test
    fun `landscape fullscreen clears only the cutout`() {
        val bottom = playerTopBarActionRowBottom(isFullscreen = true, isPortraitFullscreen = false, cutoutTop = 0.dp)

        assertThat(bottom).isEqualTo(56.dp)
    }
}
