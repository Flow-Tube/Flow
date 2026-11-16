package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsungLayerTest {
    @Test
    fun `the lock screen and mini player colour gets a layer`() {
        assertTrue(needsUnsungLayer(Color.White.copy(alpha = SYNCED_LINE_UNSUNG_ALPHA)))
    }

    @Test
    fun `an ink already blended onto the backdrop draws without one`() {
        val look = LyricsLineLook(textSize = 24f, lineSpacing = 1.2f, accent = Color.White, align = TextAlign.Start, backdrop = Color.Black)

        assertFalse(needsUnsungLayer(look.ink(SYNCED_LINE_UNSUNG_ALPHA)))
    }

    @Test
    fun `an ink with no backdrop to blend onto gets a layer`() {
        val look = LyricsLineLook(textSize = 24f, lineSpacing = 1.2f, accent = Color.White, align = TextAlign.Start, backdrop = null)

        assertTrue(needsUnsungLayer(look.ink(SYNCED_LINE_UNSUNG_ALPHA)))
    }
}
