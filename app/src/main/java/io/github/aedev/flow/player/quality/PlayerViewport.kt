package io.github.aedev.flow.player.quality

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.aedev.flow.player.MiniPlayerExpansionState

/**
 * The viewport Auto picks a quality for. The mini player and a PiP window show a few hundred pixels,
 * so while either is up [ViewportTrackSelection] keeps new chunks to the rung that covers 480p. The
 * track selector itself always keeps the display's size: changing its parameters makes Media3 rebuild
 * the playing stream and drop what it buffered (#2257). A single-stream source has one rung and is
 * not affected.
 */
@UnstableApi
internal object PlayerViewport {
    private const val SMALL_LONG_SIDE = 854
    private const val SMALL_SHORT_SIDE = 480

    @Volatile
    var small = false
        private set

    fun isSmall(
        expansion: MiniPlayerExpansionState,
        inPip: Boolean,
        lowerQualityWhenSmall: Boolean,
    ): Boolean = lowerQualityWhenSmall && (inPip || expansion == MiniPlayerExpansionState.COLLAPSED)

    fun apply(small: Boolean) {
        this.small = small
    }

    /** Whether a frame of [width] by [height] fits the small viewport in either orientation; an unknown size fits. */
    fun fitsSmall(
        width: Int,
        height: Int,
    ): Boolean {
        if (width <= 0 || height <= 0) return true
        return maxOf(width, height) <= SMALL_LONG_SIDE && minOf(width, height) <= SMALL_SHORT_SIDE
    }

    /** The viewport every reset of the size constraints puts back. */
    fun DefaultTrackSelector.Parameters.Builder.applyViewport(): DefaultTrackSelector.Parameters.Builder =
        setViewportSizeToPhysicalDisplaySize(true)
}
