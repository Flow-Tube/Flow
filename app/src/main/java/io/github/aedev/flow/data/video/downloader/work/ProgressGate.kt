package io.github.aedev.flow.data.video.downloader.work

/**
 * When a download's progress is worth writing down: once it has moved a whole percent or 16 MiB
 * since the last write, or finished (YouTube's offline progress gate), not on every tick.
 */
internal class ProgressGate {
    private var lastBytes = -1L
    private var lastPercent = -1L

    fun passes(
        done: Long,
        total: Long,
    ): Boolean {
        val percent = if (total > 0) done * PERCENT / total else 0L
        val moved =
            lastBytes < 0 || percent > lastPercent || done - lastBytes >= STEP_BYTES || (total > 0 && done >= total && lastBytes < total)
        if (moved) {
            lastBytes = done
            lastPercent = percent
        }
        return moved
    }

    private companion object {
        const val PERCENT = 100L
        const val STEP_BYTES = 16L * 1024 * 1024
    }
}
