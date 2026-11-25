package io.github.aedev.flow.ui.screens.player.state

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.IdleProgressBar
import io.github.aedev.flow.ui.components.shared.RestTrackHeight
import org.junit.Test

/**
 * Pins the media-sheet heights the player derives from one measured layout. The numbers are the
 * ones the sheet snaps between, so a regression here moves the sheet on screen.
 */
class PlayerMediaSheetGeometryTest {
    private val density = Density(2f)

    private fun heights(
        isFullscreen: Boolean = false,
        progressDrivenResize: Boolean = false,
        lockedCollapsedHeight: androidx.compose.ui.unit.Dp? = null,
        expandedPlayerBottom: androidx.compose.ui.unit.Dp = 300.dp,
        coversPlayer: Boolean = false,
        belowVideoBandPx: Float = 0f,
    ) = mediaSheetHeights(
        density = density,
        fullScreenHeightPx = 2000f,
        expandedPlayerBottom = expandedPlayerBottom,
        isFullscreen = isFullscreen,
        progressDrivenResize = progressDrivenResize,
        lockedCollapsedHeight = lockedCollapsedHeight,
        fallbackScreenHeight = 800.dp,
        statusBarHeightPx = 48f,
        playerWidthPx = 1080f,
        coversPlayer = coversPlayer,
        belowVideoBandPx = belowVideoBandPx,
    )

    @Test
    fun `a measured portrait player fills the space under it`() {
        val result = heights()

        assertThat(result.rawCollapsed).isEqualTo(700.dp)
        assertThat(result.collapsed).isEqualTo(0.dp)
        assertThat(result.expanded).isEqualTo(700.dp)
    }

    @Test
    fun `a fullscreen player floats the sheet at a fixed share of the screen`() {
        val result = heights(isFullscreen = true)

        assertThat(result.rawCollapsed).isEqualTo(700.dp)
        assertThat(result.collapsed).isEqualTo(0.dp)
        assertThat(result.expanded).isEqualTo(750.dp)
    }

    @Test
    fun `a scrolled-away player leaves the sheet everything below the status bar`() {
        val result = heights(coversPlayer = true)

        assertThat(result.collapsed).isEqualTo(0.dp)
        assertThat(result.expanded).isEqualTo(976.dp)
    }

    @Test
    fun `fullscreen keeps its own share even with the player scrolled away`() {
        assertThat(heights(isFullscreen = true, coversPlayer = true).expanded).isEqualTo(750.dp)
    }

    @Test
    fun `an unmeasured player falls back to three quarters of the screen`() {
        val result = heights(expandedPlayerBottom = 0.dp)

        assertThat(result.rawCollapsed).isEqualTo(0.dp)
        assertThat(result.collapsed).isEqualTo(0.dp)
        assertThat(result.expanded).isEqualTo(600.dp)
    }

    @Test
    fun `a resize-driven sheet expands to the sixteen-nine player bottom above its locked height`() {
        val result = heights(progressDrivenResize = true, lockedCollapsedHeight = 500.dp)

        assertThat(result.rawCollapsed).isEqualTo(700.dp)
        assertThat(result.collapsed).isEqualTo(500.dp)
        assertThat(result.expanded).isEqualTo(672.25.dp)
    }

    @Test
    fun `a resize-driven sheet with no lock yet collapses to the raw height`() {
        val result = heights(progressDrivenResize = true)

        assertThat(result.rawCollapsed).isEqualTo(700.dp)
        assertThat(result.collapsed).isEqualTo(700.dp)
        assertThat(result.expanded).isEqualTo(700.dp)
    }

    @Test
    fun `a panel shortens every video taller than 16 by 9`() {
        assertThat(panelShortensPlayer(4f / 3f)).isTrue()
        assertThat(panelShortensPlayer(1f)).isTrue()
        assertThat(panelShortensPlayer(9f / 16f)).isTrue()
        assertThat(panelShortensPlayer(16f / 9f)).isFalse()
        assertThat(panelShortensPlayer(2.39f)).isFalse()
    }

    @Test
    fun `a nearly 16 by 9 stream keeps its height under a panel`() {
        assertThat(panelShortensPlayer(426f / 240f)).isFalse()
        assertThat(panelShortensPlayer(1.75f)).isFalse()
    }

    @Test
    fun `a shortened 4 by 3 or square video is cropped and a vertical one keeps fitting`() {
        assertThat(panelCropsVideo(4f / 3f)).isTrue()
        assertThat(panelCropsVideo(1f)).isTrue()
        assertThat(panelCropsVideo(9f / 16f)).isFalse()
        assertThat(panelCropsVideo(16f / 9f)).isFalse()
    }

    @Test
    fun `only a below-video progress bar in a one-pane page reserves a band`() {
        assertThat(belowVideoBand(IdleProgressBar.BELOW_VIDEO, isTwoPaneWindow = false)).isEqualTo(RestTrackHeight)
        assertThat(belowVideoBand(IdleProgressBar.BELOW_VIDEO, isTwoPaneWindow = true)).isEqualTo(0.dp)
        assertThat(belowVideoBand(IdleProgressBar.ON_VIDEO, isTwoPaneWindow = false)).isEqualTo(0.dp)
        assertThat(belowVideoBand(IdleProgressBar.OFF, isTwoPaneWindow = false)).isEqualTo(0.dp)
    }

    @Test
    fun `a resize-driven sheet stops under the band at 16 by 9`() {
        val without = heights(progressDrivenResize = true, lockedCollapsedHeight = 100.dp)
        val with = heights(progressDrivenResize = true, lockedCollapsedHeight = 100.dp, belowVideoBandPx = 10f)

        assertThat(without.expanded - with.expanded).isEqualTo(5.dp)
    }
}
