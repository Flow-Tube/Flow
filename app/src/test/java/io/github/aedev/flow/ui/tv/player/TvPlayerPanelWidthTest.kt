package io.github.aedev.flow.ui.tv.player

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.ui.screens.player.state.PlayerSheet
import org.junit.Test

class TvPlayerPanelWidthTest {
    private val full = 400.dp

    @Test
    fun `settings take under a third of a 1080p tv`() {
        assertThat(tvPlayerPanelWidth(PlayerSheet.Settings(), screenWidth = 960.dp, fullWidth = full)).isEqualTo(288.dp)
        assertThat(tvPlayerPanelWidth(PlayerSheet.SleepTimer, screenWidth = 960.dp, fullWidth = full)).isEqualTo(288.dp)
    }

    @Test
    fun `a narrow screen keeps the panel readable`() {
        assertThat(tvPlayerPanelWidth(PlayerSheet.Queue, screenWidth = 640.dp, fullWidth = full)).isEqualTo(288.dp)
    }

    @Test
    fun `a wide screen never goes past the full panel`() {
        assertThat(tvPlayerPanelWidth(PlayerSheet.Settings(), screenWidth = 1600.dp, fullWidth = full)).isEqualTo(full)
    }

    @Test
    fun `panels for reading keep the full width`() {
        listOf(PlayerSheet.Description, PlayerSheet.Chapters, PlayerSheet.Transcript, PlayerSheet.Comments(fullscreen = true))
            .forEach { assertThat(tvPlayerPanelWidth(it, screenWidth = 960.dp, fullWidth = full)).isEqualTo(full) }
    }
}
