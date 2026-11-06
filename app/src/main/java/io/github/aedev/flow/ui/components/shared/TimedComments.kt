package io.github.aedev.flow.ui.components.shared

import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.RichTextTarget

private const val MILLIS_PER_SECOND = 1_000L

/** Where a comment first points in its video, in seconds, or null when it points nowhere. */
fun Comment.firstTimestampSeconds(): Long? =
    richText
        ?.spans
        ?.filter { it.target is RichTextTarget.Timestamp }
        ?.minByOrNull { it.start }
        ?.let { (it.target as RichTextTarget.Timestamp).seconds }

/**
 * The comments with a timestamp, latest position first, the most liked first at the same position.
 * A pinned comment takes its place like any other. The server also marks times past the end of the
 * video as positions, so those go once [durationMs] is known.
 */
fun timedCommentsInOrder(
    comments: List<Comment>,
    durationMs: Long,
): List<Comment> =
    comments
        .mapNotNull { comment ->
            comment
                .firstTimestampSeconds()
                ?.takeIf { durationMs <= 0L || it * MILLIS_PER_SECOND <= durationMs }
                ?.let { seconds -> comment to seconds }
        }.sortedWith(compareByDescending<Pair<Comment, Long>> { it.second }.thenByDescending { it.first.likeCount })
        .map { it.first }

/** The index of the latest timestamp already passed at [positionMs], or -1 before the first one. */
fun timedAnchorIndex(
    secondsLatestFirst: List<Long>,
    positionMs: Long,
): Int {
    val positionSeconds = positionMs / MILLIS_PER_SECOND
    var low = 0
    var high = secondsLatestFirst.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (secondsLatestFirst[middle] <= positionSeconds) high = middle else low = middle + 1
    }
    return low.takeIf { it < secondsLatestFirst.size } ?: -1
}
