package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.error.PlayerDiagnostics
import io.github.aedev.flow.player.stream.model.StreamType
import io.github.aedev.flow.utils.NetworkState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins what [PlaybackLoadResolver] hands back for each way a load can end, with the InnerTube
 * extractor driven from the same fakes the player-screen characterisation harness uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackLoadResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val repository: YouTubeRepository = mockk(relaxed = true)
    private val viewHistory: ViewHistory = mockk(relaxed = true)
    private val playerPreferences: PlayerPreferences = mockk(relaxed = true)
    private val videoDownloadManager: VideoDownloadManager = mockk(relaxed = true)
    private val sponsorBlockRepository: SponsorBlockRepository = mockk(relaxed = true)
    private var localCopy: DownloadedVideo? = null

    private lateinit var resolver: PlaybackLoadResolver

    @Before
    fun setUp() {
        mockkObject(InnerTubeVideoStreamExtractor)
        coEvery { InnerTubeVideoStreamExtractor.extract(any(), any()) } returns null

        mockkObject(NetworkState)
        every { NetworkState.isOnline(any()) } returns true

        mockkObject(PlayerDiagnostics)
        every { PlayerDiagnostics.logWarning(any(), any()) } just Runs

        coEvery { videoDownloadManager.findLocalCopy(any()) } answers { localCopy }
        coEvery { videoDownloadManager.getSponsorBlockData(any()) } returns null
        every { playerPreferences.defaultQualityWifi } returns flowOf(VideoQuality.AUTO)
        every { playerPreferences.defaultQualityCellular } returns flowOf(VideoQuality.AUTO)
        every { playerPreferences.preferredAudioLanguage } returns flowOf("original")
        every { playerPreferences.preferredSubtitleLanguage } returns flowOf(CaptionTrackResolver.NO_PREFERRED_LANGUAGE)
        every { playerPreferences.videoCodecPriority } returns flowOf("auto")
        every { playerPreferences.autoplayEnabled } returns flowOf(true)
        every { viewHistory.getPlaybackPosition(any()) } returns flowOf(0L)

        resolver =
            PlaybackLoadResolver(
                repository = repository,
                viewHistory = viewHistory,
                playerPreferences = playerPreferences,
                videoDownloadManager = videoDownloadManager,
                sponsorBlockRepository = sponsorBlockRepository,
                networkDispatcher = testDispatcher,
                ioDispatcher = testDispatcher,
            )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a playable result resolves in one step, with the lane left to the watch response`() =
        runTest(testDispatcher) {
            coEvery { InnerTubeVideoStreamExtractor.extract(any(), any()) } returns playableInnerTubeResult()

            val steps = resolveSteps().second
            advanceUntilIdle()

            val vod = steps.single() as ResolvedPlayback.VodFromInnerTube
            assertThat(vod.preferredQuality).isEqualTo(VideoQuality.AUTO)
            assertThat(vod.relatedVideos).isEmpty()
        }

    @Test
    fun `a forced SABR reload retries the full client ladder once before giving up`() =
        runTest(testDispatcher) {
            val steps = resolveSteps(escalateToSabr = true).second
            advanceUntilIdle()

            val failure = steps.single() as ResolvedPlayback.Failed
            assertThat(failure.failure).isEqualTo(PlaybackFailure.EXTRACTION)
            assertThat(failure.cause).isNull()
            assertThat(failure.relatedVideos).isNull()
            coVerify(exactly = 1) { InnerTubeVideoStreamExtractor.extract(VIDEO_ID, forceSabr = true) }
            coVerify(exactly = 1) { InnerTubeVideoStreamExtractor.extract(VIDEO_ID, forceSabr = false) }
        }

    @Test
    fun `a downloaded copy is handed over before resolution and ends the load when offline`() =
        runTest(testDispatcher) {
            val file = temporaryFolder.newFile("$VIDEO_ID.mp4")
            localCopy = DownloadedVideo(video = downloadedVideo(), filePath = file.absolutePath)
            every { NetworkState.isOnline(any()) } returns false

            val steps = resolveSteps().second
            advanceUntilIdle()

            val local = steps.single() as ResolvedPlayback.LocalCopyReady
            assertThat(local.localFilePath).isEqualTo(file.absolutePath)
            coVerify(exactly = 0) { InnerTubeVideoStreamExtractor.extract(any(), any()) }
        }

    @Test
    fun `a downloaded copy online plays the file, carries the row identity and never extracts streams`() =
        runTest(testDispatcher) {
            val file = temporaryFolder.newFile("$VIDEO_ID.mp4")
            localCopy = DownloadedVideo(video = downloadedVideo(), filePath = file.absolutePath)
            coEvery { videoDownloadManager.getSponsorBlockData(VIDEO_ID) } returns "[]"

            val steps = resolveSteps().second
            advanceUntilIdle()

            val local = steps.single() as ResolvedPlayback.LocalCopyReady
            assertThat(local.downloadedVideo?.title).isEqualTo("Downloaded")
            assertThat(local.needsSponsorBlockBackfill).isFalse()
            coVerify(exactly = 0) { InnerTubeVideoStreamExtractor.extract(any(), any()) }
        }

    @Test
    fun `a failing local copy lookup falls through to stream resolution`() =
        runTest(testDispatcher) {
            coEvery { videoDownloadManager.findLocalCopy(any()) } throws IllegalStateException("db")
            coEvery { InnerTubeVideoStreamExtractor.extract(any(), any()) } returns playableInnerTubeResult()

            val steps = resolveSteps().second
            advanceUntilIdle()

            assertThat(steps.single()).isInstanceOf(ResolvedPlayback.VodFromInnerTube::class.java)
        }

    @Test
    fun `a video the premiere lookup flags resolves to a countdown rather than an error`() =
        runTest(testDispatcher) {
            val steps = resolveSteps(upcoming = UpcomingPremiere(isUpcoming = true, scheduledStartMs = 1_234L)).second
            advanceUntilIdle()

            val upcoming = steps.single() as ResolvedPlayback.Upcoming
            assertThat(upcoming.releaseTimeMs).isEqualTo(1_234L)
            assertThat(upcoming.relatedVideos).isEmpty()
        }

    @Test
    fun `a load that stopped being current hands back nothing`() =
        runTest(testDispatcher) {
            val steps = resolveSteps(isCurrent = { false }).second
            advanceUntilIdle()

            assertThat(steps).isEmpty()
        }

    @Test
    fun `cancelling the load mid-extraction hands back nothing`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<InnerTubeVideoStreamExtractor.VideoExtractionResult?>()
            coEvery { InnerTubeVideoStreamExtractor.extract(any(), any()) } coAnswers { gate.await() }

            val (job, steps) = resolveSteps()
            runCurrent()
            coVerify(exactly = 1) { InnerTubeVideoStreamExtractor.extract(VIDEO_ID, forceSabr = false) }

            job.cancel()
            advanceUntilIdle()

            assertThat(steps).isEmpty()
        }

    @Test
    fun `a load after the tap's extraction finished plays it without a second client ladder`() =
        runTest(testDispatcher) {
            mockkObject(PlaybackPrefetcher)
            every { PlaybackPrefetcher.take(VIDEO_ID) } returns playableInnerTubeResult()

            val steps = resolveSteps().second
            advanceUntilIdle()

            assertThat(steps.single()).isInstanceOf(ResolvedPlayback.VodFromInnerTube::class.java)
            coVerify(exactly = 0) { InnerTubeVideoStreamExtractor.extract(any(), any()) }
        }

    @Test
    fun `a forced SABR reload never takes the tap's extraction`() =
        runTest(testDispatcher) {
            mockkObject(PlaybackPrefetcher)
            every { PlaybackPrefetcher.take(any()) } returns playableInnerTubeResult()

            resolveSteps(escalateToSabr = true)
            advanceUntilIdle()

            verify(exactly = 0) { PlaybackPrefetcher.take(any()) }
            coVerify(exactly = 1) { InnerTubeVideoStreamExtractor.extract(VIDEO_ID, forceSabr = true) }
        }

    @Test
    fun `a retry drops the kept extraction and never takes a handed-over one`() =
        runTest(testDispatcher) {
            mockkObject(PlaybackPrefetcher)
            every { PlaybackPrefetcher.take(any()) } returns playableInnerTubeResult()
            coEvery { InnerTubeVideoStreamExtractor.extract(any(), any()) } returns playableInnerTubeResult()

            resolveSteps(freshStreams = true)
            advanceUntilIdle()

            verify(exactly = 1) { InnerTubeVideoStreamExtractor.forgetReusable(VIDEO_ID) }
            verify(exactly = 0) { PlaybackPrefetcher.take(any()) }
            verify(exactly = 0) { InnerTubeVideoStreamExtractor.takePromoted(any()) }
            coVerify(exactly = 1) { InnerTubeVideoStreamExtractor.extract(VIDEO_ID, forceSabr = false) }
        }

    @Test
    fun `a forced SABR reload neither reads nor drops the kept extraction`() =
        runTest(testDispatcher) {
            resolveSteps(escalateToSabr = true)
            advanceUntilIdle()

            coVerify(exactly = 0) { InnerTubeVideoStreamExtractor.extractForPage(any()) }
            verify(exactly = 0) { InnerTubeVideoStreamExtractor.forgetReusable(any()) }
        }

    private fun TestScope.resolveSteps(
        escalateToSabr: Boolean = false,
        isCurrent: () -> Boolean = { true },
        upcoming: UpcomingPremiere = UpcomingPremiere.NOT_UPCOMING,
        blockedChannelIds: Set<String> = emptySet(),
        freshStreams: Boolean = false,
    ): Pair<Job, List<ResolvedPlayback>> {
        val steps = mutableListOf<ResolvedPlayback>()
        val job =
            launch(testDispatcher) {
                resolver.resolve(
                    scope = this,
                    request =
                        PlaybackResolutionRequest(
                            videoId = VIDEO_ID,
                            isWifi = true,
                            escalateToSabr = escalateToSabr,
                            resumePositionOverrideMs = null,
                            allowShorts = true,
                            blockedChannelIds = blockedChannelIds,
                            freshStreams = freshStreams,
                        ),
                    isCurrent = isCurrent,
                    resolveUpcoming = { _, _ -> upcoming },
                    onStep = { steps += it },
                )
            }
        return job to steps
    }

    /** A VOD the extractor resolved with direct URLs for both tracks. */
    private fun playableInnerTubeResult(): InnerTubeVideoStreamExtractor.VideoExtractionResult {
        val result = mockk<InnerTubeVideoStreamExtractor.VideoExtractionResult>(relaxed = true)
        every { result.isLive } returns false
        every { result.sabrInfo } returns null
        every { result.videoFormats } returns listOf(format(itag = 137, width = 1920, height = 1080))
        every { result.audioFormats } returns listOf(format(itag = 140, width = null, height = null))
        return result
    }

    private fun format(
        itag: Int,
        width: Int?,
        height: Int?,
    ) = PlayerResponse.StreamingData.Format(
        itag = itag,
        url = "https://example.invalid/$itag",
        mimeType = if (height == null) "audio/mp4" else "video/mp4",
        bitrate = 1_000,
        width = width,
        height = height,
        contentLength = 1_000L,
        quality = "medium",
        fps = null,
        qualityLabel = height?.let { "${it}p" },
        averageBitrate = 1_000,
        audioQuality = null,
        approxDurationMs = "120000",
        audioSampleRate = null,
        audioChannels = null,
        loudnessDb = null,
        lastModified = null,
        signatureCipher = null,
    )

    private fun downloadedVideo(): Video =
        Video(
            id = VIDEO_ID,
            title = "Downloaded",
            channelName = "Channel",
            channelId = "channel",
            thumbnailUrl = "",
            duration = 120,
            viewCount = 0L,
            uploadDate = "2026-01-01",
        )

    private companion object {
        const val VIDEO_ID = "vid_resolver"
    }
}
