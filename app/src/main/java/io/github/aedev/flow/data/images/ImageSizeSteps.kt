package io.github.aedev.flow.data.images

/**
 * Pixel sizes asked of Google's image CDN. Drawn sizes round up to the next step, so cards of
 * nearly the same size share one URL and one cache entry.
 */
object ImageSizeSteps {
    private val STEPS = intArrayOf(128, 192, 256, 384, 512, 768, 1024, 1536, 2048)

    /** [px] rounded up to a step, capped at the largest; [unknown] when [px] is zero or less. */
    fun roundUp(
        px: Int,
        unknown: Int,
    ): Int {
        if (px <= 0) return unknown
        return STEPS.firstOrNull { it >= px } ?: STEPS.last()
    }
}
