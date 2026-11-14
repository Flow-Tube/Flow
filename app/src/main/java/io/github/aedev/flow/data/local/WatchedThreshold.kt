package io.github.aedev.flow.data.local

/**
 * How much of a video counts as watched. [isWatched] is the only definition: every watched video
 * filter reads history rows and applies it here rather than restating it in SQL. Shorts filters use
 * `finishedShortIds` instead (#979).
 *
 * [ALMOST_FINISHED] means the last minute of a long video, and at least 90 % of a short one, so a
 * one-minute Short is not watched the moment it starts.
 */
enum class WatchedThreshold(
    val minPercent: Float,
    val maxRemainingMs: Long,
) {
    PERCENT_90(90f, Long.MAX_VALUE),
    PERCENT_95(95f, Long.MAX_VALUE),
    PERCENT_99(99f, Long.MAX_VALUE),
    ALMOST_FINISHED(90f, 60_000L),
    ;

    fun isWatched(
        positionMs: Long,
        durationMs: Long,
    ): Boolean {
        if (positionMs <= 0L || durationMs <= 0L) return false
        val percent = positionMs.toFloat() / durationMs.toFloat() * 100f
        return percent >= minPercent && durationMs - positionMs <= maxRemainingMs
    }
}

/**
 * A row from a YouTube history import: Takeout lists what was watched but not how far, so the row
 * has neither a position nor a duration (#2060). A live stream opened in Flow looks the same.
 */
fun isImportedWatch(
    positionMs: Long,
    durationMs: Long,
): Boolean = positionMs == 0L && durationMs == 0L

/** [WatchedThreshold.isWatched], plus imported rows when the viewer counts those as watched. */
fun WatchedThreshold.isWatched(
    positionMs: Long,
    durationMs: Long,
    countImported: Boolean,
): Boolean = isWatched(positionMs, durationMs) || (countImported && isImportedWatch(positionMs, durationMs))
