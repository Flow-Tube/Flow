package io.github.aedev.flow.ui.screens.player

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModelHarness.Companion.historyEntity
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModelHarness.Companion.video
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins the observable behaviour of the non-network entry points: construction, restored sessions,
 * local playback, background-mode flags, clearing, and the preference toggles. As with the fetch
 * count tests, these record today's behaviour rather than a spec.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerViewModelEntryPointsTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var harness: VideoPlayerViewModelHarness

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        harness = VideoPlayerViewModelHarness(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        harness.close()
    }

    private fun TestScope.newViewModel(): VideoPlayerViewModel {
        val viewModel = harness.createViewModel()
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `construction performs one history read and no network call`() =
        runTest {
            val viewModel = newViewModel()

            assertThat(viewModel.uiState.value).isEqualTo(VideoPlayerUiState())
            verify { harness.repository wasNot Called }
            coVerify(exactly = 0) { InnerTubeVideoStreamExtractor.extract(any(), any()) }
            coVerify(exactly = 1) { harness.viewHistory.getLatestUnfinishedVideo() }
            coVerify(exactly = 0) { harness.viewHistory.touchHistoryEntry(any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { harness.playerManager.pause() }
            verify(exactly = 0) { harness.playerManager.clearAll() }
            verify(exactly = 0) { harness.playerManager.startBackgroundService(any(), any(), any(), any()) }
            verify(exactly = 0) { harness.playerManager.setAutoplayCandidates(any(), any(), any()) }
            verify(exactly = 0) { GlobalPlayerState.setCurrentVideo(any()) }
            assertThat(GlobalPlayerState.currentVideo.value).isNull()
        }

    @Test
    fun `construction skips the history read when continue watching is disabled or music is playing`() =
        runTest {
            harness.continueWatchingEnabled.value = false
            newViewModel()
            coVerify(exactly = 0) { harness.viewHistory.getLatestUnfinishedVideo() }

            harness.continueWatchingEnabled.value = true
            harness.musicCurrentTrack.value = mockk<MusicTrack>(relaxed = true)
            newViewModel()
            coVerify(exactly = 0) { harness.viewHistory.getLatestUnfinishedVideo() }
        }

    @Test
    fun `a restored video that was playing in a queue resumes inside that queue`() =
        runTest {
            val entity = historyEntity("hist_1")
            coEvery { harness.viewHistory.getLatestUnfinishedVideo() } returns entity
            val queue = listOf(video("a"), entity.toVideo(), video("b"))
            coEvery { harness.videoQueueStore.load() } returns
                io.github.aedev.flow.data.video
                    .SavedVideoQueue(queue, 1, "Weekend builds")
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.resumeRestoredSession(stayMini = false)
            advanceUntilIdle()

            verify { harness.playerManager.setQueue(queue, 1, "Weekend builds", null) }
        }

    @Test
    fun `an unfinished history entry becomes a restored session that resumeRestoredSession turns into a play`() =
        runTest {
            val entity = historyEntity("hist_1")
            coEvery { harness.viewHistory.getLatestUnfinishedVideo() } returns entity
            val viewModel = newViewModel()

            val restored = viewModel.uiState.value
            // Video.timestamp defaults to the wall clock, so compare the metadata without it.
            val expected = entity.toVideo().copy(timestamp = 0L)
            assertThat(restored.cachedVideo?.copy(timestamp = 0L)).isEqualTo(expected)
            assertThat(restored.isRestoredSession).isTrue()
            assertThat(restored.isLoading).isFalse()
            verify { harness.repository wasNot Called }
            verify(exactly = 0) { harness.playerManager.startBackgroundService(any(), any(), any(), any()) }
            assertThat(GlobalPlayerState.currentVideo.value).isNull()

            viewModel.resumeRestoredSession(stayMini = true)

            val resumed = viewModel.uiState.value
            assertThat(resumed.isRestoredSession).isFalse()
            assertThat(resumed.resumedInMiniPlayer).isTrue()
            assertThat(resumed.isBackgroundPlaybackMode).isFalse()
            assertThat(resumed.isLoading).isTrue()
            assertThat(resumed.cachedVideo?.copy(timestamp = 0L)).isEqualTo(expected)
            verify(exactly = 1) { GlobalPlayerState.setCurrentVideo(match { it?.id == "hist_1" }) }

            advanceUntilIdle()
            assertThat(viewModel.uiState.value.resumedInMiniPlayer).isTrue()
            coVerify(exactly = 1) { InnerTubeVideoStreamExtractor.extract("hist_1", forceSabr = false) }

            viewModel.clearResumedInMiniPlayer()
            assertThat(viewModel.uiState.value.resumedInMiniPlayer).isFalse()

            viewModel.resumeRestoredSession()
            coVerify(exactly = 1) { InnerTubeVideoStreamExtractor.extract("hist_1", forceSabr = false) }
        }

    @Test
    fun `playLocalVideo arms the player from the content uri without touching the network`() =
        runTest {
            val viewModel = newViewModel()
            val video = video("local_1")

            viewModel.playLocalVideo(video, "content://media/external/video/1")

            val armed = viewModel.uiState.value
            assertThat(armed.cachedVideo).isEqualTo(video)
            assertThat(armed.isLoading).isFalse()
            assertThat(armed.localFilePath).isEqualTo("content://media/external/video/1")
            assertThat(armed.localFileVideoId).isEqualTo("local_1")
            assertThat(armed.error).isNull()
            assertThat(armed.isRestoredSession).isFalse()
            assertThat(armed.isBackgroundPlaybackMode).isFalse()
            assertThat(armed.shouldDismissPlayer).isFalse()
            assertThat(armed.offlineSponsorBlockSegments).isNull()

            advanceUntilIdle()

            verifyOrder {
                harness.playerManager.pause()
                harness.playerManager.clearAll()
                EnhancedMusicPlayerManager.stop()
                EnhancedMusicPlayerManager.clearCurrentTrack()
                harness.playerManager.startBackgroundService("local_1", video.title, video.channelName, video.thumbnailUrl)
                harness.playerManager.initialize(harness.context)
                harness.playerManager.playLocalFile("local_1", "content://media/external/video/1", any(), any(), any())
                harness.playerManager.play()
            }
            verify(exactly = 1) { GlobalPlayerState.setCurrentVideo(video) }
            assertThat(GlobalPlayerState.currentVideo.value).isEqualTo(video)
            verify { harness.repository wasNot Called }
            coVerify(exactly = 0) { InnerTubeVideoStreamExtractor.extract(any(), any()) }
            coVerify(exactly = 1) { harness.viewHistory.getSavedPosition("local_1") }
            coVerify(exactly = 0) { harness.viewHistory.touchHistoryEntry(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `with start paused on an opened video is loaded and waits for play`() =
        runTest {
            harness.startVideosPaused = true
            every { harness.playerManager.isStartPausedArmed("local_1") } returns true
            val viewModel = newViewModel()

            viewModel.playLocalVideo(video("local_1"), "content://media/external/video/1")
            advanceUntilIdle()

            verify { harness.playerManager.armStartPaused("local_1") }
            verify { harness.playerManager.playLocalFile("local_1", "content://media/external/video/1", any(), any(), any()) }
            verify(exactly = 0) { harness.playerManager.play() }
        }

    @Test
    fun `a video that follows another is never held paused`() =
        runTest {
            harness.startVideosPaused = true
            val viewModel = newViewModel()

            viewModel.playVideo(video("next_1"), userOpened = false)

            verify { harness.playerManager.armStartPaused(null) }
            verify(exactly = 0) { harness.playerManager.armStartPaused("next_1") }
        }

    @Test
    fun `with start paused off an opened video plays as before`() =
        runTest {
            val viewModel = newViewModel()

            viewModel.playVideo(video("open_1"))

            verify { harness.playerManager.armStartPaused(null) }
        }

    @Test
    fun `with open in fullscreen on an opened video asks for fullscreen until the screen takes it`() =
        runTest {
            harness.openVideosFullscreen = true
            val viewModel = newViewModel()

            viewModel.playVideo(video("open_1"))
            assertThat(viewModel.fullscreenOpen.value).isEqualTo("open_1")

            viewModel.consumeFullscreenOpen("other")
            assertThat(viewModel.fullscreenOpen.value).isEqualTo("open_1")
            viewModel.consumeFullscreenOpen("open_1")
            assertThat(viewModel.fullscreenOpen.value).isNull()
        }

    @Test
    fun `open in fullscreen covers device videos and the video a playlist starts on`() =
        runTest {
            harness.openVideosFullscreen = true
            val viewModel = newViewModel()

            viewModel.playLocalVideo(video("local_1"), "content://media/external/video/1")
            assertThat(viewModel.fullscreenOpen.value).isEqualTo("local_1")

            viewModel.playPlaylist(listOf(video("a"), video("b")), startIndex = 1)
            assertThat(viewModel.fullscreenOpen.value).isEqualTo("b")
        }

    @Test
    fun `a video that follows another never asks for fullscreen`() =
        runTest {
            harness.openVideosFullscreen = true
            val viewModel = newViewModel()
            viewModel.playVideo(video("open_1"))

            viewModel.playVideo(video("next_1"), userOpened = false)

            assertThat(viewModel.fullscreenOpen.value).isNull()
        }

    @Test
    fun `with open in fullscreen off an opened video asks for nothing`() =
        runTest {
            val viewModel = newViewModel()

            viewModel.playVideo(video("open_1"))

            assertThat(viewModel.fullscreenOpen.value).isNull()
        }

    @Test
    fun `resuming a restored session never asks for fullscreen`() =
        runTest {
            harness.openVideosFullscreen = true
            coEvery { harness.viewHistory.getLatestUnfinishedVideo() } returns historyEntity("hist_1")
            val viewModel = newViewModel()

            viewModel.resumeRestoredSession(stayMini = false)

            assertThat(viewModel.fullscreenOpen.value).isNull()
        }

    @Test
    fun `syncing with a local video that is already playing keeps its state`() =
        runTest {
            val viewModel = newViewModel()
            val video = video("local_1")
            viewModel.playLocalVideo(video, "content://media/external/video/1")
            advanceUntilIdle()

            viewModel.syncWithCurrentPlayerVideo(video)

            val synced = viewModel.uiState.value
            assertThat(synced.isLoading).isFalse()
            assertThat(synced.localFilePath).isEqualTo("content://media/external/video/1")
            assertThat(synced.localFileVideoId).isEqualTo("local_1")
        }

    @Test
    fun `startBackgroundPlayback resetDismissState and showVideoPlayer flip the sheet flags in order`() =
        runTest {
            val viewModel = newViewModel()
            val video = video("vid_a")
            viewModel.playLocalVideo(video, "content://media/1")
            advanceUntilIdle()

            viewModel.startBackgroundPlayback()

            val background = viewModel.uiState.value
            assertThat(background.shouldDismissPlayer).isTrue()
            assertThat(background.isBackgroundPlaybackMode).isTrue()
            verifyOrder {
                harness.playerManager.startBackgroundService("vid_a", video.title, video.channelName, video.thumbnailUrl)
                GlobalPlayerState.setExplicitBackgroundPlaybackActive(true)
                harness.playerManager.continueVideoPlaybackInBackground()
            }
            assertThat(GlobalPlayerState.isExplicitBackgroundPlaybackActive.value).isTrue()

            viewModel.resetDismissState()

            val reset = viewModel.uiState.value
            assertThat(reset.shouldDismissPlayer).isFalse()
            assertThat(reset.isBackgroundPlaybackMode).isTrue()

            viewModel.showVideoPlayer()

            val shown = viewModel.uiState.value
            assertThat(shown.shouldDismissPlayer).isFalse()
            assertThat(shown.isBackgroundPlaybackMode).isFalse()
            verify(exactly = 1) { harness.playerManager.restoreVideoOutput() }
            assertThat(GlobalPlayerState.isExplicitBackgroundPlaybackActive.value).isFalse()
        }

    @Test
    fun `startBackgroundPlayback without any current video is a no-op`() =
        runTest {
            val viewModel = newViewModel()

            viewModel.startBackgroundPlayback()

            assertThat(viewModel.uiState.value).isEqualTo(VideoPlayerUiState())
            verify(exactly = 0) { harness.playerManager.continueVideoPlaybackInBackground() }
            verify(exactly = 0) { harness.playerManager.startBackgroundService(any(), any(), any(), any()) }
            assertThat(GlobalPlayerState.isExplicitBackgroundPlaybackActive.value).isFalse()
        }

    @Test
    fun `playVideo of the video already playing in background reopens the sheet instead of reloading`() =
        runTest {
            val viewModel = newViewModel()
            val video = video("vid_a")
            viewModel.playLocalVideo(video, "content://media/1")
            advanceUntilIdle()
            viewModel.startBackgroundPlayback()
            harness.playerState.value = EnhancedPlayerState(currentVideoId = "vid_a", isPrepared = true, isPlaying = true)
            advanceUntilIdle()

            viewModel.expandPlayerRequest.test {
                viewModel.playVideo(video)
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            val shown = viewModel.uiState.value
            assertThat(shown.isBackgroundPlaybackMode).isFalse()
            assertThat(shown.shouldDismissPlayer).isFalse()
            assertThat(shown.isLoading).isFalse()
            verify(exactly = 1) { harness.playerManager.restoreVideoOutput() }
            verify(exactly = 1) { harness.playerManager.pause() }
            verify { harness.repository wasNot Called }
        }

    @Test
    fun `clearVideo stops everything and keeps only the autoplay and adaptive flags`() =
        runTest {
            val viewModel = newViewModel()
            val video = video("vid_a")
            viewModel.playLocalVideo(video, "content://media/1")
            advanceUntilIdle()
            viewModel.startBackgroundPlayback()

            viewModel.clearVideo()

            assertThat(viewModel.uiState.value).isEqualTo(VideoPlayerUiState(autoplayEnabled = true, isAdaptiveMode = false))
            verifyOrder {
                harness.playerManager.stop()
                harness.playerManager.stopBackgroundService()
                harness.playerManager.clearAll()
                GlobalPlayerState.setCurrentVideo(null)
                GlobalPlayerState.setExplicitBackgroundPlaybackActive(false)
                GlobalPlayerState.hideMiniPlayer()
            }
            assertThat(GlobalPlayerState.currentVideo.value).isNull()
            assertThat(GlobalPlayerState.isMiniPlayerVisible.value).isFalse()
            assertThat(GlobalPlayerState.isExplicitBackgroundPlaybackActive.value).isFalse()
            assertThat(viewModel.commentsState.value).isEmpty()
            assertThat(viewModel.isLoadingComments.value).isFalse()
            assertThat(viewModel.hasMoreComments.value).isFalse()
            assertThat(viewModel.canGoPrevious.value).isFalse()
        }

    @Test
    fun `toggleAutoplay writes the preference and pushes candidates only when a video is cached`() =
        runTest {
            val viewModel = newViewModel()

            viewModel.toggleAutoplay(false)
            advanceUntilIdle()
            coVerify(exactly = 1) { harness.playerPreferences.setAutoplayEnabled(false) }
            assertThat(viewModel.uiState.value.autoplayEnabled).isFalse()
            verify(exactly = 0) { harness.playerManager.setAutoplayCandidates(any(), any(), any()) }

            viewModel.playLocalVideo(video("vid_a"), "content://media/1")
            advanceUntilIdle()
            viewModel.toggleAutoplay(true)
            advanceUntilIdle()
            coVerify(exactly = 1) { harness.playerPreferences.setAutoplayEnabled(true) }
            assertThat(viewModel.uiState.value.autoplayEnabled).isTrue()
            verify(exactly = 1) { harness.playerManager.setAutoplayCandidates("vid_a", emptyList(), true) }
        }

    @Test
    fun `toggleAutoplay is forced off while the player loops`() =
        runTest {
            val viewModel = newViewModel()
            harness.playerState.value = EnhancedPlayerState(isLooping = true)
            advanceUntilIdle()

            viewModel.toggleAutoplay(true)
            advanceUntilIdle()

            coVerify(exactly = 1) { harness.playerPreferences.setAutoplayEnabled(false) }
            coVerify(exactly = 0) { harness.playerPreferences.setAutoplayEnabled(true) }
            assertThat(viewModel.uiState.value.autoplayEnabled).isFalse()
        }

    @Test
    fun `toggleLoop on disables autoplay and toggleLoop off leaves autoplay alone`() =
        runTest {
            val viewModel = newViewModel()

            viewModel.toggleLoop(true)
            advanceUntilIdle()
            verify(exactly = 1) { harness.playerManager.toggleLoop(true) }
            coVerify(exactly = 1) { harness.playerPreferences.setAutoplayEnabled(false) }
            assertThat(viewModel.uiState.value.autoplayEnabled).isFalse()

            viewModel.toggleLoop(false)
            advanceUntilIdle()
            verify(exactly = 1) { harness.playerManager.toggleLoop(false) }
            coVerify(exactly = 1) { harness.playerPreferences.setAutoplayEnabled(any()) }
            assertThat(viewModel.uiState.value.autoplayEnabled).isFalse()
        }

    @Test
    fun `toggleSkipSilence and toggleVolumeNormalization delegate to the player without touching ui state`() =
        runTest {
            val viewModel = newViewModel()
            val before = viewModel.uiState.value

            viewModel.toggleSkipSilence(true)
            viewModel.toggleVolumeNormalization(true)
            viewModel.toggleSkipSilence(false)
            advanceUntilIdle()

            verifyOrder {
                harness.playerManager.toggleSkipSilence(true)
                harness.playerManager.toggleVolumeNormalization(true)
                harness.playerManager.toggleSkipSilence(false)
            }
            assertThat(viewModel.uiState.value).isEqualTo(before)
        }
}
