package io.github.aedev.flow.player.quality

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.aedev.flow.player.MiniPlayerExpansionState

/**
 * The viewport Auto picks a quality for. The mini player and a PiP window show a few hundred pixels,
 * so while either is up Media3 keeps to the rung that covers 480p instead of the whole display.
 * A single-stream source has one rung and is not affected.
 */
@UnstableApi
internal object PlayerViewport {
    private const val SMALL_WIDTH = 854
    private const val SMALL_HEIGHT = 480

    @Volatile
    private var small = false

    fun isSmall(
        expansion: MiniPlayerExpansionState,
        inPip: Boolean,
    ): Boolean = inPip || expansion == MiniPlayerExpansionState.COLLAPSED

    fun apply(
        selector: DefaultTrackSelector,
        small: Boolean,
    ) {
        this.small = small
        selector.setParameters(selector.buildUponParameters().applyViewport())
    }

    /** The viewport for the player's current size; every reset of the size constraints goes through here. */
    fun DefaultTrackSelector.Parameters.Builder.applyViewport(): DefaultTrackSelector.Parameters.Builder =
        if (small) setViewportSize(SMALL_WIDTH, SMALL_HEIGHT, true) else setViewportSizeToPhysicalDisplaySize(true)
}
