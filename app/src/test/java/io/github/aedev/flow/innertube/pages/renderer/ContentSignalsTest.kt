package io.github.aedev.flow.innertube.pages.renderer

import io.github.aedev.flow.data.repository.WatchMetadataVideoMapper
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.YouTubeLocale
import io.github.aedev.flow.innertube.models.response.WatchMetadataResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContentSignalsTest {
    private val owner = FeedItemOwner(id = "UCowner", name = "Owner")
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var previousLocale: YouTubeLocale

    @Before
    fun rememberLocale() {
        previousLocale = YouTube.locale
    }

    @After
    fun restoreLocale() {
        YouTube.locale = previousLocale
    }

    private fun useLanguage(hl: String) {
        YouTube.locale = YouTubeLocale(gl = "US", hl = hl)
    }

    private fun signals(raw: String) = ContentSignals.of(Json.parseToJsonElement(raw).jsonObject)

    private fun videoRenderer(
        extra: String,
        views: String = "1,234 views",
    ) = """
        { "videoRenderer": {
          "videoId": "vid00000001",
          "title": { "runs": [ { "text": "A video" } ] },
          "viewCountText": { "simpleText": "$views" },
          $extra
        } }
        """.trimIndent()

    private fun lockup(
        overlays: String = "[]",
        rows: String = "[]",
        contentType: String = "LOCKUP_CONTENT_TYPE_VIDEO",
        onTap: String = """{ "watchEndpoint": { "videoId": "lockup00001" } }""",
    ) = """
        { "contentId": "lockup00001",
          "contentType": "$contentType",
          "contentImage": { "thumbnailViewModel": { "overlays": $overlays } },
          "rendererContext": { "commandContext": { "onTap": { "innertubeCommand": $onTap } } },
          "metadata": { "lockupMetadataViewModel": {
            "title": { "content": "A lockup" },
            "metadata": { "contentMetadataViewModel": { "metadataRows": $rows } }
          } }
        }
        """.trimIndent()

    private fun badgeOverlay(
        text: String,
        style: String,
    ) =
        """[ { "thumbnailBottomOverlayViewModel": { "badges": [ { "thumbnailBadgeViewModel": { "text": "$text", "badgeStyle": "$style" } } ] } } ]"""

    private fun textRow(vararg parts: String) =
        """[ { "metadataParts": [ ${parts.joinToString(",") { """{ "text": { "content": "$it" } }""" }} ] } ]"""

    private fun feedVideo(raw: String) =
        when (val item = Json.parseToJsonElement(raw).toFeedItem(owner)) {
            is FeedItem.VideoItem -> item.video
            is FeedItem.ShortItem -> item.video
            else -> error("not a video: $item")
        }

    private fun related(vararg items: String) =
        WatchMetadataVideoMapper.relatedVideos(
            json.decodeFromString<WatchMetadataResponse>(
                """{"contents":{"twoColumnWatchNextResults":{"secondaryResults":{"secondaryResults":{"results":[""" +
                    items.joinToString(",") + "]}}}}}",
            ),
        )

    @Test
    fun `a spanish lockup is live by its badge style alone`() {
        useLanguage("es")

        val video =
            feedVideo(
                """{ "lockupViewModel": ${lockup(
                    badgeOverlay("EN DIRECTO", "THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE"),
                    textRow("1234 personas viendo"),
                )} }""",
            )

        assertTrue(video.isLive)
        assertFalse(video.isUpcoming)
    }

    @Test
    fun `a spanish search row is live by its metadata badge style`() {
        useLanguage("es")

        val video =
            feedVideo(
                videoRenderer(
                    """"badges": [ { "metadataBadgeRenderer": { "style": "BADGE_STYLE_TYPE_LIVE_NOW", "label": "EN DIRECTO" } } ]""",
                    views = "1234 personas viendo",
                ),
            )

        assertTrue(video.isLive)
    }

    @Test
    fun `a row with only a live time status overlay is live in any language`() {
        useLanguage("es")

        val video =
            feedVideo(
                videoRenderer(
                    """"thumbnailOverlays": [ { "thumbnailOverlayTimeStatusRenderer": { "style": "LIVE", "text": { "simpleText": "EN DIRECTO" } } } ]""",
                ),
            )

        assertTrue(video.isLive)
    }

    @Test
    fun `english wording counts only when the response is english`() {
        val raw = videoRenderer(""""lengthText": { "simpleText": "" }""", views = "1,234 watching")

        useLanguage("en")
        assertTrue(feedVideo(raw).isLive)
        useLanguage("es")
        assertFalse(feedVideo(raw).isLive)
    }

    @Test
    fun `a styled time status overrules english wording`() {
        useLanguage("en")

        val video =
            feedVideo(
                videoRenderer(
                    """"thumbnailOverlays": [ { "thumbnailOverlayTimeStatusRenderer": { "style": "DEFAULT", "text": { "simpleText": "10:00" } } } ]""",
                    views = "Live set, 1,234 watching",
                ),
            )

        assertFalse(video.isLive)
    }

    @Test
    fun `a members-only search row carries its badge label`() {
        val video =
            feedVideo(
                videoRenderer(
                    """"badges": [ { "metadataBadgeRenderer": { "icon": { "iconType": "SPONSORSHIP_STAR" }, "style": "BADGE_STYLE_TYPE_MEMBERS_ONLY", "label": "Solo para miembros" } } ]""",
                ),
            )

        assertEquals("Solo para miembros", video.membersOnlyText)
        assertFalse(video.isLive)
    }

    @Test
    fun `a members-only lockup carries its badge view model label`() {
        val rows =
            """[ { "metadataParts": [ { "text": { "content": "Owner" } } ] },
                 { "badges": [ { "badgeViewModel": { "badgeText": "Members only", "badgeStyle": "BADGE_MEMBERS_ONLY" } } ] } ]"""

        assertEquals("Members only", signals(lockup(rows = rows)).membersOnlyLabel)
    }

    @Test
    fun `an upcoming row takes its scheduled start from the event data`() {
        val parsed =
            signals(
                """{ "upcomingEventData": { "startTime": "1795000000" },
                     "thumbnailOverlays": [ { "thumbnailOverlayTimeStatusRenderer": { "style": "UPCOMING" } } ] }""",
            )

        assertEquals(LiveState.UPCOMING, parsed.liveState)
        assertEquals(1_795_000_000L, parsed.scheduledStart)
    }

    @Test
    fun `an upcoming search row is upcoming and dated by its start`() {
        useLanguage("es")

        val video = feedVideo(videoRenderer(""""upcomingEventData": { "startTime": "1795000000" }""", views = "3 esperando"))

        assertTrue(video.isUpcoming)
        assertFalse(video.isLive)
        assertEquals(1_795_000_000_000L, video.timestamp)
        assertEquals(0L, video.viewCount)
    }

    @Test
    fun `a short lockup is a short even when it opens the watch page`() {
        assertTrue(signals(lockup(contentType = "LOCKUP_CONTENT_TYPE_SHORT")).isShort)
    }

    @Test
    fun `a lockup that opens the reel player is a short`() {
        assertTrue(signals(lockup(onTap = """{ "reelWatchEndpoint": { "videoId": "lockup00001" } }""")).isShort)
        assertFalse(signals(lockup()).isShort)
    }

    @Test
    fun `a plain video has no signals`() {
        val parsed = signals(lockup(badgeOverlay("12:34", "THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT")))

        assertEquals(LiveState.NONE, parsed.liveState)
        assertFalse(parsed.isShort)
        assertNull(parsed.membersOnlyLabel)
        assertNull(parsed.scheduledStart)
    }

    @Test
    fun `a related lockup is live by its style and never by a badge that merely says live`() {
        useLanguage("es")

        val videos =
            related(
                """{ "lockupViewModel": ${lockup(
                    badgeOverlay("EN DIRECTO", "THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE"),
                    textRow("Canal", "1234 personas viendo"),
                )} }""",
                """{ "lockupViewModel": ${lockup(
                    badgeOverlay("Live at Wembley 1:02:03", "THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT"),
                    textRow("Canal"),
                )
                    .replace("lockup00001", "lockup00002")} }""",
            )

        assertEquals(listOf(true, false), videos.map { it.isLive })
    }

    @Test
    fun `a related compact video that opens the reel player is marked a short`() {
        val videos =
            related(
                """{ "compactVideoRenderer": { "videoId": "short000001", "title": { "simpleText": "A short" },
                     "navigationEndpoint": { "reelWatchEndpoint": { "videoId": "short000001" } } } }""",
                """{ "compactVideoRenderer": { "videoId": "live0000001", "title": { "simpleText": "On air" },
                     "thumbnailOverlays": [ { "thumbnailOverlayTimeStatusRenderer": { "style": "LIVE" } } ] } }""",
            )

        assertEquals(listOf(true, false), videos.map { it.isShort })
        assertEquals(listOf(false, true), videos.map { it.isLive })
    }

    @Test
    fun `a related members-only lockup keeps its label`() {
        val rows =
            """[ { "metadataParts": [ { "text": { "content": "Owner" } } ] },
                 { "badges": [ { "badgeViewModel": { "badgeText": "Members only", "badgeStyle": "BADGE_MEMBERS_ONLY" } } ] } ]"""

        assertEquals("Members only", related("""{ "lockupViewModel": ${lockup(rows = rows)} }""").single().membersOnlyText)
    }
}
