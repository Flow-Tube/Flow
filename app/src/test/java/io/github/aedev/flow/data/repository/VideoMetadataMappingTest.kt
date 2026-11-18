package io.github.aedev.flow.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.spyk
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The fields [YouTubeRepository] fills for one video and for a channel, as plain values. Playlist,
 * history and liked-video repair store exactly these. They were frozen against NewPipe's StreamInfo
 * and ChannelInfo; where InnerTube answers differently the test says why.
 */
class VideoMetadataMappingTest {
    private val source = InnerTubeMetadataSource()
    private val repository = spyk(YouTubeRepository(mockk<PlayerPreferences>(relaxed = true)))

    @Before
    fun setUp() {
        source.install()
    }

    @After
    fun tearDown() {
        source.uninstall()
    }

    private val avatars =
        listOf(
            SourceImage("https://yt3.ggpht.com/abc=s88-c-k-c0x00ffffff-no-rj", 88, 88),
            SourceImage("https://yt3.ggpht.com/abc=s240-c-k-c0x00ffffff-no-rj", 240, 240),
            SourceImage("https://yt3.ggpht.com/abc=s176-c-k-c0x00ffffff-no-rj", 176, 176),
            SourceImage("https://yt3.ggpht.com/abc=s48-c-k-c0x00ffffff-no-rj", 48, 48),
        )

    private fun page(
        uploadDate: String? = "2009-10-24T23:57:33-07:00",
        dateText: String? = "Oct 25, 2009",
    ) = SourceWatchPage(
        title = "Rick Astley - Never Gonna Give You Up",
        channelName = "Rick Astley",
        channelId = "UCuAXFkgsw1L7xaCfnd5JJOw",
        thumbnails =
            listOf(
                SourceImage("https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg", 120, 90),
                SourceImage("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", 1280, 720),
                SourceImage("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", 480, 360),
            ),
        avatars = avatars,
        durationSeconds = 213,
        viewCount = 1_700_000_000,
        uploadDate = uploadDate,
        dateText = dateText,
    )

    // NewPipe's textual upload date was this same microformat text, and its uploader URL was always
    // /channel/<id>, so the handle stayed null. Its avatar list held the best image of each distinct
    // avatar; the watch page lists one avatar at several sizes, so that is one URL.
    @Test
    fun `a video's details are read from its player and watch responses`() =
        runTest {
            source.serveWatchPage("dQw4w9WgXcQ", page())

            val video = repository.getVideo("dQw4w9WgXcQ")

            assertThat(video)
                .isEqualTo(
                    Video(
                        id = "dQw4w9WgXcQ",
                        title = "Rick Astley - Never Gonna Give You Up",
                        channelName = "Rick Astley",
                        channelId = "UCuAXFkgsw1L7xaCfnd5JJOw",
                        channelHandle = null,
                        thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
                        duration = 213,
                        viewCount = 1_700_000_000,
                        uploadDate = "2009-10-24T23:57:33-07:00",
                        timestamp = 1_256_453_853_000L,
                        channelThumbnailUrl = "https://yt3.ggpht.com/abc=s240-c-k-c0x00ffffff-no-rj",
                        channelThumbnailUrls = listOf("https://yt3.ggpht.com/abc=s240-c-k-c0x00ffffff-no-rj"),
                    ),
                )
        }

    @Test
    fun `a video's category is kept and remembered`() =
        runTest {
            source.serveWatchPage("dQw4w9WgXcQ", page().copy(category = "Music"))

            assertThat(repository.getVideo("dQw4w9WgXcQ")?.category).isEqualTo("Music")
            assertThat(repository.cachedVideoCategory("dQw4w9WgXcQ")).isEqualTo("Music")
        }

    @Test
    fun `without a microformat date the watch page's date line is kept`() =
        runTest {
            source.serveWatchPage("dQw4w9WgXcQ", page(uploadDate = null, dateText = "Oct 25, 2009"))

            val video = repository.getVideo("dQw4w9WgXcQ")!!

            assertThat(video.uploadDate).isEqualTo("Oct 25, 2009")
        }

    @Test
    fun `a page without names or dates falls back to the placeholders`() =
        runTest {
            source.serveWatchPage(
                "dQw4w9WgXcQ",
                page(uploadDate = null, dateText = null).copy(title = null, channelName = null, thumbnails = emptyList()),
            )

            val video = repository.getVideo("dQw4w9WgXcQ")!!

            assertThat(video.title).isEqualTo("Unknown Title")
            assertThat(video.channelName).isEqualTo("Unknown Channel")
            assertThat(video.uploadDate).isEqualTo("Unknown")
            assertThat(video.timestamp).isEqualTo(0L)
            assertThat(video.thumbnailUrl).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg")
        }

    @Test
    fun `a video that cannot be read gives no video`() =
        runTest {
            assertThat(repository.getVideo("aaaaaaaaaaa")).isNull()
            assertThat(source.playerRequests).containsExactly("aaaaaaaaaaa")
        }

    // NewPipe's one StreamInfo fetch was a watch page plus several player requests; this is one
    // player and one watch request, and the watch request is shared with the rest of the app.
    @Test
    fun `repairing N stubs costs one player and one watch request each`() =
        runTest {
            val ids = listOf("dQw4w9WgXcQ", "9bZkp7q19f0", "kJQP7kiw5Fk")
            ids.forEach { source.serveWatchPage(it, page()) }

            ids.forEach { repository.getVideo(it) }

            assertThat(source.playerRequests).containsExactlyElementsIn(ids).inOrder()
            assertThat(source.watchRequests).containsExactlyElementsIn(ids)
        }

    private val channel =
        SourceChannel(
            id = "UCuAXFkgsw1L7xaCfnd5JJOw",
            name = "RickAstleyYT",
            avatarUrl = "https://yt3.ggpht.com/abc=s240-c-k-c0x00ffffff-no-rj",
            tags = listOf("rick astley", "pop", "80s"),
            description = "The official channel",
        )

    private fun cardWithoutAvatar() =
        Video(
            id = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            channelName = "Rick Astley",
            channelId = "UCuAXFkgsw1L7xaCfnd5JJOw",
            thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg",
            duration = 213,
            viewCount = 1L,
            uploadDate = "",
        )

    @Test
    fun `a card missing its avatar gets the channel's largest avatar`() =
        runTest {
            source.serveChannel(channel)

            val enriched = repository.enrichMissingChannelMetadata(listOf(cardWithoutAvatar())).single()

            assertThat(enriched.channelId).isEqualTo("UCuAXFkgsw1L7xaCfnd5JJOw")
            assertThat(enriched.channelName).isEqualTo("RickAstleyYT")
            assertThat(enriched.channelThumbnailUrl).isEqualTo("https://yt3.ggpht.com/abc=s240-c-k-c0x00ffffff-no-rj")
            assertThat(enriched.channelThumbnailUrls).containsExactly("https://yt3.ggpht.com/abc=s240-c-k-c0x00ffffff-no-rj")
            assertThat(source.channelRequests).containsExactly("UCuAXFkgsw1L7xaCfnd5JJOw")
        }

    @Test
    fun `a channel without an avatar falls back to the watch page's channel`() =
        runTest {
            source.serveChannel(channel.copy(avatarUrl = ""))
            coEvery { repository.getLiveWatchMetadata("dQw4w9WgXcQ") } returns
                YouTubeRepository.LiveWatchMetadata(
                    title = null,
                    channelName = "Rick Astley",
                    channelId = "UCuAXFkgsw1L7xaCfnd5JJOw",
                    channelAvatarUrl = "https://yt3.ggpht.com/watch=s176",
                    subscriberCount = null,
                    viewCount = null,
                    description = null,
                    relatedVideos = emptyList(),
                )

            val enriched = repository.enrichMissingChannelMetadata(listOf(cardWithoutAvatar())).single()

            assertThat(enriched.channelThumbnailUrl).isEqualTo("https://yt3.ggpht.com/watch=s176")
            assertThat(enriched.channelName).isEqualTo("Rick Astley")
        }

    @Test
    fun `with no avatar anywhere the channel header is asked last`() =
        runTest {
            coEvery { repository.getLiveWatchMetadata(any()) } returns null
            coEvery { repository.fetchChannelAvatarById("UCuAXFkgsw1L7xaCfnd5JJOw") } returns "https://yt3.ggpht.com/header=s900"

            val enriched = repository.enrichMissingChannelMetadata(listOf(cardWithoutAvatar())).single()

            assertThat(enriched.channelThumbnailUrl).isEqualTo("https://yt3.ggpht.com/header=s900")
            coVerify(exactly = 1) { repository.fetchChannelAvatarById("UCuAXFkgsw1L7xaCfnd5JJOw") }
        }

    @Test
    fun `channels are asked for by id, handle or name`() =
        runTest {
            source.serveChannel(channel.copy(handle = "RickAstleyYT"))

            repository.getChannelInfo("UCuAXFkgsw1L7xaCfnd5JJOw")
            repository.getChannelInfo("@RickAstleyYT")
            repository.getChannelInfo("RickAstleyYT")
            repository.getChannelInfo(" https://www.youtube.com/c/RickAstley ")

            assertThat(source.resolvedLinks)
                .containsExactly(
                    "https://www.youtube.com/@RickAstleyYT",
                    "https://www.youtube.com/@RickAstleyYT",
                    "https://www.youtube.com/c/RickAstley",
                ).inOrder()
            // A link that resolves to no channel costs no browse.
            assertThat(source.channelRequests)
                .containsExactly("UCuAXFkgsw1L7xaCfnd5JJOw", "UCuAXFkgsw1L7xaCfnd5JJOw", "UCuAXFkgsw1L7xaCfnd5JJOw")
                .inOrder()
        }

    @Test
    fun `a channel's own tags and description reach the recommendation engine`() =
        runTest {
            source.serveChannel(channel)
            val context = mockk<Context>()
            mockkObject(FlowNeuroEngine.Companion)
            try {
                coEvery { FlowNeuroEngine.onChannelTagsLearned(any(), any(), any(), any()) } just runs

                repository.learnChannelTags(context, "UCuAXFkgsw1L7xaCfnd5JJOw")

                coVerify(exactly = 1) {
                    FlowNeuroEngine.onChannelTagsLearned(
                        context,
                        "UCuAXFkgsw1L7xaCfnd5JJOw",
                        listOf("rick astley", "pop", "80s"),
                        "The official channel",
                    )
                }
            } finally {
                unmockkObject(FlowNeuroEngine.Companion)
            }
        }

    @Test
    fun `a channel with no tags and no description teaches nothing`() =
        runTest {
            source.serveChannel(channel.copy(tags = emptyList(), description = " "))
            mockkObject(FlowNeuroEngine.Companion)
            try {
                coEvery { FlowNeuroEngine.onChannelTagsLearned(any(), any(), any(), any()) } just runs

                repository.learnChannelTags(mockk(), "UCuAXFkgsw1L7xaCfnd5JJOw")
                repository.learnChannelTags(mockk(), "UCunknown")

                coVerify(exactly = 0) { FlowNeuroEngine.onChannelTagsLearned(any(), any(), any(), any()) }
            } finally {
                unmockkObject(FlowNeuroEngine.Companion)
            }
        }
}
