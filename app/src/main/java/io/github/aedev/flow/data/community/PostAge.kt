package io.github.aedev.flow.data.community

import io.github.aedev.flow.utils.relativedate.RelativeUploadDateParser
import java.time.Duration

/** How old a community post is, read from the relative time YouTube prints on it ("3 days ago (edited)"). */
internal object PostAge {
    val RecentWindow: Duration = Duration.ofDays(30)

    /** When the post went up, or null when [publishedTimeText] carries no readable age. */
    fun publishedAt(
        publishedTimeText: String,
        hl: String?,
        now: Long,
    ): Long? = RelativeUploadDateParser.parse(publishedTimeText, hl, now)

    /** Under [RecentWindow] old. An unreadable age counts as old: it cannot prove the post is recent. */
    fun isRecent(
        publishedAt: Long?,
        now: Long,
    ): Boolean = publishedAt != null && now - publishedAt < RecentWindow.toMillis()
}
