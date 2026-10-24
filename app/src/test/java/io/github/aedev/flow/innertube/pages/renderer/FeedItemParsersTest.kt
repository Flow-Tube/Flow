package io.github.aedev.flow.innertube.pages.renderer

import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.YouTubeLocale
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * `contentType` is the field the whole registry turns on: the same `lockupViewModel` carries videos,
 * playlists, podcasts and sister channels, and the parser it was replacing assumed every one of them
 * was a video.
 */
class FeedItemParsersTest {
    private val owner = FeedItemOwner(id = "UCowner", name = "Linus Tech Tips", avatarUrl = "https://yt3.test/avatar.jpg")

    private fun item(raw: String) = Json.parseToJsonElement(raw).toFeedItem(owner)

    private fun lockup(
        contentId: String,
        contentType: String,
        title: String,
        rows: String = "[]",
        overlays: String = "[]",
    ) = """
        { "lockupViewModel": {
          "contentId": "$contentId",
          "contentType": "$contentType",
          "contentImage": { "thumbnailViewModel": {
            "image": { "sources": [ { "url": "https://i.ytimg.test/$contentId.jpg", "width": 1280, "height": 720 } ] },
            "overlays": $overlays
          } },
          "metadata": { "lockupMetadataViewModel": {
            "title": { "content": "$title" },
            "metadata": { "contentMetadataViewModel": { "metadataRows": $rows } }
          } }
        } }
        """.trimIndent()

    private fun durationOverlay(text: String) =
        """[ { "thumbnailOverlayBadgeViewModel": { "thumbnailBadges": [ { "thumbnailBadgeViewModel": { "text": "$text" } } ] } } ]"""

    private fun row(vararg parts: String) =
        """[ { "metadataParts": [ ${parts.joinToString(",") { """{ "text": { "content": "$it" } }""" }} ] } ]"""

    /** The shape the streams tab returns live: a bottom overlay whose badge is styled, not only worded. */
    private fun bottomBadgeOverlay(
        text: String,
        style: String,
    ) =
        """[ { "thumbnailBottomOverlayViewModel": { "badges": [ { "thumbnailBadgeViewModel": { "text": "$text", "badgeStyle": "$style" } } ] } } ]"""

    @Test
    fun `a finished stream keeps its duration and is neither live nor upcoming`() {
        val video =
            (
                item(
                    lockup(
                        "wan1",
                        "LOCKUP_CONTENT_TYPE_VIDEO",
                        "The WAN Show",
                        rows = row("603K views", "Streamed 2 months ago"),
                        overlays = bottomBadgeOverlay("3:53:45", "THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT"),
                    ),
                ) as FeedItem.VideoItem
            ).video

        assertEquals(3 * 3600 + 53 * 60 + 45, video.duration)
        assertFalse(video.isLive)
        assertFalse(video.isUpcoming)
        assertEquals("Streamed 2 months ago", video.uploadDate)
    }

    @Test
    fun `a spanish row keeps its view count and dates the video by its own words`() {
        val previous = YouTube.locale
        YouTube.locale = YouTubeLocale(gl = "ES", hl = "es")
        val video =
            try {
                (
                    item(
                        lockup(
                            "es1",
                            "LOCKUP_CONTENT_TYPE_VIDEO",
                            "Un video",
                            rows = row("1234 visualizaciones", "hace 3 semanas"),
                            overlays = durationOverlay("10:00"),
                        ),
                    ) as FeedItem.VideoItem
                ).video
            } finally {
                YouTube.locale = previous
            }

        assertEquals("hace 3 semanas", video.uploadDate)
        assertEquals(1234L, video.viewCount)
        val threeWeeksAgo = System.currentTimeMillis() - 21L * 86_400_000L
        assertTrue(abs(video.timestamp - threeWeeksAgo) < 60_000L)
    }

    @Test
    fun `a stream that is on now is live`() {
        val video =
            (
                item(
                    lockup(
                        "iss",
                        "LOCKUP_CONTENT_TYPE_VIDEO",
                        "Live Video from the International Space Station",
                        rows = row("83 watching"),
                        overlays = bottomBadgeOverlay("LIVE", "THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE"),
                    ),
                ) as FeedItem.VideoItem
            ).video

        assertTrue(video.isLive)
        assertFalse(video.isUpcoming)
        assertEquals(0, video.duration)
    }

    @Test
    fun `a scheduled stream is upcoming and keeps its scheduled start as the date`() {
        val video =
            (
                item(
                    lockup(
                        "crew12",
                        "LOCKUP_CONTENT_TYPE_VIDEO",
                        "Crew-12 Pre-Departure News Conference",
                        rows = row("2 waiting", "Scheduled for 9/16/26, 6:45 PM"),
                        overlays = bottomBadgeOverlay("Upcoming", "THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT"),
                    ),
                ) as FeedItem.VideoItem
            ).video

        assertTrue(video.isUpcoming)
        assertFalse(video.isLive)
        assertEquals(0, video.duration)
        assertEquals(0L, video.viewCount)
        assertEquals("Scheduled for 9/16/26, 6:45 PM", video.uploadDate)
        assertEquals(0L, video.timestamp)
    }

    @Test
    fun `a video lockup becomes a video with duration views and attribution`() {
        val parsed =
            item(
                lockup(
                    "1_y9qCIPhQ4",
                    "LOCKUP_CONTENT_TYPE_VIDEO",
                    "I Rescued Abandoned UI Designs",
                    rows = row("64K views", "4 days ago"),
                    overlays = durationOverlay("8:24"),
                ),
            )

        val video = (parsed as FeedItem.VideoItem).video
        assertEquals("1_y9qCIPhQ4", video.id)
        assertEquals("I Rescued Abandoned UI Designs", video.title)
        assertEquals(504, video.duration)
        assertEquals(64_000L, video.viewCount)
        assertEquals("4 days ago", video.uploadDate)
        assertEquals("Linus Tech Tips", video.channelName)
        assertEquals("UCowner", video.channelId)
        assertEquals("https://yt3.test/avatar.jpg", video.channelThumbnailUrl)
    }

    @Test
    fun `a short lockup on a Home tab stays a video but is marked a Short (1393)`() {
        val parsed =
            item(
                lockup(
                    "hqr_k5p2KZ8",
                    "LOCKUP_CONTENT_TYPE_SHORT",
                    "How to Use Ultrafast Properly",
                    rows = row("12K views", "10 days ago"),
                    overlays = durationOverlay("0:28"),
                ),
            )

        val video = (parsed as FeedItem.VideoItem).video
        assertTrue(video.isShort)
        assertEquals(28, video.duration)
    }

    @Test
    fun `a plural relative date resolves to days rather than seconds`() {
        val fourDays =
            (
                item(lockup("v1", "LOCKUP_CONTENT_TYPE_VIDEO", "T", rows = row("64K views", "4 days ago")))
                    as FeedItem.VideoItem
            ).video.timestamp
        val fourSeconds =
            (
                item(lockup("v2", "LOCKUP_CONTENT_TYPE_VIDEO", "T", rows = row("64K views", "4 seconds ago")))
                    as FeedItem.VideoItem
            ).video.timestamp

        assertTrue("4 days ago must be older than 4 seconds ago", fourDays < fourSeconds - 3L * 86_400_000L)
    }

    @Test
    fun `a playlist lockup becomes a playlist and takes its count from the badge`() {
        val parsed =
            item(
                lockup(
                    "PLxxxx",
                    "LOCKUP_CONTENT_TYPE_PLAYLIST",
                    "Scrapyard Wars",
                    overlays = durationOverlay("12 videos"),
                ),
            )

        val playlist = (parsed as FeedItem.PlaylistItem).playlist
        assertEquals("PLxxxx", playlist.id)
        assertEquals("Scrapyard Wars", playlist.name)
        assertEquals(12, playlist.videoCount)
        assertEquals(false, playlist.isLocal)
    }

    @Test
    fun `a course lockup is a playlist with its lessons, not an upcoming video (#1299)`() {
        val lessonsBadge =
            """[ { "thumbnailOverlayBadgeViewModel": { "thumbnailBadges": [ { "thumbnailBadgeViewModel": {
              "icon": { "sources": [ { "clientResource": { "imageName": "GRADUATION_CAP" } } ] },
              "text": "124 lessons", "badgeStyle": "THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT" } } ] } } ]"""
        val parsed =
            item(
                lockup(
                    "PLcourse",
                    "LOCKUP_CONTENT_TYPE_COURSE",
                    "Python for Beginners (Full Course)",
                    rows = row("Telusko", "Course"),
                    overlays = lessonsBadge,
                ),
            )

        val playlist = (parsed as FeedItem.PlaylistItem).playlist
        assertEquals("PLcourse", playlist.id)
        assertEquals(124, playlist.videoCount)
        assertTrue(playlist.isCourse)
    }

    @Test
    fun `a playlist row with the course panel is a course`() {
        val parsed =
            item(
                """
                { "playlistRenderer": {
                  "playlistId": "PLcourse",
                  "title": { "simpleText": "Python for Beginners (Full Course)" },
                  "videoCount": "124",
                  "thumbnailOverlays": [
                    { "thumbnailOverlayBottomPanelRenderer": { "text": { "simpleText": "124 lessons" }, "icon": { "iconType": "COURSE" } } },
                    { "thumbnailOverlayHoverTextRenderer": { "text": { "runs": [ { "text": "Play all" } ] }, "icon": { "iconType": "PLAY_ALL" } } }
                  ]
                } }
                """.trimIndent(),
            )

        val playlist = (parsed as FeedItem.PlaylistItem).playlist
        assertEquals(124, playlist.videoCount)
        assertTrue(playlist.isCourse)
    }

    @Test
    fun `an ordinary playlist is not a course`() {
        val parsed = item(lockup("PLxxxx", "LOCKUP_CONTENT_TYPE_PLAYLIST", "Scrapyard Wars", overlays = durationOverlay("12 videos")))

        assertFalse((parsed as FeedItem.PlaylistItem).playlist.isCourse)
    }

    @Test
    fun `a podcast lockup is a playlist rather than a video`() {
        val parsed = item(lockup("PLpodcast", "LOCKUP_CONTENT_TYPE_PODCAST", "The WAN Show"))

        assertTrue(parsed is FeedItem.PlaylistItem)
    }

    @Test
    fun `a channel lockup becomes a related channel`() {
        val parsed =
            item(
                lockup(
                    "UCsister",
                    "LOCKUP_CONTENT_TYPE_CHANNEL",
                    "Techquickie",
                    rows = row("4.5M subscribers"),
                ),
            )

        val channel = (parsed as FeedItem.RelatedChannelItem).channel
        assertEquals("UCsister", channel.id)
        assertEquals("Techquickie", channel.name)
        assertEquals(4_500_000L, channel.subscriberCount)
    }

    @Test
    fun `a live lockup is marked live from its watching count`() {
        val parsed =
            item(lockup("live1", "LOCKUP_CONTENT_TYPE_VIDEO", "The WAN Show", rows = row("32K watching")))

        assertTrue((parsed as FeedItem.VideoItem).video.isLive)
    }

    @Test
    fun `a shorts lockup becomes a short`() {
        val parsed =
            item(
                """
                { "shortsLockupViewModel": {
                  "onTap": { "innertubeCommand": { "commandMetadata": { "webCommandMetadata": {
                    "url": "/shorts/iRZSOtjOlH8"
                  } } } },
                  "overlayMetadata": {
                    "primaryText": { "content": "Best comment out of 25K viewers" },
                    "secondaryText": { "content": "1.2M views" }
                  },
                  "thumbnailViewModel": { "thumbnailViewModel": { "image": { "sources": [
                    { "url": "https://i.ytimg.test/frame0.jpg", "width": 405, "height": 720 }
                  ] } } }
                } }
                """.trimIndent(),
            )

        val short = (parsed as FeedItem.ShortItem).video
        assertEquals("iRZSOtjOlH8", short.id)
        assertEquals("Best comment out of 25K viewers", short.title)
        assertEquals(1_200_000L, short.viewCount)
        assertTrue(short.isShort)
        assertEquals("UCowner", short.channelId)
    }

    @Test
    fun `a legacy video renderer still parses`() {
        val parsed =
            item(
                """
                { "videoRenderer": {
                  "videoId": "legacy1",
                  "title": { "runs": [ { "text": "Legacy item" } ] },
                  "lengthText": { "simpleText": "1:02:03" },
                  "viewCountText": { "simpleText": "1,234,567 views" },
                  "publishedTimeText": { "simpleText": "2 years ago" },
                  "thumbnail": { "thumbnails": [ { "url": "https://i.ytimg.test/legacy1.jpg", "width": 480, "height": 360 } ] }
                } }
                """.trimIndent(),
            )

        val video = (parsed as FeedItem.VideoItem).video
        assertEquals(3723, video.duration)
        assertEquals(1_234_567L, video.viewCount)
    }

    @Test
    fun `a rich item wrapper is unwrapped`() {
        val parsed =
            item("""{ "richItemRenderer": { "content": ${lockup("wrapped", "LOCKUP_CONTENT_TYPE_VIDEO", "Wrapped")} } }""")

        assertEquals("wrapped", (parsed as FeedItem.VideoItem).video.id)
    }

    @Test
    fun `an unregistered renderer yields null instead of throwing`() {
        assertNull(item("""{ "someFutureRenderer": { "id": "x", "title": "y" } }"""))
        assertNull(item("""{ "continuationItemRenderer": { "continuationEndpoint": {} } }"""))
    }

    @Test
    fun `an unregistered renderer between two known ones does not abort the list`() {
        val items =
            Json
                .parseToJsonElement(
                    """
                    [
                      ${lockup("a", "LOCKUP_CONTENT_TYPE_VIDEO", "First")},
                      { "someFutureRenderer": {} },
                      ${lockup("b", "LOCKUP_CONTENT_TYPE_VIDEO", "Second")}
                    ]
                    """.trimIndent(),
                ).toFeedItems(owner)

        assertEquals(listOf("a", "b"), items.map { (it as FeedItem.VideoItem).video.id })
    }

    @Test
    fun `duration text without a colon is not mistaken for a duration`() {
        assertNull(parseDurationText("12 videos"))
        assertNull(parseDurationText("LIVE"))
        assertEquals(84, parseDurationText("1:24"))
    }
}
