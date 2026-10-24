package io.github.aedev.flow.data.subscriptions

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Parsing and diffing for the channel RSS feeds both the subscription feed and the new-upload
 * notification check are built on.
 *
 * Kept free of network and WorkManager types so both halves stay unit-testable.
 */
object ChannelRssParser {
    private const val TAG = "ChannelRssParser"

    /**
     * A channel can publish several videos between two checks, so a burst is capped rather than
     * dropped to one — but not left unbounded, otherwise a stored id that has aged out of the feed
     * would announce the channel's whole backlog at once.
     */
    const val MAX_NEW_PER_CHANNEL = 5

    /** Returns [ChannelRssFeed.EMPTY] on malformed XML rather than throwing. */
    fun parse(xml: String): ChannelRssFeed =
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            readFeed(parser)
        } catch (e: Exception) {
            Log.e(TAG, "Could not create the RSS parser", e)
            ChannelRssFeed.EMPTY
        }

    /**
     * Walks a namespace-aware parser already positioned on the feed, returning whatever was read
     * before any malformed markup. Separate from [parse] so tests can drive a real parser
     * implementation — the platform XML factory is stubbed out in local unit tests.
     */
    internal fun readFeed(parser: XmlPullParser): ChannelRssFeed {
        val entries = mutableListOf<ChannelRssEntry>()
        var channelName: String? = null
        try {
            var insideEntry = false
            var videoId: String? = null
            var title: String? = null
            var thumbnail: String? = null
            var description: String? = null
            var publishedAt = 0L
            var viewCount = 0L
            var isShort: Boolean? = null

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name

                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals("entry", ignoreCase = true)) {
                            insideEntry = true
                            videoId = null
                            title = null
                            thumbnail = null
                            description = null
                            publishedAt = 0L
                            viewCount = 0L
                            isShort = null
                        } else if (insideEntry) {
                            when {
                                tagName.equals("videoId", ignoreCase = true) -> {
                                    videoId = parser.nextText()
                                }

                                // The feed repeats the title inside <media:group>; keep the first.
                                tagName.equals("title", ignoreCase = true) && title == null -> {
                                    title = parser.nextText()
                                }

                                tagName.equals("thumbnail", ignoreCase = true) && thumbnail == null -> {
                                    thumbnail = parser.getAttributeValue(null, "url")
                                }

                                tagName.equals("description", ignoreCase = true) && description == null -> {
                                    description = parser.nextText()
                                }

                                // <updated> also appears per entry, but it moves on edits; only
                                // <published> is a stable upload time.
                                tagName.equals("published", ignoreCase = true) && publishedAt == 0L -> {
                                    publishedAt = parseTimestamp(parser.nextText())
                                }

                                tagName.equals("statistics", ignoreCase = true) && viewCount == 0L -> {
                                    viewCount = parser.getAttributeValue(null, "views")?.toLongOrNull() ?: 0L
                                }

                                tagName.equals("link", ignoreCase = true) && isShort == null -> {
                                    isShort = reelVerdict(parser.getAttributeValue(null, "href"))
                                }
                            }
                        } else if (tagName.equals("name", ignoreCase = true) && channelName == null) {
                            // Only the feed-level <author> carries a <name> in a channel feed.
                            channelName = parser.nextText()
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("entry", ignoreCase = true)) {
                            insideEntry = false
                            val id = videoId
                            val entryTitle = title
                            if (!id.isNullOrEmpty() && !entryTitle.isNullOrEmpty()) {
                                entries +=
                                    ChannelRssEntry(
                                        videoId = id,
                                        title = entryTitle,
                                        thumbnailUrl = thumbnail,
                                        publishedAtMillis = publishedAt,
                                        viewCount = viewCount,
                                        description = description,
                                        isShort = isShort,
                                    )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing RSS XML", e)
        }
        return ChannelRssFeed(channelName = channelName?.takeIf { it.isNotBlank() }, entries = entries)
    }

    private fun reelVerdict(href: String?): Boolean? {
        val path = href?.substringAfter("://", "")?.substringAfter('/', "") ?: return null
        return when {
            path.startsWith("shorts/") -> true
            path.startsWith("watch") -> false
            else -> null
        }
    }

    private fun parseTimestamp(value: String?): Long {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return 0L
        return try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            0L
        }
    }

    /**
     * The entries published since [lastVideoId] was recorded, newest first.
     *
     * A null [lastVideoId] means this channel has never been checked, so nothing is announced —
     * the caller only stores the pointer. When [lastVideoId] is no longer in the feed the backlog
     * length is unknown, so only the newest entry is announced.
     */
    fun newEntriesSince(
        entries: List<ChannelRssEntry>,
        lastVideoId: String?,
    ): List<ChannelRssEntry> {
        if (entries.isEmpty() || lastVideoId == null) return emptyList()

        val knownIndex = entries.indexOfFirst { it.videoId == lastVideoId }
        return when {
            knownIndex == 0 -> emptyList()
            knownIndex > 0 -> entries.take(minOf(knownIndex, MAX_NEW_PER_CHANNEL))
            else -> entries.take(1)
        }
    }
}
