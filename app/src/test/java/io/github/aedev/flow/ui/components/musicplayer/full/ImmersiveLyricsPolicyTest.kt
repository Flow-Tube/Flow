package io.github.aedev.flow.ui.components.musicplayer.full

import io.github.aedev.flow.data.local.MusicPlayerBackgroundStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveLyricsPolicyTest {
    @Test
    fun `with the switch on, only the immersive background in the phone column shows lyrics in place`() {
        MusicPlayerBackgroundStyle.entries.forEach { style ->
            MusicPlayerLayout.entries.forEach { layout ->
                val expected = style == MusicPlayerBackgroundStyle.IMMERSIVE && layout == MusicPlayerLayout.COMPACT
                assertEquals("$style $layout", expected, lyricsShowInPlace(true, style, layout))
            }
        }
    }

    @Test
    fun `with the switch off, the lyrics button always opens the sheet`() {
        MusicPlayerBackgroundStyle.entries.forEach { style ->
            MusicPlayerLayout.entries.forEach { layout ->
                assertFalse("$style $layout", lyricsShowInPlace(false, style, layout))
            }
        }
    }

    @Test
    fun `lyrics in place run only while visible`() {
        assertTrue(inPlaceLyricsActive(expanded = true, inPlace = true, sheetOpen = false, queueCovers = false))
        assertFalse(inPlaceLyricsActive(expanded = false, inPlace = true, sheetOpen = false, queueCovers = false))
        assertFalse(inPlaceLyricsActive(expanded = true, inPlace = false, sheetOpen = false, queueCovers = false))
        assertFalse(inPlaceLyricsActive(expanded = true, inPlace = true, sheetOpen = true, queueCovers = false))
        assertFalse(inPlaceLyricsActive(expanded = true, inPlace = true, sheetOpen = false, queueCovers = true))
    }
}
