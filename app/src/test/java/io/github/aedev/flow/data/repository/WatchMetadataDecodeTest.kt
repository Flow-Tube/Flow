package io.github.aedev.flow.data.repository

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.WatchMetadataResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * The player path reuses the raw watch response it already cached rather than issuing a second
 * /next, so this decode is the hinge: if it silently fails the callers fall back to a network
 * request and the dedup quietly stops working.
 */
class WatchMetadataDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture() =
        json.parseToJsonElement(
            javaClass.classLoader!!
                .getResourceAsStream("watch_next_related_lockups.json")!!
                .bufferedReader()
                .use { it.readText() },
        )

    @Test
    fun `a cached watch response decodes into the typed model`() {
        val decoded = decodeWatchMetadata(fixture())

        assertThat(decoded).isNotNull()
        assertThat(decoded!!.relatedVideos().map { it.videoId })
            .containsExactly("DdNbdhWmX04", "i_nj_vkD03g")
            .inOrder()
    }

    @Test
    fun `the decoded model carries the channel ids the related lane filters on`() {
        val decoded = decodeWatchMetadata(fixture())!!

        assertThat(decoded.relatedVideos().mapNotNull { it.channelId() }).hasSize(2)
    }

    @Test
    fun `a payload that is not a watch response decodes to null instead of throwing`() {
        assertThat(decodeWatchMetadata(JsonPrimitive("not a watch response"))).isNull()
    }

    private fun ownerResponse(subscriberText: String?): WatchMetadataResponse {
        val count = subscriberText?.let { """"subscriberCountText":{"simpleText":"$it"},""" }.orEmpty()
        val raw =
            """
            {"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[
              {"videoSecondaryInfoRenderer":{"owner":{"videoOwnerRenderer":{
                $count
                "thumbnail":{"thumbnails":[{"url":"https://example.invalid/a.jpg","width":88,"height":88}]},
                "title":{"runs":[{"text":"Owner"}]},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"UCreal"}}
              }}}}
            ]}}}}}
            """.trimIndent()
        return decodeWatchMetadata(json.parseToJsonElement(raw))!!
    }

    @Test
    fun `the owner carries the avatar and the subscriber count`() {
        val channel = watchChannelOf(ownerResponse("17.2M subscribers"), "en")

        assertThat(channel.subscriberCount).isEqualTo(17_200_000L)
        assertThat(channel.avatarUrl).isEqualTo("https://example.invalid/a.jpg")
    }

    @Test
    fun `an owner with a hidden count has none`() {
        assertThat(watchChannelOf(ownerResponse(null), "en").subscriberCount).isNull()
    }
}
