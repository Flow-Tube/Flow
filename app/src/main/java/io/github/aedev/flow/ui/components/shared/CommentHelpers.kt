package io.github.aedev.flow.ui.components.shared

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.innertube.pages.VideoCommentSort
import io.github.aedev.flow.utils.formatTimeAgo
import io.github.aedev.flow.utils.parseTimestampMs

enum class CommentSortFilter(
    @param:StringRes val labelRes: Int,
) {
    TOP(R.string.filter_top),
    NEWEST(R.string.filter_newest),
    OLDEST(R.string.filter_oldest),
    TIMED(R.string.filter_timed),
}

/**
 * The continuation that serves [filter], out of the orders the section offered.
 *
 * The menu is matched by position rather than by title, because the titles arrive in the user's
 * language. YouTube's web section serves two orders and no third: Oldest and Timed read the
 * chronological one and arrange what they have, which is why they map to the same continuation as
 * Newest.
 */
fun videoCommentSortFor(
    options: List<VideoCommentSort>,
    filter: CommentSortFilter,
): VideoCommentSort? =
    when (filter) {
        CommentSortFilter.TOP -> options.getOrNull(0)
        CommentSortFilter.NEWEST, CommentSortFilter.OLDEST, CommentSortFilter.TIMED -> options.getOrNull(1)
    }

/** The orders Timed reads, in the order it reads them: Newest usually has more timestamps per page. */
fun timedCommentOrders(options: List<VideoCommentSort>): List<VideoCommentSort> = listOfNotNull(options.getOrNull(1), options.getOrNull(0))

/**
 * What a video's comment list shows for the selected chip.
 *
 * Top and Newest are already the order the server returned, so they are left alone. Oldest and Timed
 * arrange what they read, and only over the pages loaded so far.
 */
fun applyVideoCommentFilters(
    comments: List<Comment>,
    filter: CommentSortFilter,
    durationMs: Long = 0L,
): List<Comment> =
    when (filter) {
        CommentSortFilter.TOP, CommentSortFilter.NEWEST -> comments
        CommentSortFilter.OLDEST -> sortCommentsByFilter(comments, filter)
        CommentSortFilter.TIMED -> timedCommentsInOrder(comments, durationMs)
    }

private fun relativeTimeToSeconds(timeStr: String): Long {
    val lower = timeStr.lowercase().trim()
    val number = Regex("\\d+").find(lower)?.value?.toLongOrNull() ?: 0L
    return when {
        "second" in lower -> number
        "minute" in lower -> number * 60L
        "hour" in lower -> number * 3_600L
        "day" in lower -> number * 86_400L
        "week" in lower -> number * 604_800L
        "month" in lower -> number * 2_592_000L
        "year" in lower -> number * 31_536_000L
        else -> Long.MAX_VALUE
    }
}

/** Sorts comments for the given filter, keeping pinned comments first. */
fun sortCommentsByFilter(
    comments: List<Comment>,
    filter: CommentSortFilter,
): List<Comment> {
    if (filter == CommentSortFilter.TIMED) return timedCommentsInOrder(comments, durationMs = 0L)
    val pinned = comments.filter { it.isPinned }
    val unpinned = comments.filterNot { it.isPinned }
    val sortedUnpinned =
        when (filter) {
            CommentSortFilter.TOP -> unpinned.sortedByDescending { it.likeCount }
            CommentSortFilter.NEWEST -> unpinned.sortedBy { relativeTimeToSeconds(it.publishedTime) }
            CommentSortFilter.OLDEST -> unpinned.sortedByDescending { relativeTimeToSeconds(it.publishedTime) }
            CommentSortFilter.TIMED -> unpinned
        }
    return pinned + sortedUnpinned
}

/** Converts a "H:MM:SS" / "MM:SS" comment timestamp into milliseconds, or 0 when it is not one. */
fun commentTimestampToMs(timestamp: String): Long = parseTimestampMs(timestamp) ?: 0L

fun formatAuthorName(author: String): String {
    val trimmed = author.trim()
    return if (trimmed.startsWith("@")) {
        trimmed
    } else {
        "@$trimmed"
    }
}

/**
 * Channel reference for a comment author, in one of the forms `youtubeChannelUrl` accepts:
 * a `UC…` channel id, an `@handle`, or a bare handle.
 *
 * Callers must forward the value unchanged — re-prefixing it with `@` turns a channel id into a
 * handle that does not exist, which is why comment authors used to open a 404 page.
 */
fun commentAuthorChannelRef(comment: Comment): String = comment.authorChannelId.trim().ifBlank { comment.author.trim().removePrefix("@") }

internal fun toHighQualityAvatarUrl(url: String): String {
    if (url.isBlank()) return url

    return url
        .replace(Regex("=s\\d+"), "=s1024")
        .replace(Regex("/s\\d+-"), "/s1024-")
        .replace(Regex("=w\\d+-h\\d+"), "=w1024-h1024")
}

@Composable
internal fun localizedCommentPublishedTime(publishedTime: String): String {
    val editedSuffix = Regex("\\s*\\(?edited\\)?\\s*$", RegexOption.IGNORE_CASE)
    val isEdited = editedSuffix.containsMatchIn(publishedTime)
    val time = formatTimeAgo(publishedTime.replace(editedSuffix, "").trim())
    return if (isEdited) {
        stringResource(R.string.comment_time_edited_template, time, stringResource(R.string.comment_edited))
    } else {
        time
    }
}
