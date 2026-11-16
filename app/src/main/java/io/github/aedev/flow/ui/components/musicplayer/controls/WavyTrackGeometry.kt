package io.github.aedev.flow.ui.components.musicplayer.controls

/**
 * Where a wavy seek bar's two parts sit, in the track's own pixels. M3's Slider insets its track by
 * half the thumb and centres the thumb at fraction × track width, so both parts are measured from
 * there: each round cap stops [gap] short of the thumb's edge, whatever the fraction.
 */
internal object WavyTrackGeometry {
    fun thumbCentre(
        trackWidth: Float,
        fraction: Float,
    ): Float = trackWidth * fraction.coerceIn(0f, 1f)

    /** Where the wave's line starts: half a stroke in, so its round cap starts at the track's start. */
    fun waveStart(stroke: Float): Float = stroke / 2f

    /** Where the wave's line ends, never before [waveStart]. */
    fun waveEnd(
        trackWidth: Float,
        fraction: Float,
        thumbWidth: Float,
        gap: Float,
        stroke: Float,
    ): Float = maxOf(waveStart(stroke), thumbCentre(trackWidth, fraction) - thumbWidth / 2f - gap - stroke / 2f)

    /** Where the straight rest of the track starts, mirroring [waveEnd] on the thumb's other side. */
    fun restStart(
        trackWidth: Float,
        fraction: Float,
        thumbWidth: Float,
        gap: Float,
        stroke: Float,
    ): Float = thumbCentre(trackWidth, fraction) + thumbWidth / 2f + gap + stroke / 2f

    fun trackEnd(
        trackWidth: Float,
        stroke: Float,
    ): Float = trackWidth - stroke / 2f

    /**
     * How much of its full height a wave this long gets. A wave of a few pixels at full height is one
     * steep hook that flips as the phase moves, so it grows in over its first [wavelength].
     */
    fun heightFactor(
        waveLength: Float,
        wavelength: Float,
    ): Float = if (wavelength <= 0f) 1f else (waveLength / wavelength).coerceIn(0f, 1f)
}
