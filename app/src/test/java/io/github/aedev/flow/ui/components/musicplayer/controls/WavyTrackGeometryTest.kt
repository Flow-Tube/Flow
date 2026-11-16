package io.github.aedev.flow.ui.components.musicplayer.controls

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WavyTrackGeometryTest {
    private val width = 1000f
    private val stroke = 15f
    private val thumb = 15f
    private val gap = 0f
    private val wavelength = 96f

    private fun waveEnd(fraction: Float) = WavyTrackGeometry.waveEnd(width, fraction, thumb, gap, stroke)

    private fun capEnd(fraction: Float) = waveEnd(fraction) + stroke / 2f

    private fun thumbLeadingEdge(fraction: Float) = WavyTrackGeometry.thumbCentre(width, fraction) - thumb / 2f

    @Test
    fun `the thumb centre is the fraction of the full track width`() {
        assertThat(WavyTrackGeometry.thumbCentre(width, 0f)).isEqualTo(0f)
        assertThat(WavyTrackGeometry.thumbCentre(width, 0.5f)).isEqualTo(500f)
        assertThat(WavyTrackGeometry.thumbCentre(width, 1f)).isEqualTo(1000f)
        assertThat(WavyTrackGeometry.thumbCentre(width, 1.5f)).isEqualTo(1000f)
    }

    @Test
    fun `no wave at the very start`() {
        assertThat(waveEnd(0f)).isEqualTo(WavyTrackGeometry.waveStart(stroke))
        assertThat(waveEnd(0.001f)).isEqualTo(WavyTrackGeometry.waveStart(stroke))
    }

    @Test
    fun `the wave's cap never passes the thumb's leading edge`() {
        for (step in 0..1000) {
            val fraction = step / 1000f
            if (waveEnd(fraction) > WavyTrackGeometry.waveStart(stroke)) {
                assertThat(capEnd(fraction)).isAtMost(thumbLeadingEdge(fraction))
            }
        }
    }

    @Test
    fun `the wave stops the gap short of the thumb in the middle and at the end`() {
        val withGap = { fraction: Float -> WavyTrackGeometry.waveEnd(width, fraction, thumb, 12f, stroke) + stroke / 2f }
        assertThat(withGap(0.5f)).isEqualTo(500f - thumb / 2f - 12f)
        assertThat(withGap(1f)).isEqualTo(1000f - thumb / 2f - 12f)
        assertThat(capEnd(0.5f)).isEqualTo(thumbLeadingEdge(0.5f))
    }

    @Test
    fun `the rest of the track starts past the thumb's trailing edge`() {
        val restCapStart = WavyTrackGeometry.restStart(width, 0.5f, thumb, gap, stroke) - stroke / 2f
        assertThat(restCapStart).isEqualTo(500f + thumb / 2f)
        assertThat(WavyTrackGeometry.restStart(width, 1f, thumb, gap, stroke)).isGreaterThan(WavyTrackGeometry.trackEnd(width, stroke))
    }

    @Test
    fun `the wave grows in over its first wavelength`() {
        assertThat(WavyTrackGeometry.heightFactor(0f, wavelength)).isEqualTo(0f)
        assertThat(WavyTrackGeometry.heightFactor(wavelength / 2f, wavelength)).isEqualTo(0.5f)
        assertThat(WavyTrackGeometry.heightFactor(wavelength, wavelength)).isEqualTo(1f)
        assertThat(WavyTrackGeometry.heightFactor(wavelength * 10f, wavelength)).isEqualTo(1f)
        assertThat(WavyTrackGeometry.heightFactor(-5f, wavelength)).isEqualTo(0f)
    }
}
