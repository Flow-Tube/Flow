package io.github.aedev.flow.ui.components.shared

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
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
    ;

    /** Where this order sits in the section's own sort menu; Oldest and Timed are not in it. */
    val menuIndex: Int?
        get() =
            when (this) {
                TOP -> 0
                NEWEST -> 1
                OLDEST, TIMED -> null
            }
}

/**
 * The continuation that serves [filter], out of the orders the section offered.
 *
 * YouTube's sort menu lists Top, then Newest, in the user's language, so it is matched by position.
 * YouTube has no oldest-first order, so Oldest reads the Newest one to its end and turns it around;
 * Timed reads it too when YouTube has no Timed list.
 */
fun videoCommentSortFor(
    options: List<VideoCommentSort>,
    filter: CommentSortFilter,
): VideoCommentSort? = options.getOrNull(filter.menuIndex ?: 1)

/** The line under the chips when Oldest stopped before the oldest comment; null otherwise. */
@Composable
fun oldestCommentsNote(
    filter: CommentSortFilter,
    capped: Boolean,
    loaded: Int,
): String? =
    if (filter == CommentSortFilter.OLDEST &&
        capped
    ) {
        pluralStringResource(R.plurals.comments_oldest_capped, loaded, loaded)
    } else {
        null
    }

/** Comments read newest first, turned around in the order they arrived; pinned ones stay on top. */
fun oldestFirst(comments: List<Comment>): List<Comment> {
    val (pinned, rest) = comments.partition { it.isPinned }
    return pinned + rest.asReversed()
}

/** The orders Timed reads, in the order it reads them: Newest usually has more timestamps per page. */
fun timedCommentOrders(options: List<VideoCommentSort>): List<VideoCommentSort> = listOfNotNull(options.getOrNull(1), options.getOrNull(0))

/**
 * What a video's comment list shows for the selected chip. Top and Newest are the server's order as
 * it came, Oldest is Newest turned around, and Timed lists the comments that point at a position in
 * the video.
 */
fun applyVideoCommentFilters(
    comments: List<Comment>,
    filter: CommentSortFilter,
    durationMs: Long = 0L,
): List<Comment> =
    when (filter) {
        CommentSortFilter.TOP, CommentSortFilter.NEWEST -> comments
        CommentSortFilter.OLDEST -> oldestFirst(comments)
        CommentSortFilter.TIMED -> timedCommentsInOrder(comments, durationMs)
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
