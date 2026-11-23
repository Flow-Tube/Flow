package io.github.aedev.flow.data.video

/** Videos kept before the current one when a long queue is saved. */
private const val KEPT_BEFORE = 50

/** The most videos a saved queue holds; a playlist queue reloads the rest from its source. */
internal const val SAVED_QUEUE_LIMIT = 500

/**
 * The part of a queue that is saved: [start] until [end] around the current video. A queue still
 * loading its playlist keeps its source only when the saved part reaches the end of what has loaded,
 * since the source carries on from there.
 */
internal data class SavedQueueWindow(
    val start: Int,
    val end: Int,
    val keepsSource: Boolean,
) {
    companion object {
        fun around(
            size: Int,
            index: Int,
            hasSource: Boolean,
        ): SavedQueueWindow {
            val start = (index - KEPT_BEFORE).coerceIn(0, (size - 1).coerceAtLeast(0))
            val end = (start + SAVED_QUEUE_LIMIT).coerceAtMost(size)
            return SavedQueueWindow(start, end, keepsSource = hasSource && end == size)
        }
    }
}
