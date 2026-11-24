package io.github.aedev.flow.data.video.downloader.work

/**
 * Watches a transfer's byte count on each progress tick and calls [onStall] once it has not moved
 * for [ticks] ticks in a row, so a stream that stops sending ends instead of holding its row still.
 */
internal class StallLimit(
    private val ticks: Int,
    val onStall: () -> Unit,
) {
    private var lastBytes = Long.MIN_VALUE
    private var stillTicks = 0

    fun stillAt(bytes: Long): Boolean {
        if (bytes != lastBytes) {
            lastBytes = bytes
            stillTicks = 0
            return false
        }
        return ++stillTicks >= ticks
    }

    /** Starts the count over, for a pause that is not a stall. */
    fun reset() {
        lastBytes = Long.MIN_VALUE
        stillTicks = 0
    }
}
