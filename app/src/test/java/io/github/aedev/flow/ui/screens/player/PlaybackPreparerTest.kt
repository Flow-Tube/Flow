package io.github.aedev.flow.ui.screens.player

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.localmedia.LocalSubtitles
import io.github.aedev.flow.data.video.OfflineSubtitleStore
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.sabr.integration.SabrStreamInfo
import io.github.aedev.flow.player.stream.model.StreamType
import io.github.aedev.flow.utils.NetworkState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins the sequence [PlaybackPreparer] drives on one player manager for each way playback can be
 * armed: the same instance, the same calls, in the same order, with the resume position and the
 * remembered speed the preferences ask for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPreparerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val context: Context = mockk(relaxed = true)
    private val playerManager: EnhancedPlayerManager = mockk(relaxed = true)
    private val playerPreferences: PlayerPreferences = mockk(relaxed = true)
    private val offlineSubtitleStore: OfflineSubtitleStore = mockk(relaxed = true)
    private val localSubtitles: LocalSubtitles = mockk(relaxed = true)

    private lateinit var preparer: PlaybackPreparer

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(NetworkState)
        every { NetworkState.isOnWifi(any()) } returns true

        every { playerManager.isPreparedForPlayback(any()) } returns false
        every { playerManager.isReachedByQueueAdvance(any()) } returns false
        every { playerPreferences.rememberPlaybackSpeed } returns flowOf(false)
        every { playerPreferences.playbackSpeed } returns flowOf(1f)
        every { playerPreferences.musicAtNormalSpeed } returns flowOf(false)
        every { playerPreferences.speedPerChannel } returns flowOf(false)
        every { playerManager.playerState } returns MutableStateFlow(fakePlayerState(playbackSpeed = 1.5f))
        every { playerPreferences.autoplayEnabled } returns flowOf(true)
        every { playerPreferences.videoCodecPriority } returns flowOf("auto")
        every { playerPreferences.defaultQualityWifi } returns flowOf(VideoQuality.Q_1080P)
        every { playerPreferences.defaultQualityCellular } returns flowOf(VideoQuality.Q_480P)
        coEvery { offlineSubtitleStore.load(any()) } returns emptyList()
        coEvery { localSubtitles.picked(any()) } returns emptyList()

        preparer = PlaybackPreparer(context, playerManager, playerPreferences)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `a live manifest is pushed from the live edge with the speed locked to 1x`() =
        runTest(testDispatcher) {
            val started =
                preparer.prepareLiveStreams(
                    videoId = VIDEO_ID,
                    hlsUrl = HLS_URL,
                    dashManifestUrl = DASH_URL,
                    subtitles = emptyList(),
                    isCurrent = { true },
                )

            assertThat(started).isTrue()
            coVerifyOrder {
                playerManager.setStreams(
                    videoId = VIDEO_ID,
                    videoStream = null,
                    audioStream = null,
                    videoStreams = emptyList(),
                    audioStreams = emptyList(),
                    subtitles = emptyList(),
                    durationSeconds = 0L,
                    dashManifestUrl = DASH_URL,
                    hlsUrl = HLS_URL,
                    streamType = StreamType.LIVE_STREAM,
                    startPosition = 0L,
                    sabrInfo = null,
                    itVideoFormats = emptyList(),
                    itAudioFormats = emptyList(),
                    preferredVideoCodec = "auto",
                    preferSabr = false,
                    preferredLiveQualityHeight = VideoQuality.Q_1080P.height,
                )
                playerManager.setPlaybackSpeed(1.0f)
                playerManager.play()
            }
        }

    @Test
    fun `an already prepared live video is not pushed again and reports that it did not start`() =
        runTest(testDispatcher) {
            every { playerManager.isPreparedForPlayback(VIDEO_ID) } returns true

            val started =
                preparer.prepareLiveStreams(
                    videoId = VIDEO_ID,
                    hlsUrl = HLS_URL,
                    dashManifestUrl = null,
                    subtitles = emptyList(),
                    isCurrent = { true },
                )

            assertThat(started).isFalse()
            verify(exactly = 0) { playerManager.play() }
        }

    @Test
    fun `the InnerTube VOD path pushes the converted streams at the resumed position`() =
        runTest(testDispatcher) {
            preparer.prepareVodStreams(
                videoId = VIDEO_ID,
                videoStream = null,
                audioStream = null,
                videoStreams = emptyList(),
                audioStreams = emptyList(),
                subtitles = emptyList(),
                durationSeconds = 600L,
                savedPositionMs = 90_000L,
                resumeOverrideRequested = false,
                isAdaptiveMode = false,
                sabrInfo = null,
                itVideoFormats = emptyList(),
                itAudioFormats = emptyList(),
                preferredVideoCodec = "av1",
                preferredLiveQualityHeight = 1080,
                isCurrent = { true },
            )

            coVerifyOrder {
                playerManager.setStreams(
                    videoId = VIDEO_ID,
                    videoStream = null,
                    audioStream = null,
                    videoStreams = emptyList(),
                    audioStreams = emptyList(),
                    subtitles = emptyList(),
                    durationSeconds = 600L,
                    dashManifestUrl = null,
                    hlsUrl = null,
                    streamType = StreamType.VIDEO_STREAM,
                    startPosition = 90_000L,
                    sabrInfo = null,
                    itVideoFormats = emptyList(),
                    itAudioFormats = emptyList(),
                    preferredVideoCodec = "av1",
                    preferSabr = false,
                    preferredLiveQualityHeight = 1080,
                )
                playerManager.play()
            }
            verify(exactly = 0) { playerManager.initialize(any()) }
        }

    @Test
    fun `a SABR session that only ties the direct ladder is not played`() =
        runTest(testDispatcher) {
            // Measured on device 2026-09-21: a SABR session never receives an initialisation
            // segment, so ExoPlayer cannot sniff it and every attempt dies with
            // UnrecognizedInputFormatException. Until that is fixed, playback stays on the direct
            // ladder — which the extractor now hands over cipher-resolved, n-transformed and
            // attested, so it is a real fallback rather than URLs GVS has already refused.
            preparer.prepareVodStreams(
                videoId = VIDEO_ID,
                videoStream = null,
                audioStream = null,
                videoStreams = emptyList(),
                audioStreams = emptyList(),
                subtitles = emptyList(),
                durationSeconds = 600L,
                savedPositionMs = 0L,
                resumeOverrideRequested = false,
                isAdaptiveMode = false,
                sabrInfo = sabrInfo(videoHeight = 0),
                itVideoFormats = emptyList(),
                itAudioFormats = emptyList(),
                preferredVideoCodec = "auto",
                preferredLiveQualityHeight = 1080,
                isCurrent = { true },
            )

            coVerify {
                playerManager.setStreams(
                    videoId = VIDEO_ID,
                    videoStream = any(),
                    audioStream = any(),
                    videoStreams = any(),
                    audioStreams = any(),
                    subtitles = any(),
                    durationSeconds = any(),
                    dashManifestUrl = any(),
                    hlsUrl = any(),
                    streamType = any(),
                    startPosition = any(),
                    sabrInfo = any(),
                    itVideoFormats = any(),
                    itAudioFormats = any(),
                    preferredVideoCodec = any(),
                    preferSabr = false,
                    preferredLiveQualityHeight = any(),
                )
            }
        }

    @Test
    fun `a SABR session taller than the direct ladder is still preferred`() =
        runTest(testDispatcher) {
            preparer.prepareVodStreams(
                videoId = VIDEO_ID,
                videoStream = null,
                audioStream = null,
                videoStreams = emptyList(),
                audioStreams = emptyList(),
                subtitles = emptyList(),
                durationSeconds = 600L,
                savedPositionMs = 0L,
                resumeOverrideRequested = false,
                isAdaptiveMode = false,
                sabrInfo = sabrInfo(videoHeight = 1080),
                itVideoFormats = emptyList(),
                itAudioFormats = emptyList(),
                preferredVideoCodec = "auto",
                preferredLiveQualityHeight = 1080,
                isCurrent = { true },
            )

            coVerify {
                playerManager.setStreams(
                    videoId = VIDEO_ID,
                    videoStream = any(),
                    audioStream = any(),
                    videoStreams = any(),
                    audioStreams = any(),
                    subtitles = any(),
                    durationSeconds = any(),
                    dashManifestUrl = any(),
                    hlsUrl = any(),
                    streamType = any(),
                    startPosition = any(),
                    sabrInfo = any(),
                    itVideoFormats = any(),
                    itAudioFormats = any(),
                    preferredVideoCodec = any(),
                    preferSabr = true,
                    preferredLiveQualityHeight = any(),
                )
            }
        }

    @Test
    fun `a music video starts at 1x and the remembered speed returns on the next video`() =
        runTest(testDispatcher) {
            every { playerPreferences.rememberPlaybackSpeed } returns flowOf(true)
            every { playerPreferences.playbackSpeed } returns flowOf(1.75f)
            every { playerPreferences.musicAtNormalSpeed } returns flowOf(true)

            prepareVod(PlaybackSpeedContext(videoId = VIDEO_ID, channelId = "UC1", knownMusic = true))
            prepareVod(PlaybackSpeedContext(videoId = VIDEO_ID, channelId = "UC1"))

            verifyOrder {
                playerManager.setPlaybackSpeed(1.0f)
                playerManager.setPlaybackSpeed(1.75f)
            }
        }

    @Test
    fun `without a remembered speed the session speed comes back after a music video`() =
        runTest(testDispatcher) {
            every { playerPreferences.musicAtNormalSpeed } returns flowOf(true)

            prepareVod(PlaybackSpeedContext(videoId = VIDEO_ID, channelId = null, knownMusic = true))
            prepareVod(PlaybackSpeedContext.Unknown)

            verifyOrder {
                playerManager.setPlaybackSpeed(1.0f)
                playerManager.setPlaybackSpeed(1.5f)
            }
        }

    @Test
    fun `nothing changes for music while the option is off`() =
        runTest(testDispatcher) {
            prepareVod(PlaybackSpeedContext(videoId = VIDEO_ID, channelId = null, knownMusic = true))

            verify(exactly = 0) { playerManager.setPlaybackSpeed(any()) }
        }

    @Test
    fun `a channel's own speed wins over music and the remembered speed`() =
        runTest(testDispatcher) {
            every { playerPreferences.rememberPlaybackSpeed } returns flowOf(true)
            every { playerPreferences.playbackSpeed } returns flowOf(2f)
            every { playerPreferences.musicAtNormalSpeed } returns flowOf(true)
            every { playerPreferences.speedPerChannel } returns flowOf(true)
            coEvery { playerPreferences.channelPlaybackSpeed("UC1") } returns 1.25f

            prepareVod(PlaybackSpeedContext(videoId = VIDEO_ID, channelId = "UC1", knownMusic = true))

            verify { playerManager.setPlaybackSpeed(1.25f) }
            verify(exactly = 0) { playerManager.setPlaybackSpeed(1.0f) }
        }

    @Test
    fun `a late music category only acts when it calls for an override`() =
        runTest(testDispatcher) {
            preparer.applyLateMusicSignal(PlaybackSpeedContext(videoId = VIDEO_ID, channelId = null))
            verify(exactly = 0) { playerManager.setPlaybackSpeed(any()) }

            every { playerPreferences.musicAtNormalSpeed } returns flowOf(true)
            preparer.applyLateMusicSignal(PlaybackSpeedContext(videoId = VIDEO_ID, channelId = null))
            verify { playerManager.setPlaybackSpeed(1.0f) }
        }

    @Test
    fun `a cached Music category is enough to start at 1x`() =
        runTest(testDispatcher) {
            every { playerPreferences.musicAtNormalSpeed } returns flowOf(true)
            preparer =
                PlaybackPreparer(context, playerManager, playerPreferences) { "Music" }

            prepareVod(PlaybackSpeedContext(videoId = VIDEO_ID, channelId = null))

            verify { playerManager.setPlaybackSpeed(1.0f) }
        }

    private suspend fun prepareVod(speedContext: PlaybackSpeedContext) {
        every { playerManager.isPreparedForPlayback(any()) } returns false
        preparer.prepareVodStreams(
            videoId = VIDEO_ID,
            videoStream = null,
            audioStream = null,
            videoStreams = emptyList(),
            audioStreams = emptyList(),
            subtitles = emptyList(),
            durationSeconds = 600L,
            savedPositionMs = 0L,
            resumeOverrideRequested = false,
            isAdaptiveMode = false,
            sabrInfo = null,
            itVideoFormats = emptyList(),
            itAudioFormats = emptyList(),
            preferredVideoCodec = "auto",
            preferredLiveQualityHeight = 1080,
            isCurrent = { true },
            speedContext = speedContext,
        )
    }

    private fun sabrInfo(videoHeight: Int) =
        SabrStreamInfo(
            streamingUrl = "https://rr1.googlevideo.com/videoplayback?sabr=1",
            audioItag = 140,
            audioLmt = 1L,
            videoItag = 137,
            videoLmt = 2L,
            durationMs = 600_000L,
            videoHeight = videoHeight,
        )

    @Test
    fun `local media is initialised, resumed from the saved position and played`() =
        runTest(testDispatcher) {
            preparer.prepareLocalMedia(
                videoId = VIDEO_ID,
                localFilePath = "/movies/clip.mp4",
                offlineSegments = null,
                savedPosition = 12_000L,
                durationMs = 60_000L,
                subtitles = emptyList(),
                isCurrent = { true },
            )

            verifyOrder {
                playerManager.initialize(context)
                playerManager.playLocalFile(
                    videoId = VIDEO_ID,
                    filePath = "/movies/clip.mp4",
                    savedSegments = null,
                    preservePosition = 12_000L,
                    subtitles = emptyList(),
                )
                playerManager.play()
            }
        }

    @Test
    fun `a load that stopped being current never reaches the player`() =
        runTest(testDispatcher) {
            preparer.prepareLocalMedia(
                videoId = VIDEO_ID,
                localFilePath = "/movies/clip.mp4",
                offlineSegments = null,
                savedPosition = 12_000L,
                durationMs = 60_000L,
                subtitles = emptyList(),
                isCurrent = { false },
            )

            verify(exactly = 0) { playerManager.initialize(any()) }
            verify(exactly = 0) { playerManager.playLocalFile(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { playerManager.play() }
        }

    @Test
    fun `beginSession arms the player and the media notification before any streams`() =
        runTest(testDispatcher) {
            preparer.beginSession(VIDEO_ID, title = "Title", channel = "Channel", thumbnail = "thumb")

            verifyOrder {
                playerManager.initialize(context)
                playerManager.startBackgroundService(VIDEO_ID, "Title", "Channel", "thumb")
            }
        }

    @Test
    fun `autoplay candidates are published with the preference the caller then reuses`() =
        runTest(testDispatcher) {
            every { playerPreferences.autoplayEnabled } returns flowOf(false)

            val enabled = preparer.applyAutoplayCandidates(VIDEO_ID, emptyList())

            assertThat(enabled).isFalse()
            verify(exactly = 1) {
                playerManager.setAutoplayCandidates(sourceVideoId = VIDEO_ID, videos = emptyList(), enabled = false)
            }
        }

    private companion object {
        const val VIDEO_ID = "vid_preparer"
        const val DASH_URL = "https://example.invalid/manifest.mpd"
        const val HLS_URL = "https://example.invalid/live.m3u8"
    }
}
