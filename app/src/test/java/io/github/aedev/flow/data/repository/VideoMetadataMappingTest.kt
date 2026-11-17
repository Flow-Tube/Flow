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
 * The fields [YouTubeRepository] fills from a video's watch page, a related list and a channel
 * page, as plain values. Playlist, history and liked-video repair store exactly these, so a native
 * replacement has to fill the same fields with the same values.
 */
class VideoMetadataMappingTest {
    private val source = NewPipeMetadataSource()
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
            SourceImage("https://yt3.ggpht.com/def=s176-c-k-c0x00ffffff-no-rj", 176, 176),
            SourceImage("https://yt3.ggpht.com/ghi=s48-c-k-c0x00ffffff-no-rj", 48, 48),
        )

    private fun page(
        channelUrl: String? = "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw",
        textual: String? = "Oct 25, 2009",
        uploadedAt: Long? = 1_256_428_800_000L,
    ) = SourceWatchPage(
        title = "Rick Astley - Never Gonna Give You Up",
        channelName = "Rick Astley",
        channelUrl = channelUrl,
        thumbnails =
            listOf(
                SourceImage("https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg", 120, 90),
                SourceImage("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", 1280, 720),
                SourceImage("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", 480, 360),
            ),
        avatars = avatars,
        durationSeconds = 213,
        viewCount = 1_700_000_000,
        textualUploadDate = textual,
        uploadedAtMillis = uploadedAt,
    )

    @Test
    fun `a video's details are read from its watch page`() =
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
                        uploadDate = "Oct 25, 2009",
                        timestamp = 1_256_428_800_000L,
                        channelThumbnailUrl = "https://yt3.ggpht.com/abc=s240-c-k-c0x00ffffff-no-rj",
                        channelThumbnailUrls =
                            listOf(
                                "https://yt3.ggpht.com/abc=s240-c-k-c0x00ffffff-no-rj",
                                "https://yt3.ggpht.com/def=s176-c-k-c0x00ffffff-no-rj",
                            ),
                    ),
                )
        }

    @Test
    fun `a handle URL becomes an at-id and is kept as the handle`() =
        runTest {
            source.serveWatchPage("dQw4w9WgXcQ", page(channelUrl = "https://www.youtube.com/@RickAstleyYT"))

            val video = repository.getVideo("dQw4w9WgXcQ")!!

            assertThat(video.channelId).isEqualTo("@RickAstleyYT")
            assertThat(video.channelHandle).isEqualTo("https://www.youtube.com/@RickAstleyYT")
        }

    @Test
    fun `a page without names or dates falls back to the placeholders`() =
        runTest {
            source.serveWatchPage(
                "dQw4w9WgXcQ",
                page(channelUrl = null, textual = null, uploadedAt = null).copy(title = null, channelName = null, thumbnails = emptyList()),
            )

            val video = repository.getVideo("dQw4w9WgXcQ")!!

            assertThat(video.title).isEqualTo("Unknown Title")
            assertThat(video.channelName).isEqualTo("Unknown Channel")
            assertThat(video.channelId).isEmpty()
            assertThat(video.uploadDate).isEqualTo("Unknown")
            assertThat(video.timestamp).isEqualTo(0L)
            assertThat(video.thumbnailUrl).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg")
        }

    @Test
    fun `a video that cannot be read gives no video`() =
        runTest {
            assertThat(repository.getVideo("aaaaaaaaaaa")).isNull()
            assertThat(source.watchRequests).containsExactly("aaaaaaaaaaa")
        }

    @Test
    fun `repairing N stubs costs one watch request each`() =
        runTest {
            val ids = listOf("dQw4w9WgXcQ", "9bZkp7q19f0", "kJQP7kiw5Fk")
            ids.forEach { source.serveWatchPage(it, page()) }

            ids.forEach { repository.getVideo(it) }

            assertThat(source.watchRequests).containsExactlyElementsIn(ids).inOrder()
        }

    @Test
    fun `a page that needs reloading is asked for once more and then read`() =
        runTest {
            source.serveWatchPageAfterReload("dQw4w9WgXcQ", page())

            val video = repository.getVideo("dQw4w9WgXcQ")

            assertThat(video?.title).isEqualTo("Rick Astley - Never Gonna Give You Up")
            assertThat(source.watchRequests).containsExactly("dQw4w9WgXcQ", "dQw4w9WgXcQ")
        }

    private fun listed(
        url: String,
        kind: SourceKind = SourceKind.VIDEO,
        duration: Long = 245,
        shortForm: Boolean = false,
        textual: String? = "3 days ago",
        uploadedAt: Long? = null,
        title: String = "A video",
        channelName: String = "A channel",
        thumbnailId: String = "x",
    ) = SourceListedVideo(
        url = url,
        title = title,
        channelName = channelName,
        channelUrl = "https://www.youtube.com/channel/UC0000000000000000000001",
        thumbnails = listOf(SourceImage("https://i.ytimg.com/vi/$thumbnailId/hqdefault.jpg", 480, 360)),
        avatars = avatars,
        durationSeconds = duration,
        viewCount = 12_345,
        textualUploadDate = textual,
        uploadedAtMillis = uploadedAt,
        kind = kind,
        shortForm = shortForm,
    )

    @Test
    fun `a related video keeps its id, channel, thumbnail, length and counts`() {
        val video =
            source
                .relatedVideos(
                    repository,
                    listOf(listed("https://www.youtube.com/watch?v=AAAAAAAAAAA&t=10", thumbnailId = "AAAAAAAAAAA")),
                ).single()

        assertThat(video.id).isEqualTo("AAAAAAAAAAA")
        assertThat(video.title).isEqualTo("A video")
        assertThat(video.channelName).isEqualTo("A channel")
        assertThat(video.channelId).isEqualTo("UC0000000000000000000001")
        assertThat(video.thumbnailUrl).isEqualTo("https://i.ytimg.com/vi/AAAAAAAAAAA/hq720.jpg")
        assertThat(video.duration).isEqualTo(245)
        assertThat(video.viewCount).isEqualTo(12_345)
        assertThat(video.uploadDate).isEqualTo("3 days ago")
        assertThat(video.channelThumbnailUrl).isEqualTo("https://yt3.ggpht.com/abc=s240-c-k-c0x00ffffff-no-rj")
        assertThat(video.isLive).isFalse()
        assertThat(video.isShort).isFalse()
        assertThat(video.isUpcoming).isFalse()
    }

    @Test
    fun `ids are read from watch, short and shorts URLs`() {
        val videos =
            source.relatedVideos(
                repository,
                listOf(
                    listed("https://youtu.be/BBBBBBBBBBB?si=x"),
                    listed("https://www.youtube.com/shorts/CCCCCCCCCCC?feature=share", duration = 0),
                    listed("https://www.youtube.com/embed/DDDDDDDDDDD"),
                ),
            )

        assertThat(videos.map { it.id }).containsExactly("BBBBBBBBBBB", "CCCCCCCCCCC", "DDDDDDDDDDD").inOrder()
    }

    @Test
    fun `a Short with no length is given a minute, and a live stream no length at all`() {
        val videos =
            source.relatedVideos(
                repository,
                listOf(
                    listed("https://www.youtube.com/watch?v=EEEEEEEEEEE", duration = 0, shortForm = true),
                    listed("https://www.youtube.com/watch?v=FFFFFFFFFFF", kind = SourceKind.LIVE, duration = 7_200),
                    listed("https://www.youtube.com/watch?v=GGGGGGGGGGG", kind = SourceKind.UPCOMING, duration = 0),
                ),
            )

        assertThat(videos.map { Triple(it.duration, it.isShort, it.isLive) })
            .containsExactly(Triple(60, true, false), Triple(0, false, true), Triple(0, false, false))
            .inOrder()
        assertThat(videos.map { it.isUpcoming }).containsExactly(false, false, true).inOrder()
    }

    @Test
    fun `a listed video with an absolute date only shows it as a short date`() {
        val video =
            source
                .relatedVideos(
                    repository,
                    listOf(listed("https://www.youtube.com/watch?v=HHHHHHHHHHH", textual = null, uploadedAt = 1_256_428_800_000L)),
                ).single()

        assertThat(video.timestamp).isEqualTo(1_256_428_800_000L)
        assertThat(video.uploadDate).isNotEqualTo("Unknown")
    }

    @Test
    fun `a music channel's video is marked music`() {
        val video =
            source
                .relatedVideos(
                    repository,
                    listOf(
                        listed(
                            "https://www.youtube.com/watch?v=IIIIIIIIIII",
                            title = "Song (Official Video)",
                            channelName = "Artist - Topic",
                        ),
                    ),
                ).single()

        assertThat(video.isMusic).isTrue()
    }

    @Test
    fun `related items without an id or repeated are dropped`() {
        val videos =
            source.relatedVideos(
                repository,
                listOf(
                    listed("https://www.youtube.com/watch?v=JJJJJJJJJJJ"),
                    listed("https://www.youtube.com/watch?v=JJJJJJJJJJJ"),
                    listed(""),
                ),
            )

        assertThat(videos.map { it.id }).containsExactly("JJJJJJJJJJJ")
    }

    private val channel =
        SourceChannel(
            id = "UCuAXFkgsw1L7xaCfnd5JJOw",
            name = "RickAstleyYT",
            avatars = avatars,
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
            assertThat(source.channelRequests).containsExactly("https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw")
        }

    @Test
    fun `a channel without an avatar falls back to the watch page's channel`() =
        runTest {
            source.serveChannel(channel.copy(avatars = emptyList()))
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
            repository.getChannelInfo("UCuAXFkgsw1L7xaCfnd5JJOw")
            repository.getChannelInfo("@RickAstleyYT")
            repository.getChannelInfo("RickAstleyYT")
            repository.getChannelInfo(" https://www.youtube.com/c/RickAstley ")

            assertThat(source.channelRequests)
                .containsExactly(
                    "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw",
                    "https://www.youtube.com/@RickAstleyYT",
                    "https://www.youtube.com/@RickAstleyYT",
                    "https://www.youtube.com/c/RickAstley",
                ).inOrder()
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
