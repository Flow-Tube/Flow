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

    @Test
    fun `a Mix lockup becomes a playlist entry in its place`() {
        val results = watch(results = listOf(videoLockup("a"), mixLockup("RDa"), videoLockup("b"))).watchRelatedResults(hl = "en")

        assertThat(results.entries).hasSize(3)
        val mix = results.entries[1] as WatchRelatedEntry.PlaylistCard
        assertThat(mix.playlist.id).isEqualTo("RDa")
        assertThat(mix.playlist.name).isEqualTo("Mix - a")
        assertThat(mix.playlist.isLocal).isFalse()
    }

    @Test
    fun `the Shorts shelf keeps its title and its reels`() {
        val results = watch(results = listOf(reelShelf("Shorts remixing this video", "s1", "s2"))).watchRelatedResults(hl = "en")

        val shelf = results.entries.single() as WatchRelatedEntry.ShortsShelf
        assertThat(shelf.title).isEqualTo("Shorts remixing this video")
        assertThat(shelf.shorts.map { it.id }).containsExactly("s1", "s2").inOrder()
        assertThat(shelf.shorts.all { it.isShort }).isTrue()
    }

    companion object {
        fun continuationItem(token: String) =
            """{"continuationItemRenderer":{"trigger":"CONTINUATION_TRIGGER_ON_ITEM_SHOWN",""" +
                """"continuationEndpoint":{"continuationCommand":{"token":"$token"}}}}"""

        fun mixLockup(id: String) =
            """
            {"lockupViewModel":{"contentId":"$id","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
              "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{
                "image":{"sources":[{"url":"https://i.ytimg.com/vi/x/hqdefault.jpg","width":336,"height":188}]},
                "overlays":[{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[{"thumbnailBadgeViewModel":{"text":"Mix"}}]}}]}}}},
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"Mix - ${id.removePrefix("RD")}"}}}}}
            """.trimIndent()

        fun reelShelf(
            title: String,
            vararg ids: String,
        ) = """{"reelShelfRenderer":{"title":{"runs":[{"text":"$title"}]},"items":[""" +
            ids.joinToString(",") { id ->
                """{"shortsLockupViewModel":{"onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"$id"}}},""" +
                    """"overlayMetadata":{"primaryText":{"content":"Short $id"},"secondaryText":{"content":"3.2M views"}}}}"""
            } + "]}}"

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
