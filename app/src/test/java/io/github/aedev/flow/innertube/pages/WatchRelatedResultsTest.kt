package io.github.aedev.flow.innertube.pages

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test

class WatchRelatedResultsTest {
    @Test
    fun `the plain autoplay set names the next video`() {
        val watch =
            watch(
                autoplay =
                    """[{"mode":"SHUFFLE","autoplayVideo":{"watchEndpoint":{"videoId":"shuffled"}}},""" + autoplaySet("pick") + "]",
            )

        assertThat(watch.watchUpNextVideoId()).isEqualTo("pick")
    }

    @Test
    fun `no autoplay block means no pick`() {
        assertThat(watch().watchUpNextVideoId()).isNull()
    }

    @Test
    fun `video lockups keep the order YouTube lists them in`() {
        val results = watch(results = listOf(videoLockup("a"), videoLockup("b"))).watchRelatedResults(hl = "en")

        assertThat(results.entries.flatMap { (it as WatchRelatedEntry.Videos).videos.map { video -> video.videoId } })
            .containsExactly("a", "b")
            .inOrder()
    }

    @Test
    fun `an item section is read through`() {
        val section = """{"itemSectionRenderer":{"contents":[${videoLockup("a")},${videoLockup("b")}]}}"""

        val results = watch(results = listOf(section)).watchRelatedResults(hl = "en")

        assertThat(results.entries).hasSize(2)
    }

    @Test
    fun `the first page keeps the token for the next one`() {
        val results = watch(results = listOf(videoLockup("a"), continuationItem("next"))).watchRelatedResults(hl = "en")

        assertThat(results.continuation).isEqualTo("next")
        assertThat(results.entries).hasSize(1)
    }

    @Test
    fun `a continuation page appends its videos and hands on the following token`() {
        val response =
            Json.parseToJsonElement(
                """
                {"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"targetId":"watch-next-feed",
                  "continuationItems":[${videoLockup("c")},${videoLockup("d")},${continuationItem("after")}]}}]}
                """.trimIndent(),
            )

        val results = response.relatedContinuationResults(hl = "en")

        assertThat(results.entries.flatMap { (it as WatchRelatedEntry.Videos).videos.map { video -> video.videoId } })
            .containsExactly("c", "d")
            .inOrder()
        assertThat(results.continuation).isEqualTo("after")
    }

    @Test
    fun `the last page carries no token`() {
        val response =
            Json.parseToJsonElement(
                """{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[${videoLockup("z")}]}}]}""",
            )

        assertThat(response.relatedContinuationResults(hl = "en").continuation).isNull()
    }

    companion object {
        fun continuationItem(token: String) =
            """{"continuationItemRenderer":{"trigger":"CONTINUATION_TRIGGER_ON_ITEM_SHOWN",""" +
                """"continuationEndpoint":{"continuationCommand":{"token":"$token"}}}}"""

        fun autoplaySet(videoId: String) = """{"mode":"NORMAL","autoplayVideo":{"watchEndpoint":{"videoId":"$videoId"}}}"""

        fun videoLockup(id: String) =
            """
            {"lockupViewModel":{"contentId":"$id","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"Title $id"}}}}}
            """.trimIndent()

        fun watch(
            results: List<String> = emptyList(),
            autoplay: String? = null,
        ): JsonElement {
            val autoplayBlock = autoplay?.let { ""","autoplay":{"autoplay":{"sets":$it}}""" }.orEmpty()
            return Json.parseToJsonElement(
                """
                {"contents":{"twoColumnWatchNextResults":{
                  "secondaryResults":{"secondaryResults":{"results":[${results.joinToString(",")}]}}$autoplayBlock
                }}}
                """.trimIndent(),
            )
        }
    }
}
