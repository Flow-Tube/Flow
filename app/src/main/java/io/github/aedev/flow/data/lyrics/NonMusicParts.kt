package io.github.aedev.flow.data.lyrics

/** A stretch of a music video with no song playing, on the video's clock. */
internal data class NonMusicPart(
    val startMs: Long,
    val endMs: Long,
)

/** Lines this close to the edge of a part can still be on the video's clock: votes and lyrics are both a little loose. */
private const val EDGE_MARGIN_MS = 1_500L

/** One stray line isn't enough to say lyrics were timed to something else. */
private const val MIN_LINES_INSIDE = 2

/**
 * [entries] moved onto the video's clock, when they were timed to the song without the video's
 * non-music parts. Lines inside a part say they weren't on the video's clock, unless the song has a
 * spoken section or the part is marked too widely. Lyrics for the whole video then run past its end
 * once moved, so those stay. Lyrics already moved have no lines inside, so moving twice is harmless.
 */
internal fun onVideoClock(
    entries: List<LyricsEntry>,
    parts: List<NonMusicPart>,
    videoMs: Long,
): List<LyricsEntry> {
    val merged = merged(parts)
    if (videoMs <= 0L || !hasLinesInside(entries, merged)) return entries
    if (videoTime(entries.maxOf { it.time }, merged) > videoMs) return entries
    return entries.map { entry ->
        entry.copy(
            time = videoTime(entry.time, merged),
            words =
                entry.words?.map { word ->
                    word.copy(startTime = videoTime(word.startTime, merged), endTime = videoTime(word.endTime, merged))
                },
        )
    }
}

private fun merged(parts: List<NonMusicPart>): List<NonMusicPart> =
    parts
        .filter { it.endMs > it.startMs }
        .sortedBy { it.startMs }
        .fold(mutableListOf()) { merged, part ->
            val last = merged.lastOrNull()
            if (last != null && part.startMs <= last.endMs) {
                merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, part.endMs))
            } else {
                merged += part
            }
            merged
        }

/** Nobody sings in a part marked non-music, so lines there can't be on the video's clock. */
private fun hasLinesInside(
    entries: List<LyricsEntry>,
    parts: List<NonMusicPart>,
): Boolean =
    entries.count { entry ->
        !entry.isBackground &&
            entry.text.isNotBlank() &&
            parts.any { part ->
                val start = if (part.startMs == 0L) 0L else part.startMs + EDGE_MARGIN_MS
                entry.time in start..<part.endMs - EDGE_MARGIN_MS
            }
    } >= MIN_LINES_INSIDE

private fun videoTime(
    songMs: Long,
    parts: List<NonMusicPart>,
): Long =
    parts.fold(songMs) { videoMs, part ->
        if (videoMs >= part.startMs) videoMs + (part.endMs - part.startMs) else videoMs
    }
