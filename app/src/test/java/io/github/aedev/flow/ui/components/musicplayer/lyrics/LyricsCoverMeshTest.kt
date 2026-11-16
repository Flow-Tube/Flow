package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.LyricsBackgroundStyle
import org.junit.Test

class LyricsCoverMeshTest {
    private fun contrastWithWhite(color: Color) = 1.05f / (color.luminance() + 0.05f)

    @Test
    fun `white text stays readable on an all white cover`() {
        val colors = lyricsCoverColors(IntArray(64) { Color.White.toArgb() })

        assertThat(contrastWithWhite(colors.brightest)).isAtLeast(7f)
    }

    @Test
    fun `a dark cover is only lightly darkened`() {
        assertThat(lyricsCoverScrim(IntArray(64) { Color(0xFF202030).toArgb() })).isWithin(0.001f).of(0.3f)
    }

    @Test
    fun `the brightest cell is what text is measured against`() {
        val colors = lyricsCoverColors(intArrayOf(Color.Black.toArgb(), Color.White.toArgb(), Color.Black.toArgb(), Color.Black.toArgb()))

        assertThat(colors.brightest).isEqualTo(Color(colors.cells[1]))
        assertThat(contrastWithWhite(colors.brightest)).isAtLeast(7f)
    }

    @Test
    fun `the mesh keeps the corner colors and stays opaque`() {
        val red = Color.Red.toArgb()
        val blue = Color.Blue.toArgb()
        val mesh = lyricsCoverMesh(intArrayOf(red, blue, blue, red), side = 2, size = 16)

        assertThat(mesh).hasLength(256)
        assertThat(mesh[0]).isEqualTo(red)
        assertThat(mesh[15]).isEqualTo(blue)
        assertThat(mesh[255]).isEqualTo(red)
        assertThat(mesh.all { it ushr 24 == 0xFF }).isTrue()
    }

    @Test
    fun `an unknown stored style reads as solid`() {
        assertThat(
            io.github.aedev.flow.data.local.LyricsBackgroundStyle
                .fromStored("NEON"),
        ).isEqualTo(LyricsBackgroundStyle.SOLID)
    }
}
