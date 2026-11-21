package io.github.aedev.flow.player

import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.player.resolver.Media3JvmShims
import io.github.aedev.flow.player.resolver.SourceShape
import io.github.aedev.flow.player.resolver.shape
import io.github.aedev.flow.player.sabr.integration.SabrStreamInfo
import io.github.aedev.flow.player.stream.PlaybackTestStreams.LIVE
import io.github.aedev.flow.player.stream.PlaybackTestStreams.POST_LIVE
import io.github.aedev.flow.player.stream.PlaybackTestStreams.VOD
import io.github.aedev.flow.player.stream.PlaybackTestStreams.h264
import io.github.aedev.flow.player.stream.PlaybackTestStreams.h264Format
import io.github.aedev.flow.player.stream.PlaybackTestStreams.m4a
import io.github.aedev.flow.player.stream.PlaybackTestStreams.opus
import io.github.aedev.flow.player.stream.PlaybackTestStreams.videoFormat
import io.github.aedev.flow.player.stream.PlaybackTestStreams.vp9
import io.github.aedev.flow.player.stream.PlaybackTestStreams.vp9Format
import io.github.aedev.flow.player.stream.ResolvedStreamData
import io.github.aedev.flow.player.stream.TestAudioStream
import io.github.aedev.flow.player.stream.TestStreamType
import io.github.aedev.flow.player.stream.TestVideoStream
import io.github.aedev.flow.player.stream.testHeight
import io.github.aedev.flow.player.stream.testTrackId
import io.github.aedev.flow.player.stream.testUrl
import io.mockk.every
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * What the video player keeps when a video's streams arrive, and what it hands the player: the
 * stream Auto or the viewer picked, the quality and audio rows, the cast lists, and the media
 * source, through a fresh load, a gapless promotion and the two reload paths error recovery uses.
 */
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class EnhancedPlayerManagerStreamsTest {
    private val dispatcher = StandardTestDispatcher()

    private val h360 = h264(360, 700_000)
    private val h480 = h264(480, 1_200_000)
    private val h720 = h264(720, 2_500_000)
    private val h1080 = h264(1080, 4_500_000)
    private val ladder = listOf(h1080, h360, h720, h480, h720)

    private val english = opus(trackId = "en.4", trackName = "English original", isDefault = true)
    private val englishM4a = m4a(trackId = "en.4", trackName = "English original", isDefault = true)
    private val french = opus(trackId = "fr.3", trackName = "French", isDefault = false, isAutoDubbed = true)

    private lateinit var harness: PlayerManagerHarness

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        Media3JvmShims.install()
        harness = PlayerManagerHarness()
    }

    @After
    fun tearDown() {
        Media3JvmShims.uninstall()
        Dispatchers.resetMain()
    }

    private val lastSource: SourceShape get() = harness.loadedSources.last().shape()!!

    private fun SourceShape.videoUrls() =
        children
            .first()
            .sideloadedManifest!!
            .representations
            .map { it.baseUrl }

    private fun runStreams(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private suspend fun setStreams(
        videoStream: TestVideoStream? = null,
        audioStream: TestAudioStream? = english,
        videoStreams: List<TestVideoStream> = ladder,
        audioStreams: List<TestAudioStream> = listOf(english, englishM4a, french),
        dashUrl: String? = null,
        hlsUrl: String? = null,
        streamType: TestStreamType? = VOD,
        localFilePath: String? = null,
        sabrInfo: SabrStreamInfo? = null,
        itVideoFormats: List<io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format> = emptyList(),
        startPosition: Long = 0L,
    ) = harness.manager.setStreams(
        videoId = "vid_1",
        videoStream = videoStream,
        audioStream = audioStream,
        videoStreams = videoStreams,
        audioStreams = audioStreams,
        subtitles = emptyList(),
        durationSeconds = 212,
        dashManifestUrl = dashUrl,
        localFilePath = localFilePath,
        hlsUrl = hlsUrl,
        streamType = streamType,
        startPosition = startPosition,
        sabrInfo = sabrInfo,
        itVideoFormats = itVideoFormats,
        preferredVideoCodec = "auto",
    )

    @Test
    fun `in Auto the streams are deduplicated, Auto picks by bandwidth and Media3 gets the whole ladder`() =
        runStreams {
            setStreams()

            assertThat(harness.availableVideoStreams.map { it.testHeight }).containsExactly(1080, 720, 480, 360).inOrder()
            assertThat(harness.currentVideoStream!!.testUrl).isEqualTo(h720.testUrl)
            assertThat(harness.quality.isAdaptiveQualityEnabled).isTrue()
            assertThat(harness.quality.isAdaptiveLadderActive).isTrue()
            assertThat(lastSource.videoUrls()).containsExactly(h360.testUrl, h480.testUrl, h720.testUrl, h1080.testUrl).inOrder()
            assertThat(lastSource.children[1].uri).isEqualTo(english.testUrl)

            val state = harness.manager.playerState.value
            assertThat(state.currentVideoId).isEqualTo("vid_1")
            assertThat(state.currentQuality).isEqualTo(0)
            assertThat(state.currentQualityKey).isNull()
            assertThat(state.effectiveQuality).isEqualTo(720)
            assertThat(state.availableQualities.map { it.label })
                .containsExactly("Auto", "1080p H264", "720p H264", "480p H264", "360p H264")
                .inOrder()
            assertThat(state.isPrepared).isTrue()
            assertThat(state.isLive).isFalse()
        }

    @Test
    fun `a picked stream is pinned and loaded alone`() =
        runStreams {
            setStreams(videoStream = h480, startPosition = 33_000L)

            assertThat(harness.quality.isAdaptiveQualityEnabled).isFalse()
            assertThat(harness.quality.manualQualityHeight).isEqualTo(480)
            assertThat(harness.quality.isAdaptiveLadderActive).isFalse()
            assertThat(lastSource.videoUrls()).containsExactly(h480.testUrl)
            verify { harness.player.seekTo(33_000L) }

            val state = harness.manager.playerState.value
            assertThat(state.currentQuality).isEqualTo(480)
            assertThat(state.effectiveQuality).isEqualTo(480)
            assertThat(state.currentQualityKey).isEqualTo(h480.testUrl)
        }

    @Test
    fun `audio rows are one per track and the current one follows the picked dub`() =
        runStreams {
            setStreams(audioStream = french)

            val state = harness.manager.playerState.value
            assertThat(state.availableAudioTracks.map { it.label }).containsExactly("English original", "French").inOrder()
            assertThat(state.currentAudioTrack).isEqualTo(1)
            assertThat(harness.currentAudioStream!!.testTrackId).isEqualTo("fr.3")
            assertThat(lastSource.children[1].uri).isEqualTo(french.testUrl)
        }

    @Test
    fun `casting gets every video stream and every format of the current audio track`() =
        runStreams {
            setStreams(audioStream = english)

            assertThat(harness.manager.getCastVideoStreams().map { it.testUrl })
                .containsExactly(h1080.testUrl, h720.testUrl, h480.testUrl, h360.testUrl)
                .inOrder()
            assertThat(harness.manager.getCastAudioStreams().map { it.testUrl }).containsExactly(english.testUrl, englishM4a.testUrl)

            harness.set("currentAudioStream", french)
            assertThat(harness.manager.getCastAudioStreams().map { it.testUrl }).containsExactly(french.testUrl)
        }

    @Test
    fun `without separate audio the video still loads`() =
        runStreams {
            setStreams(audioStream = null, audioStreams = emptyList(), videoStream = h720)

            assertThat(lastSource.kind).isEqualTo("dash")
            assertThat(
                lastSource.sideloadedManifest!!
                    .representations
                    .single()
                    .baseUrl,
            ).isEqualTo(h720.testUrl)
        }

    @Test
    fun `a live stream plays its HLS playlist and the player turns live`() =
        runStreams {
            setStreams(hlsUrl = "https://m/live.m3u8", dashUrl = "https://m/live.mpd", streamType = LIVE)

            assertThat(lastSource.kind).isEqualTo("hls")
            assertThat(lastSource.uri).isEqualTo("https://m/live.m3u8")
            assertThat(harness.manager.playerState.value.isLive).isTrue()
            assertThat(harness.quality.isAdaptiveLadderActive).isFalse()
        }

    @Test
    fun `a stream that just ended plays YouTube's DASH manifest when it has no playlist`() =
        runStreams {
            setStreams(dashUrl = "https://m/live.mpd", streamType = POST_LIVE)

            assertThat(lastSource.kind).isEqualTo("dash")
            assertThat(lastSource.uri).isEqualTo("https://m/live.mpd")
            assertThat(lastSource.sideloadedManifest).isNull()
            assertThat(harness.manager.playerState.value.isLive).isTrue()
        }

    @Test
    fun `an HLS URL on a video that is not live is dropped`() =
        runStreams {
            setStreams(hlsUrl = "https://m/live.m3u8", streamType = VOD)

            assertThat(harness.get("currentHlsUrl")).isNull()
            assertThat(lastSource.kind).isEqualTo("merging")
            assertThat(harness.manager.playerState.value.isLive).isFalse()
        }

    @Test
    fun `a downloaded file plays from disk without a ladder`() =
        runStreams {
            setStreams(
                videoStreams = emptyList(),
                audioStreams = emptyList(),
                audioStream = null,
                localFilePath = "/storage/Flow/vid_1.mp4",
            )

            assertThat(lastSource.kind).isEqualTo("progressive")
            assertThat(lastSource.uri).isEqualTo("file:///storage/Flow/vid_1.mp4")
            assertThat(harness.quality.isAdaptiveLadderActive).isFalse()
        }

    @Test
    fun `the source aspect ratio is the largest rendition's, InnerTube formats and streams alike`() =
        runStreams {
            setStreams(itVideoFormats = listOf(videoFormat(400, 1440, "video/mp4; codecs=\"av01.0.12M.08\"", 9_000_000, width = 3440)))
            assertThat(harness.manager.playerState.value.sourceVideoAspectRatio).isWithin(0.001f).of(3440f / 1440f)

            setStreams()
            assertThat(harness.manager.playerState.value.sourceVideoAspectRatio).isWithin(0.001f).of(1920f / 1080f)
        }

    @Test
    fun `a SABR session adds its own rows for heights the extracted streams lack`() =
        runStreams {
            val sabr =
                SabrStreamInfo(
                    streamingUrl = "https://sabr.invalid",
                    audioItag = 251,
                    audioLmt = 1,
                    videoItag = 271,
                    videoLmt = 1,
                    durationMs = 212_000,
                )
            val formats = listOf(vp9Format(1440, 9_000_000, fps = 60), h264Format(720, 2_500_000))

            setStreams(sabrInfo = sabr, itVideoFormats = formats)

            val rows = harness.manager.playerState.value.availableQualities
            val sabrRow = rows.single { it.streamKey == "sabr:271" }
            assertThat(sabrRow.label).isEqualTo("1440p60 VP9")
            assertThat(sabrRow.height).isEqualTo(1440)
            assertThat(rows.map { it.height }).containsExactly(0, 1440, 1080, 720, 480, 360).inOrder()
        }

    private fun resolved(
        id: String,
        pinned: TestVideoStream? = null,
        videoStreams: List<TestVideoStream> = ladder,
        audio: TestAudioStream? = english,
    ) = ResolvedStreamData(
        enrichedVideo =
            Video(
                id = id,
                title = "Video $id",
                channelName = "Channel",
                channelId = "UC1",
                thumbnailUrl = "",
                duration = 212,
                viewCount = 1L,
                uploadDate = "",
            ),
        videoStream = pinned,
        audioStream = audio,
        videoStreams = videoStreams,
        audioStreams = listOf(english, french),
        subtitles = emptyList(),
        durationSeconds = 212,
        dashManifestUrl = null,
        streamType = VOD,
        preferredCodec = "auto",
        itVideoFormats = emptyList(),
        itAudioFormats = emptyList(),
    )

    @Test
    fun `a promoted preload in Auto plays on its ladder from the top rung`() =
        runStreams {
            setStreams()
            every { harness.player.currentMediaItemIndex } returns 1
            harness.preload(resolved("vid_2"))

            harness.promotePreloadedItem()

            assertThat(harness.currentVideoId).isEqualTo("vid_2")
            assertThat(harness.currentVideoStream!!.testUrl).isEqualTo(h1080.testUrl)
            assertThat(harness.availableVideoStreams.map { it.testHeight }).containsExactly(1080, 720, 480, 360).inOrder()
            assertThat(harness.quality.isAdaptiveLadderActive).isTrue()
            assertThat(harness.quality.isAdaptiveQualityEnabled).isTrue()
            verify { harness.player.removeMediaItem(0) }

            val state = harness.manager.playerState.value
            assertThat(state.currentVideoId).isEqualTo("vid_2")
            assertThat(state.currentQuality).isEqualTo(0)
            assertThat(state.currentQualityKey).isNull()
            assertThat(state.effectiveQuality).isEqualTo(1080)
            assertThat(state.availableQualities.map { it.height }).containsExactly(0, 1080, 720, 480, 360).inOrder()
            assertThat(state.availableAudioTracks.map { it.label }).containsExactly("English original", "French").inOrder()
            assertThat(state.isPrepared).isTrue()
        }

    @Test
    fun `a promoted preload with a pinned quality keeps the pin`() =
        runStreams {
            setStreams()
            harness.preload(resolved("vid_2", pinned = h480))

            harness.promotePreloadedItem()

            assertThat(harness.currentVideoStream!!.testUrl).isEqualTo(h480.testUrl)
            assertThat(harness.quality.isAdaptiveLadderActive).isFalse()
            assertThat(harness.quality.manualQualityHeight).isEqualTo(480)
            val state = harness.manager.playerState.value
            assertThat(state.currentQuality).isEqualTo(480)
            assertThat(state.currentQualityKey).isEqualTo(h480.testUrl)
        }

    @Test
    fun `promotion without a preload does nothing`() =
        runStreams {
            setStreams()

            harness.promotePreloadedItem()

            assertThat(harness.currentVideoId).isEqualTo("vid_1")
        }

    @Test
    fun `a stream reload stops, clears and loads the current stream again at the position`() =
        runStreams {
            setStreams(videoStream = h720)
            val loads = harness.loadedSources.size

            harness.reloadCurrentStream(48_000L, "test")

            verifyOrder {
                harness.player.stop()
                harness.player.clearMediaItems()
                harness.player.seekTo(48_000L)
            }
            assertThat(harness.loadedSources).hasSize(loads + 1)
            assertThat(lastSource.videoUrls()).containsExactly(h720.testUrl)
            assertThat(lastSource.children[1].uri).isEqualTo(english.testUrl)
        }

    @Test
    fun `a stream reload without a current stream does nothing`() =
        runStreams {
            harness.reloadCurrentStream(0L, "test")

            assertThat(harness.loadedSources).isEmpty()
        }

    @Test
    fun `a delayed reload in Auto moves off a failed stream to the tallest working one`() =
        runStreams {
            setStreams()
            harness.quality.markStreamFailed(h720.testUrl)
            every { harness.player.currentPosition } returns 20_000L

            harness.reloadPlaybackManager()
            advanceTimeBy(PlayerConfig.ERROR_RETRY_DELAY_MS + 1)
            runCurrent()

            assertThat(harness.currentVideoStream!!.testUrl).isEqualTo(h1080.testUrl)
            assertThat(harness.quality.streamErrorCount).isEqualTo(0)
            verify { harness.player.seekTo(20_000L) }
        }

    @Test
    fun `a delayed reload with a pinned quality reloads the pinned stream`() =
        runStreams {
            setStreams(videoStream = h480)
            every { harness.player.currentPosition } returns 5_000L
            val loads = harness.loadedSources.size

            harness.reloadPlaybackManager()
            runCurrent()
            assertThat(harness.loadedSources).hasSize(loads)
            advanceTimeBy(PlayerConfig.ERROR_RETRY_DELAY_MS + 1)
            runCurrent()

            assertThat(harness.loadedSources).hasSize(loads + 1)
            assertThat(lastSource.videoUrls()).containsExactly(h480.testUrl)
            verify { harness.player.seekTo(5_000L) }
        }

    @Test
    fun `a quality switch reloads through the manager at the same position`() =
        runStreams {
            setStreams(videoStream = h720)

            harness.quality.switchQualityByHeight(1080, 70_000L)

            assertThat(harness.currentVideoStream!!.testUrl).isEqualTo(h1080.testUrl)
            assertThat(lastSource.videoUrls()).containsExactly(h1080.testUrl)
            verify { harness.player.seekTo(70_000L) }
        }

    @Test
    fun `a VP9 ladder stays separate from H264 when Auto starts on VP9`() =
        runStreams {
            val vp9s = listOf(vp9(360, 600_000), vp9(720, 2_000_000), vp9(1080, 3_800_000))
            harness.manager.setStreams(
                videoId = "vid_1",
                videoStream = null,
                audioStream = english,
                videoStreams = ladder + vp9s,
                audioStreams = listOf(english),
                subtitles = emptyList(),
                durationSeconds = 212,
                streamType = VOD,
                preferredVideoCodec = "vp9",
            )

            assertThat(lastSource.videoUrls()).containsExactlyElementsIn(vp9s.map { it.testUrl })
        }
}
