package io.github.aedev.flow.ui.components.shared.quickactions

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.data.engagement.BlockedChannel
import io.github.aedev.flow.data.engagement.VideoEngagementUseCase
import io.github.aedev.flow.data.engagement.VideoFeedbackUseCase
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.entity.PlaylistVideoCrossRef
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.video.MusicVideoVersions
import io.github.aedev.flow.data.video.AutoDownloadTrigger
import io.github.aedev.flow.data.video.VideoDownloadOptions
import io.github.aedev.flow.data.video.VideoDownloadOptionsLoader
import io.github.aedev.flow.innertube.models.Artist
import io.github.aedev.flow.innertube.models.SongItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickActionsMessagesTest {
    private val testDispatcher = StandardTestDispatcher()
    private val engagement: VideoEngagementUseCase = mockk(relaxed = true)
    private val feedback: VideoFeedbackUseCase = mockk(relaxed = true)
    private val loader: VideoDownloadOptionsLoader = mockk()
    private val video =
        Video(
            id = "vid",
            title = "Lighthouses",
            channelName = "Orbital Notes",
            channelId = "UCorbital",
            thumbnailUrl = "",
            duration = 60,
            viewCount = 0L,
            uploadDate = "",
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private var videoSearch: suspend (String) -> List<SongItem> = { emptyList() }
    private val song = MusicTrack("song", "Anti-Hero", "Taylor Swift", "", 201, channelId = "ts")

    private val playlistRepository: PlaylistRepository = mockk(relaxed = true)
    private val autoDownloads = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val autoDownload: AutoDownloadTrigger = mockk(relaxed = true) { every { queued } returns autoDownloads }

    private fun viewModel() =
        QuickActionsViewModel(
            repository = mockk(relaxed = true),
            playlistRepository = playlistRepository,
            videoDownloadManager = mockk(relaxed = true),
            engagement = engagement,
            feedback = feedback,
            downloadOptions = loader,
            likedMedia = mockk(relaxed = true),
            playerManager = { mockk(relaxed = true) },
            musicVideos = MusicVideoVersions { videoSearch(it) },
            autoDownload = autoDownload,
            aiReporter = mockk(relaxed = true),
        )

    private fun kotlinx.coroutines.test.TestScope.messagesOf(viewModel: QuickActionsViewModel): List<QuickActionMessage> {
        val messages = mutableListOf<QuickActionMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.collect { messages += it } }
        return messages
    }

    @Test
    fun `an automatic download is announced with an undo that cancels it`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            val messages = messagesOf(viewModel)
            testDispatcher.scheduler.advanceUntilIdle()

            autoDownloads.emit(video.id)
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(messages.map { it.text }).containsExactly(R.string.auto_download_queued)
            viewModel.undo(messages.single().undo!!)
            testDispatcher.scheduler.advanceUntilIdle()
            verify(exactly = 1) { autoDownload.undo(video.id) }
        }

    @Test
    fun `Watch video opens the song's official video`() =
        runTest(testDispatcher) {
            videoSearch =
                { listOf(SongItem("video", "Anti-Hero", listOf(Artist("Taylor Swift", "ts")), musicVideoType = OMV, thumbnail = "")) }
            val opened = mutableListOf<String>()

            viewModel().watchVideo(song) { opened += it }
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(opened).containsExactly("video")
        }

    @Test
    fun `Watch video says so when the song has no video or the search fails`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            val messages = messagesOf(viewModel)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.watchVideo(song) { error("nothing to open") }
            testDispatcher.scheduler.advanceUntilIdle()
            videoSearch = { throw java.io.IOException("offline") }
            viewModel.watchVideo(song.copy(videoId = "other")) { error("nothing to open") }
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(messages.map { it.text }).containsExactly(R.string.music_video_unavailable, R.string.music_video_failed).inOrder()
        }

    @Test
    fun `removing a saved Short offers an undo that puts it back in place`() =
        runTest(testDispatcher) {
            val entry = PlaylistVideoCrossRef(PlaylistRepository.SAVED_SHORTS_ID, video.id, position = -5L, addedAt = 1L)
            coEvery { playlistRepository.takeVideosFromPlaylist(PlaylistRepository.SAVED_SHORTS_ID, setOf(video.id)) } returns listOf(entry)
            val viewModel = viewModel()
            val messages = messagesOf(viewModel)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.removeFromSavedShorts(video)
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(messages.map { it.text }).containsExactly(R.string.shorts_unsaved)
            viewModel.undo(messages.single().undo!!)
            testDispatcher.scheduler.advanceUntilIdle()
            coVerify { playlistRepository.restorePlaylistVideos(listOf(entry)) }
        }

    @Test
    fun `saving to Watch later offers an undo that takes it out again`() =
        runTest(testDispatcher) {
            coEvery { feedback.toggleWatchLater(video) } returns true
            val viewModel = viewModel()
            val messages = messagesOf(viewModel)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.toggleWatchLater(video)
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(messages.single().text).isEqualTo(R.string.toast_added_to_watch_later)
            viewModel.undo(messages.single().undo!!)
            testDispatcher.scheduler.advanceUntilIdle()
            coVerify { feedback.setWatchLater(video, false) }
        }

    @Test
    fun `hiding a channel names it and undo unblocks the same channel`() =
        runTest(testDispatcher) {
            coEvery { feedback.blockChannel(video) } returns BlockedChannel("UCreal", "Orbital Notes")
            val viewModel = viewModel()
            val messages = messagesOf(viewModel)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.blockChannel(video)
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(messages.single().arg).isEqualTo("Orbital Notes")
            viewModel.undo(messages.single().undo!!)
            testDispatcher.scheduler.advanceUntilIdle()
            coVerify { feedback.unblockChannel("UCreal") }
        }

    @Test
    fun `a failed action says so instead of showing the exception`() =
        runTest(testDispatcher) {
            coEvery { feedback.markNotInterested(video) } throws IllegalStateException("boom")
            val viewModel = viewModel()
            val messages = messagesOf(viewModel)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.markNotInterested(video)
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(messages.single()).isEqualTo(QuickActionMessage(R.string.quick_action_failed))
        }

    @Test
    fun `subscribing reads the stored state at the tap`() =
        runTest(testDispatcher) {
            every { engagement.subscriptionState("UCorbital") } returns flowOf(true)
            val viewModel = viewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.toggleSubscription("UCorbital", "Orbital Notes", "https://yt3.ggpht.com/a")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { engagement.applySubscription("UCorbital", "Orbital Notes", any(), false, any()) }
        }

    @Test
    fun `a download opens the dialog once its formats have loaded`() =
        runTest(testDispatcher) {
            val options = VideoDownloadOptions(video, emptyList(), emptyList(), emptyMap())
            coEvery { loader.load(video) } returns options
            val viewModel = viewModel()
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.requestDownload(video)
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(viewModel.pendingDownload.value).isEqualTo(options)
            viewModel.dismissDownload()
            assertThat(viewModel.pendingDownload.value).isNull()
        }

    @Test
    fun `a video with no source says so and opens nothing`() =
        runTest(testDispatcher) {
            coEvery { loader.load(video) } returns null
            val viewModel = viewModel()
            val messages = messagesOf(viewModel)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.requestDownload(video)
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(
                messages.map { it.text },
            ).containsExactly(R.string.toast_fetching_download_links, R.string.toast_no_download_source).inOrder()
            assertThat(viewModel.pendingDownload.value).isNull()
        }

    private companion object {
        const val OMV = "MUSIC_VIDEO_TYPE_OMV"
    }
}
