package io.github.aedev.flow.ui.screens.home

import androidx.compose.runtime.Immutable
import io.github.aedev.flow.player.stream.StoryboardLevel

/** Where the previewed card is: picked and dwelling, its media loading behind the thumbnail, playing on screen, or done. */
internal enum class HomePreviewPhase {
    SELECTED,
    POSITIONED,
    PLAYING,
    FINISHED,
}

@Immutable
internal data class HomePreviewState(
    val videoId: String? = null,
    val phase: HomePreviewPhase = HomePreviewPhase.SELECTED,
    val muted: Boolean = true,
    val buffering: Boolean = false,
    val playing: Boolean = false,
    val durationMs: Long = 0L,
    val hasCaptions: Boolean = false,
    val captionsOn: Boolean = false,
    val storyboard: List<StoryboardLevel> = emptyList(),
) {
    /** The thumbnail gives way only once a frame is on the surface, so a slow start never flashes black. */
    val showsVideo: Boolean get() = phase == HomePreviewPhase.PLAYING
}

internal object HomePreviewTiming {
    const val DWELL_MS = 1_000L

    /** The reference waits this long on 2G/3G; Flow reads a slow link from its reported bandwidth. */
    const val SLOW_DWELL_MS = 5_000L

    /** The stream is asked for this long before the dwell ends, so playback can start on time. */
    const val RESOLVE_LEAD_MS = 500L

    const val MAX_PREVIEW_MS = 60_000L

    fun dwellMs(slowNetwork: Boolean): Long = if (slowNetwork) SLOW_DWELL_MS else DWELL_MS

    fun resolveDelayMs(slowNetwork: Boolean): Long = (dwellMs(slowNetwork) - RESOLVE_LEAD_MS).coerceAtLeast(0L)

    /** Where a card selected again starts: where it stopped, unless that left nothing worth playing. */
    fun resumeAtMs(
        stoppedAtMs: Long?,
        durationMs: Long,
    ): Long {
        val at = stoppedAtMs ?: return 0L
        return if (durationMs > 0L && at >= durationMs - END_MARGIN_MS) 0L else at.coerceAtLeast(0L)
    }

    private const val END_MARGIN_MS = 3_000L
}
