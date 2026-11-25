package io.github.aedev.flow.innertube.pages.renderer

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-image case is the regression this file exists for: the previous parser walked into
 * `postMultiImageRenderer`, returned the first `backstageImageRenderer` it reached, and dropped every
 * other photo in the gallery.
 */
class PostAttachmentTest {
    private val owner = FeedItemOwner(id = "UCowner", name = "Linus Tech Tips", avatarUrl = "https://yt3.test/a.jpg")

    private fun attachment(raw: String) = Json.parseToJsonElement(raw).toPostAttachment(owner)

    private fun image(
        name: String,
        vararg sizes: Int,
    ): String {
        val thumbs = sizes.joinToString(",") { """{"url":"https://yt3.test/$name-$it.jpg","width":$it,"height":$it}""" }
        return """{"backstageImageRenderer":{"image":{"thumbnails":[$thumbs]}}}"""
    }

    @Test
    fun `every image of a gallery survives, in order`() {
        val parsed =
            attachment(
                """
                { "postMultiImageRenderer": { "images": [
                  ${image("one", 400, 1080, 3657)},
                  ${image("two", 400, 2000)},
                  ${image("three", 2000)}
                ] } }
                """.trimIndent(),
            )

        val images = parsed as PostAttachment.Images
        assertEquals(
            listOf(
                "https://yt3.test/one-3657.jpg",
                "https://yt3.test/two-2000.jpg",
                "https://yt3.test/three-2000.jpg",
            ),
            images.urls,
        )
    }

    @Test
    fun `a single image is a one-entry gallery`() {
        val parsed = attachment(image("solo", 800))

        assertEquals(listOf("https://yt3.test/solo-800.jpg"), (parsed as PostAttachment.Images).urls)
    }

    @Test
    fun `a protocol-relative image url is made absolute`() {
        val parsed =
            attachment("""{"backstageImageRenderer":{"image":{"thumbnails":[{"url":"//yt3.test/x.jpg","width":800,"height":800}]}}}""")

        assertEquals(listOf("https://yt3.test/x.jpg"), (parsed as PostAttachment.Images).urls)
    }

    @Test
    fun `a poll keeps its choices, ratios and counts`() {
        val parsed =
            attachment(
                """
                { "pollRenderer": {
                  "choices": [
                    { "text": { "runs": [ { "text": "I'd buy a handheld today" } ] },
                      "numVotes": "42K", "voteRatioIfSelected": 0.62 },
                    { "text": { "runs": [ { "text": "Waiting for next-gen APUs" } ] },
                      "numVotes": "26K", "voteRatioIfSelected": 0.38 }
                  ],
                  "totalVotes": { "simpleText": "68K votes" }
                } }
                """.trimIndent(),
            )

        val poll = parsed as PostAttachment.Poll
        assertEquals(2, poll.choices.size)
        assertEquals("I'd buy a handheld today", poll.choices[0].text)
        assertEquals(0.62f, poll.choices[0].voteRatio!!, 0.001f)
        assertEquals("42K", poll.choices[0].voteCountText)
        assertEquals("68K votes", poll.totalVotesText)
    }

    @Test
    fun `an image poll keeps each choice's picture`() {
        val parsed =
            attachment(
                """
                { "pollRenderer": {
                  "choices": [
                    { "text": { "runs": [ { "text": "Sage" } ] },
                      "image": { "thumbnails": [
                        { "url": "https://yt3.test/sage-small.jpg", "width": 88, "height": 88 },
                        { "url": "https://yt3.test/sage-large.jpg", "width": 400, "height": 400 } ] } },
                    { "text": { "runs": [ { "text": "Lilac" } ] },
                      "image": { "thumbnails": [ { "url": "https://yt3.test/lilac.jpg", "width": 400, "height": 400 } ] } }
                  ],
                  "totalVotes": { "simpleText": "31K votes" }
                } }
                """.trimIndent(),
            )

        val poll = parsed as PostAttachment.Poll
        assertEquals(listOf("https://yt3.test/sage-large.jpg", "https://yt3.test/lilac.jpg"), poll.choices.map { it.imageUrl })
    }

    @Test
    fun `a text poll has no pictures`() {
        val parsed = attachment("""{ "pollRenderer": { "choices": [ { "text": { "runs": [ { "text": "Yes" } ] } } ] } }""")

        assertNull((parsed as PostAttachment.Poll).choices.single().imageUrl)
    }

    @Test
    fun `a poll without ratios still parses its choices`() {
        val parsed =
            attachment(
                """{ "pollRenderer": { "choices": [ { "text": { "runs": [ { "text": "Yes" } ] } } ] } }""",
            )

        val poll = parsed as PostAttachment.Poll
        assertEquals("Yes", poll.choices.single().text)
        assertNull(poll.choices.single().voteRatio)
    }

    @Test
    fun `a shared video keeps its identity and duration`() {
        val parsed =
            attachment(
                """
                { "videoRenderer": {
                  "videoId": "IA9LlfQ9X-Q",
                  "title": { "runs": [ { "text": "I Bought EVERY Tech Ad I Saw for a MONTH" } ] },
                  "lengthText": { "simpleText": "21:44" },
                  "viewCountText": { "simpleText": "1.8M views" },
                  "thumbnail": { "thumbnails": [ { "url": "https://i.ytimg.test/x.jpg", "width": 1280, "height": 720 } ] }
                } }
                """.trimIndent(),
            )

        val video = (parsed as PostAttachment.SharedVideo).video
        assertEquals("IA9LlfQ9X-Q", video.id)
        assertEquals(1304, video.duration)
        assertEquals(1_800_000L, video.viewCount)
    }

    @Test
    fun `a shared playlist keeps its id, title, cover and count`() {
        val parsed =
            attachment(
                """
                { "playlistRenderer": {
                  "playlistId": "PLsynthetic01",
                  "title": { "simpleText": "Bench builds" },
                  "videoCount": "12",
                  "thumbnails": [ { "thumbnails": [ { "url": "https://i.ytimg.test/p.jpg", "width": 480, "height": 270 } ] } ],
                  "thumbnail": { "thumbnails": [ { "url": "https://i.ytimg.test/p.jpg", "width": 480, "height": 270 } ] }
                } }
                """.trimIndent(),
            )

        val playlist = (parsed as PostAttachment.SharedPlaylist).playlist
        assertEquals("PLsynthetic01", playlist.id)
        assertEquals("Bench builds", playlist.name)
        assertEquals("https://i.ytimg.test/p.jpg", playlist.thumbnailUrl)
        assertEquals(12, playlist.videoCount)
    }

    @Test
    fun `a playlist without an id is not an attachment`() {
        assertNull(attachment("""{ "playlistRenderer": { "title": { "simpleText": "No id" } } }"""))
    }

    @Test
    fun `a text-only post has no attachment`() {
        assertNull(attachment("{}"))
        assertNull(attachment("""{"someFutureAttachment":{"id":"x"}}"""))
    }

    @Test
    fun `a post parses end to end with its gallery intact`() {
        val page =
            Json
                .parseToJsonElement(
                    """
                    { "contents": [ { "backstagePostThreadRenderer": { "post": { "backstagePostRenderer": {
                      "postId": "Ugk123",
                      "authorText": { "runs": [ { "text": "Linus Tech Tips" } ] },
                      "contentText": { "runs": [ { "text": "Happy 40th Birthday Linus!" } ] },
                      "publishedTimeText": { "simpleText": "2 weeks ago" },
                      "voteCount": { "simpleText": "31K" },
                      "backstageAttachment": { "postMultiImageRenderer": { "images": [
                        ${image("a", 1080)}, ${image("b", 2000)}, ${image("c", 2000)}
                      ] } }
                    } } } } ] }
                    """.trimIndent(),
                ).toCommunityPostsPage("Fallback", "https://yt3.test/fallback.jpg")

        val post = page.posts.single()
        assertEquals("Ugk123", post.id)
        assertEquals("31K", post.likeCountText)
        assertTrue(post.attachment is PostAttachment.Images)
        assertEquals(3, (post.attachment as PostAttachment.Images).urls.size)
    }
}
