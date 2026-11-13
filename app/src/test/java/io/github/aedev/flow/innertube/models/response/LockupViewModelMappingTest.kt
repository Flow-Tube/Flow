package io.github.aedev.flow.innertube.models.response

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Exercised against a captured watch-next payload rather than a hand-built fixture: the fields this
 * mapper reads moved when YouTube replaced `compactVideoRenderer` with `lockupViewModel`, and a
 * fixture written from the model would have kept passing while the real shape drifted.
 */
class LockupViewModelMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun response(): WatchMetadataResponse =
        javaClass.classLoader!!
            .getResourceAsStream("watch_next_related_lockups.json")!!
            .bufferedReader()
            .use { json.decodeFromString(it.readText()) }

    @Test
    fun `related lockups deserialize into compact videos`() {
        val videos = response().relatedVideos()

        assertThat(videos.map { it.videoId }).containsExactly("DdNbdhWmX04", "i_nj_vkD03g").inOrder()
    }

    @Test
    fun `channel id comes from the avatar browse endpoint`() {
        val videos = response().relatedVideos()

        assertThat(videos.map { it.channelId() })
            .containsExactly("UCOMwpjGPUrGGUS2Diq6UmVw", "UCXuqSBlHAE6Xw-yeJA0Tunw")
            .inOrder()
    }

    @Test
    fun `channel handle and avatar are carried through`() {
        val first = response().relatedVideos().first()

        assertThat(first.channelHandle).isEqualTo("/@ElHusamoX")
        assertThat(first.channelAvatarUrl).contains("yt3.ggpht.com")
    }

    @Test
    fun `view count is taken from the part with the play-arrow icon`() {
        val videos = response().relatedVideos()

        assertThat(videos.map { it.viewCountText?.text() }).containsExactly("342K", "1.6M").inOrder()
    }

    @Test
    fun `byline is the channel name and never the view count`() {
        val videos = response().relatedVideos()

        assertThat(videos.map { it.longBylineText?.text() })
            .containsExactly("El Husamo X", "Linus Tech Tips")
            .inOrder()
    }

    @Test
    fun `published text and duration survive the mapping`() {
        val first = response().relatedVideos().first()

        assertThat(first.publishedTimeText?.text()).isEqualTo("18h ago")
        assertThat(first.lengthText?.text()).isEqualTo("21:51")
    }

    private fun singleLockup(
        byline: String,
        views: String,
        age: String,
    ): WatchMetadataResponse =
        json.decodeFromString(
            """{"contents":{"twoColumnWatchNextResults":{"secondaryResults":{"secondaryResults":{"results":[""" +
                """{"lockupViewModel":{"contentId":"abcdefghijk","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",""" +
                """"metadata":{"lockupMetadataViewModel":{"title":{"content":"Title"},""" +
                """"metadata":{"contentMetadataViewModel":{"metadataRows":[""" +
                """{"metadataParts":[{"text":{"content":"$byline"}}]},""" +
                """{"metadataParts":[{"text":{"content":"$views"},"leadingIcon":{"name":"PLAY_ARROW_OUTLINED"}},""" +
                """{"text":{"content":"$age"}}]}""" +
                """]}}}}}}]}}}}}""",
        )

    @Test
    fun `an abbreviated age beside the view count is kept as the published text`() {
        val spanish = singleLockup(byline = "Nostalgia Grupera", views = "234 K", age = "hace 3 m").relatedVideos("es").single()
        val russian = singleLockup(byline = "Natural Life TV", views = "3,1 тыс.", age = "5 дн. назад").relatedVideos("ru").single()

        assertThat(spanish.publishedTimeText?.text()).isEqualTo("hace 3 m")
        assertThat(spanish.longBylineText?.text()).isEqualTo("Nostalgia Grupera")
        assertThat(spanish.viewCountText?.text()).isEqualTo("234 K")
        assertThat(russian.publishedTimeText?.text()).isEqualTo("5 дн. назад")
        assertThat(russian.longBylineText?.text()).isEqualTo("Natural Life TV")
    }

    @Test
    fun `an unrecognised age is still taken by its place beside the view count`() {
        val video = singleLockup(byline = "Some Channel", views = "12 K", age = "3 kk sitten").relatedVideos("xx").single()

        assertThat(video.publishedTimeText?.text()).isEqualTo("3 kk sitten")
        assertThat(video.longBylineText?.text()).isEqualTo("Some Channel")
    }

    @Test
    fun `a live item takes no published text from beside its viewer count`() {
        val video = singleLockup(byline = "Some Channel", views = "1.2K watching", age = "Live").relatedVideos("en").single()

        assertThat(video.isLive).isTrue()
        assertThat(video.publishedTimeText?.text().isNullOrBlank()).isTrue()
    }

    @Test
    fun `a compact video renderer still resolves its channel id from the byline`() {
        val legacy =
            WatchMetadataResponse.CompactVideo(
                videoId = "legacy",
                longBylineText =
                    WatchMetadataResponse.Runs(
                        runs =
                            listOf(
                                WatchMetadataResponse.Runs.Run(
                                    text = "Some Channel",
                                    navigationEndpoint =
                                        WatchMetadataResponse.NavEndpoint(
                                            browseEndpoint =
                                                WatchMetadataResponse.NavEndpoint.BrowseEndpoint(
                                                    browseId = "UCLegacyChannelId",
                                                ),
                                        ),
                                ),
                            ),
                    ),
            )

        assertThat(legacy.channelId()).isEqualTo("UCLegacyChannelId")
    }
}
