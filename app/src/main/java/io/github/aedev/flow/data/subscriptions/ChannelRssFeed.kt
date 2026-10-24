package io.github.aedev.flow.data.subscriptions

/** A single `<entry>` of a YouTube channel RSS feed. */
data class ChannelRssEntry(
    val videoId: String,
    val title: String,
    val thumbnailUrl: String?,
    val publishedAtMillis: Long = 0L,
    val viewCount: Long = 0L,
    val description: String? = null,
    /** From the entry's link: YouTube links a reel as `/shorts/`. Null when the link says neither. */
    val isShort: Boolean? = null,
)

/**
 * A parsed channel RSS document.
 *
 * [entries] keeps the feed's own order, which YouTube publishes newest first.
 */
data class ChannelRssFeed(
    val channelName: String?,
    val entries: List<ChannelRssEntry>,
) {
    companion object {
        val EMPTY = ChannelRssFeed(channelName = null, entries = emptyList())
    }
}
