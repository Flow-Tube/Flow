package io.github.aedev.flow.data.subscriptions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

class ChannelRssParserTest {
    /** Mirrors the namespace-aware parser [ChannelRssParser.parse] builds on device. */
    private fun parse(xml: String): ChannelRssFeed {
        val parser =
            KXmlParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(StringReader(xml))
            }
        return ChannelRssParser.readFeed(parser)
    }

    private fun entriesOf(xml: String): List<ChannelRssEntry> = parse(xml).entries

    /** A channel feed shaped like YouTube's, entries newest first. */
    private fun feed(
        vararg ids: String,
        links: Map<String, String> = emptyMap(),
    ): String =
        buildString {
            // No leading whitespace: an XML declaration must start at offset 0.
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append(
                "<feed xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\" " +
                    "xmlns:media=\"http://search.yahoo.com/mrss/\" " +
                    "xmlns=\"http://www.w3.org/2005/Atom\">",
            )
            append("<title>Channel Name</title>")
            append("<link rel=\"alternate\" href=\"https://www.youtube.com/channel/x\"/>")
            append("<author><name>Uploader Name</name><uri>https://www.youtube.com/channel/x</uri></author>")
            ids.forEach { id ->
                append("<entry>")
                append("<yt:videoId>$id</yt:videoId>")
                append("<title>Title $id</title>")
                links[id]?.let { append("<link rel=\"alternate\" href=\"$it\"/>") }
                append("<published>2026-05-01T12:00:00+00:00</published>")
                append("<media:group>")
                append("<media:title>Title $id</media:title>")
                append("<media:thumbnail url=\"https://i.ytimg.com/vi/$id/hq.jpg\"/>")
                append("<media:description>Description $id</media:description>")
                append("<media:community><media:statistics views=\"4242\"/></media:community>")
                append("</media:group>")
                append("</entry>")
            }
            append("</feed>")
        }

    @Test
    fun `parses every entry newest first`() {
        val videos = entriesOf(feed("aaa", "bbb", "ccc"))

        assertEquals(listOf("aaa", "bbb", "ccc"), videos.map { it.videoId })
        assertEquals("Title aaa", videos.first().title)
        assertEquals("https://i.ytimg.com/vi/aaa/hq.jpg", videos.first().thumbnailUrl)
    }

    @Test
    fun `entry title wins over the repeated media title`() {
        assertEquals("Title aaa", entriesOf(feed("aaa")).single().title)
    }

    @Test
    fun `channel title is not mistaken for an entry`() {
        val videos = entriesOf(feed("aaa"))

        assertEquals(1, videos.size)
        assertTrue(videos.none { it.title == "Channel Name" })
    }

    @Test
    fun `author name is read as the channel name`() {
        assertEquals("Uploader Name", parse(feed("aaa")).channelName)
    }

    @Test
    fun `published date is parsed to epoch millis`() {
        val expected = 1777636800000L // 2026-05-01T12:00:00Z

        assertEquals(expected, entriesOf(feed("aaa")).single().publishedAtMillis)
    }

    @Test
    fun `view count is read from media statistics`() {
        assertEquals(4242L, entriesOf(feed("aaa")).single().viewCount)
    }

    @Test
    fun `description is read from media description`() {
        assertEquals("Description aaa", entriesOf(feed("aaa")).single().description)
    }

    @Test
    fun `unparseable published date falls back to zero rather than throwing`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
              <entry><yt:videoId>aaa</yt:videoId><title>Title aaa</title><published>not-a-date</published></entry>
            </feed>
            """.trimIndent()

        assertEquals(0L, entriesOf(xml).single().publishedAtMillis)
    }

    @Test
    fun `entry without a video id is skipped`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
              <entry><title>No id here</title></entry>
              <entry><yt:videoId>bbb</yt:videoId><title>Real one</title></entry>
            </feed>
            """.trimIndent()

        assertEquals(listOf("bbb"), entriesOf(xml).map { it.videoId })
    }

    @Test
    fun `malformed xml yields no entries instead of throwing`() {
        assertEquals(emptyList<ChannelRssEntry>(), entriesOf("<feed><entry>"))
    }

    @Test
    fun `missing thumbnail stays null`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
              <entry><yt:videoId>aaa</yt:videoId><title>Title aaa</title></entry>
            </feed>
            """.trimIndent()

        assertNull(entriesOf(xml).single().thumbnailUrl)
    }

    @Test
    fun `first ever check announces nothing`() {
        val entries = entriesOf(feed("aaa", "bbb"))

        assertEquals(emptyList<ChannelRssEntry>(), ChannelRssParser.newEntriesSince(entries, lastVideoId = null))
    }

    @Test
    fun `unchanged feed announces nothing`() {
        val entries = entriesOf(feed("aaa", "bbb"))

        assertEquals(emptyList<ChannelRssEntry>(), ChannelRssParser.newEntriesSince(entries, lastVideoId = "aaa"))
    }

    @Test
    fun `every video published since the last check is announced`() {
        val entries = entriesOf(feed("new1", "new2", "new3", "known"))

        val fresh = ChannelRssParser.newEntriesSince(entries, lastVideoId = "known")

        assertEquals(listOf("new1", "new2", "new3"), fresh.map { it.videoId })
    }

    @Test
    fun `a burst of new videos is capped`() {
        val ids = (1..12).map { "v$it" }.toTypedArray()
        val entries = entriesOf(feed(*ids, "known"))

        val fresh = ChannelRssParser.newEntriesSince(entries, lastVideoId = "known")

        assertEquals(ChannelRssParser.MAX_NEW_PER_CHANNEL, fresh.size)
        assertEquals("v1", fresh.first().videoId)
    }

    @Test
    fun `pointer that aged out of the feed announces only the newest`() {
        val entries = entriesOf(feed("aaa", "bbb", "ccc"))

        val fresh = ChannelRssParser.newEntriesSince(entries, lastVideoId = "long-gone")

        assertEquals(listOf("aaa"), fresh.map { it.videoId })
    }

    @Test
    fun `empty feed announces nothing`() {
        assertEquals(emptyList<ChannelRssEntry>(), ChannelRssParser.newEntriesSince(emptyList(), lastVideoId = "aaa"))
    }

    @Test
    fun `entry link tells a reel from a video`() {
        val entries =
            entriesOf(
                feed(
                    "reel",
                    "video",
                    links =
                        mapOf(
                            "reel" to "https://www.youtube.com/shorts/reel",
                            "video" to "https://www.youtube.com/watch?v=video",
                        ),
                ),
            )

        assertEquals(listOf(true, false), entries.map { it.isShort })
    }

    @Test
    fun `entry without a recognised link has no reel verdict`() {
        val entries = entriesOf(feed("bare", "other", links = mapOf("other" to "https://www.youtube.com/live/other")))

        assertEquals(listOf(null, null), entries.map { it.isShort })
    }
}
