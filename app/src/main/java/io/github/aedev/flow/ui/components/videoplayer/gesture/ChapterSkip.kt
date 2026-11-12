package io.github.aedev.flow.ui.components.videoplayer.gesture

/** Past this far into a chapter, going back restarts it instead of opening the one before. */
internal const val CHAPTER_RESTART_WINDOW_MS = 3_000L

/** The chapter a skip lands on, as an index into [startsMs] (sorted), or null when there is none that way. */
internal fun chapterSkipTarget(
    startsMs: List<Long>,
    positionMs: Long,
    forward: Boolean,
): Int? {
    if (startsMs.isEmpty()) return null
    val current = startsMs.indexOfLast { it <= positionMs }
    if (forward) return (current + 1).takeIf { it < startsMs.size }
    if (current < 0) return null
    return if (current == 0 || positionMs - startsMs[current] > CHAPTER_RESTART_WINDOW_MS) current else current - 1
}
