package io.github.aedev.flow.ui.screens.player.stage

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.FullscreenSidePanelWidth
import io.github.aedev.flow.data.local.FullscreenSidePanelWidth.DEFAULT
import io.github.aedev.flow.data.local.FullscreenSidePanelWidth.NARROW
import io.github.aedev.flow.data.local.FullscreenSidePanelWidth.SMALL
import io.github.aedev.flow.data.local.FullscreenSidePanelWidth.WIDE
import org.junit.Test

class SidePanelWidthTest {
    private fun assertLayout(
        maxWidth: Dp,
        preset: FullscreenSidePanelWidth,
        panel: Float,
        minPlayer: Float,
    ) {
        assertThat(sidePanelWidth(maxWidth, preset).value).isWithin(TOLERANCE).of(panel)
        assertThat(sidePanelMinPlayerWidth(maxWidth, preset).value).isWithin(TOLERANCE).of(minPlayer)
    }

    @Test
    fun `a 732 dp phone floors the narrow panel at 240 dp and keeps the video beside it`() {
        assertLayout(732.dp, NARROW, panel = 240f, minPlayer = 492f)
        assertLayout(732.dp, SMALL, panel = 241.56f, minPlayer = 490.44f)
        assertLayout(732.dp, DEFAULT, panel = 307.44f, minPlayer = 424.56f)
        assertLayout(732.dp, WIDE, panel = 366f, minPlayer = 366f)
    }

    @Test
    fun `a 915 dp phone takes each share of the width`() {
        assertLayout(915.dp, NARROW, panel = 240f, minPlayer = 675f)
        assertLayout(915.dp, SMALL, panel = 301.95f, minPlayer = 613.05f)
        assertLayout(915.dp, DEFAULT, panel = 384.3f, minPlayer = 530.7f)
        assertLayout(915.dp, WIDE, panel = 457.5f, minPlayer = 457.5f)
    }

    @Test
    fun `a 1280 dp tablet caps each preset at its share of 420 dp`() {
        assertLayout(1280.dp, NARROW, panel = 250f, minPlayer = 960f)
        assertLayout(1280.dp, SMALL, panel = 330f, minPlayer = 857.6f)
        assertLayout(1280.dp, DEFAULT, panel = 420f, minPlayer = 742.4f)
        assertLayout(1280.dp, WIDE, panel = 500f, minPlayer = 640f)
    }

    @Test
    fun `the default preset matches the width the panel always had on a phone`() {
        val maxWidth = 915.dp
        assertThat(sidePanelWidth(maxWidth, DEFAULT)).isEqualTo(minOf(maxWidth * 0.42f, 420.dp))
    }

    @Test
    fun `a window too small for the floor gives the panel at most half of it`() {
        assertThat(sidePanelWidth(400.dp, NARROW).value).isWithin(TOLERANCE).of(200f)
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
