package io.github.aedev.flow.data.subscriptions

/**
 * Past live streams keep the English word "Streamed" in front of their stored date, which the date
 * display reads and replaces with the translated label (#2437). The store has no column for it, and
 * every pass that recomputes a relative date would otherwise drop it.
 *
 * Only "Streamed <date>" is ever stored: "Streamed live on …" would read as live, which
 * [SubscriptionFeedTimestamps.parseRelativeTime] dates an hour ahead.
 */
internal object StreamedUploadDate {
    private const val PREFIX = "Streamed "
    private val StreamedWords = Regex("(?i)^\\s*streamed\\s+(live\\s+)?(on\\s+)?")

    fun isMarked(uploadDate: String): Boolean = StreamedWords.containsMatchIn(uploadDate)

    fun unmarked(uploadDate: String): String = uploadDate.replace(StreamedWords, "").trim()

    fun marked(uploadDate: String): String = unmarked(uploadDate).let { if (it.isBlank()) "" else PREFIX + it }

    /** [uploadDate] marked when [streamed] holds, else left as it is. */
    fun markedIf(
        streamed: Boolean,
        uploadDate: String,
    ): String = if (streamed) marked(uploadDate) else uploadDate
}
