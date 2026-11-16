package io.github.aedev.flow.ui.screens.music.collection

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.engagement.LikedMediaUseCase
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.SavedPlaylistSyncStore
import io.github.aedev.flow.data.local.entity.DownloadCollectionKind
import io.github.aedev.flow.data.local.entity.PlaylistEntity
import io.github.aedev.flow.data.local.entity.PlaylistVideoCrossRef
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.YouTubeMusicService
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.PlaylistDetails
import io.github.aedev.flow.data.recommendation.MusicSection
import io.github.aedev.flow.data.recommendation.music.DailyMixStore
import io.github.aedev.flow.data.video.BackgroundDownloadQueuer
import io.github.aedev.flow.data.video.downloader.collection.DownloadedCollections
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionUndo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import io.github.aedev.flow.data.music.PlaylistRepository as MusicLibrary

@OptIn(ExperimentalCoroutinesApi::class)
class MusicCollectionViewModelTest {
    private val context = mockk<Context>(relaxed = true).also { every { it.getString(any()) } returns "text" }
    private val playlists = mockk<PlaylistRepository>(relaxed = true)
    private val likes = mockk<LikedVideosRepository>(relaxed = true)
    private val musicLibrary = mockk<MusicLibrary>(relaxed = true)
    private val likedMedia = mockk<LikedMediaUseCase>(relaxed = true)
    private val syncState = mockk<SavedPlaylistSyncStore>(relaxed = true).also { coEvery { it.syncedAt(any()) } returns null }
    private val collections =
        mockk<DownloadedCollections>(relaxed = true).also {
            every { it.observe(any()) } returns flowOf(null)
            coEvery { it.offline(any()) } returns null
        }
    private val downloads =
        mockk<BackgroundDownloadQueuer>(
            relaxed = true,
        ).also { every { it.batches } returns MutableStateFlow(emptyMap()) }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(YouTubeMusicService)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel(id: String) =
        MusicCollectionViewModel(
            context,
            SavedStateHandle(mapOf(MUSIC_COLLECTION_ARG to id)),
            playlists,
            DailyMixStore(),
            mockk(relaxed = true),
            likes,
            musicLibrary,
            likedMedia,
            mockk(relaxed = true),
            mockk(relaxed = true),
            downloads,
            collections,
            syncState,
        )

    private fun entity(
        id: String,
        own: Boolean,
    ) = PlaylistEntity(
        id = id,
        name = "Road trip",
        description = "",
        thumbnailUrl = "",
        isPrivate = true,
        createdAt = 0L,
        isMusic = true,
        isUserCreated = own,
    )

    private fun video(id: String) =
        Video(
            id = id,
            title = id,
            channelName = "Artist",
            channelId = "UC1",
            thumbnailUrl = "t",
            duration = 226,
            viewCount = 0L,
            uploadDate = "",
            addedAtInPlaylist = 7L,
        )

    private fun track(id: String) = MusicTrack(videoId = id, title = id, artist = "Artist", thumbnailUrl = "t", duration = 200)

    private fun remote(
        id: String,
        tracks: List<MusicTrack>,
        continuation: String? = null,
    ) = PlaylistDetails(
        id = id,
        title = "Remote",
        thumbnailUrl = "",
        author = "Artist",
        trackCount = tracks.size,
        tracks = tracks,
        continuation = continuation,
    )

    private fun MusicCollectionViewModel.settled(): MusicCollectionUiState =
        runBlocking {
            withTimeout(2_000) { state.first { !it.isLoading } }
        }

    @Test
    fun `a playlist you own opens from the database with its lengths and dates`() {
        val id = "sync_4f2a"
        coEvery { playlists.getPlaylistEntity(id) } returns entity(id, own = true)
        every { playlists.observePlaylistEntity(id) } returns flowOf(entity(id, own = true))
        every { playlists.getPlaylistVideosWithAddedAtFlow(id) } returns flowOf(listOf(video("a"), video("b")))

        val state = viewModel(id).settled()

        assertThat(state.kind).isEqualTo(MusicCollectionKind.OWN)
        assertThat(state.isOwn).isTrue()
        assertThat(state.details?.tracks?.map { it.duration }).containsExactly(226, 226)
        assertThat(state.addedAt).containsEntry("a", 7L)
    }

    @Test
    fun `your playlist follows edits and removals`() {
        val id = "3f9c"
        val videos = MutableStateFlow(listOf(video("a"), video("b")))
        coEvery { playlists.getPlaylistEntity(id) } returns entity(id, own = true)
        every { playlists.observePlaylistEntity(id) } returns flowOf(entity(id, own = true))
        every { playlists.getPlaylistVideosWithAddedAtFlow(id) } returns videos
        val viewModel = viewModel(id)
        viewModel.settled()

        videos.value = listOf(video("b"))

        val tracks = runBlocking { withTimeout(2_000) { viewModel.state.first { it.details?.tracks?.size == 1 } } }
        assertThat(
            tracks.details
                ?.tracks
                ?.single()
                ?.videoId,
        ).isEqualTo("b")
    }

    @Test
    fun `a saved album opens from the saved copy and refreshes it once complete`() {
        val id = "MPREb_album"
        coEvery { playlists.getPlaylistEntity(id) } returns entity(id, own = false)
        every { playlists.getPlaylistVideosWithAddedAtFlow(id) } returns flowOf(listOf(video("a")))
        coEvery { YouTubeMusicService.fetchPlaylistDetails(id) } returns remote(id, listOf(track("a"), track("b")))

        val viewModel = viewModel(id)

        val refreshed = runBlocking { withTimeout(2_000) { viewModel.state.first { it.details?.tracks?.size == 2 } } }
        assertThat(refreshed.isSaved).isTrue()
        assertThat(refreshed.kind).isEqualTo(MusicCollectionKind.SAVED)
        coVerify(timeout = 2_000) { playlists.syncSavedPlaylistVideos(id, match { it.map(Video::id) == listOf("a", "b") }) }
    }

    @Test
    fun `a long saved playlist whose first page changed is refreshed from every page`() {
        val id = "PLsaved"
        coEvery { playlists.getPlaylistEntity(id) } returns entity(id, own = false)
        every { playlists.getPlaylistVideosWithAddedAtFlow(id) } returns flowOf(listOf(video("a")))
        coEvery { YouTubeMusicService.fetchPlaylistDetails(id) } returns remote(id, listOf(track("z")), continuation = "next")
        coEvery { YouTubeMusicService.fetchPlaylistContinuation(id, "next") } returns (listOf(track("b")) to null)

        viewModel(id)

        coVerify(timeout = 2_000) { playlists.syncSavedPlaylistVideos(id, match { it.map(Video::id) == listOf("z", "b") }) }
        coVerify(timeout = 2_000) { syncState.markSynced(id, any()) }
    }

    @Test
    fun `a long saved playlist whose first page is unchanged loads no further pages`() {
        val id = "PLsaved"
        coEvery { playlists.getPlaylistEntity(id) } returns entity(id, own = false)
        every { playlists.getPlaylistVideosWithAddedAtFlow(id) } returns flowOf(listOf(video("a"), video("b")))
        coEvery { YouTubeMusicService.fetchPlaylistDetails(id) } returns remote(id, listOf(track("a")), continuation = "next")

        val viewModel = viewModel(id)

        runBlocking { withTimeout(2_000) { viewModel.state.first { it.details?.continuation == "next" } } }
        coVerify(timeout = 2_000) { syncState.syncedAt(id) }
        coVerify(exactly = 0) { YouTubeMusicService.fetchPlaylistContinuation(any(), any()) }
        coVerify(exactly = 0) { playlists.syncSavedPlaylistVideos(any(), any()) }
    }

    @Test
    fun `a failed load shows an error and Retry loads again`() {
        val id = "PLremote"
        coEvery { playlists.getPlaylistEntity(id) } returns null
        coEvery { YouTubeMusicService.fetchPlaylistDetails(id) } returns null
        val viewModel = viewModel(id)
        assertThat(viewModel.settled().failed).isTrue()

        coEvery { YouTubeMusicService.fetchPlaylistDetails(id) } returns remote(id, listOf(track("a")))
        viewModel.retry()

        val loaded = runBlocking { withTimeout(2_000) { viewModel.state.first { it.details != null } } }
        assertThat(loaded.kind).isEqualTo(MusicCollectionKind.PLAYLIST)
        assertThat(loaded.failed).isFalse()
    }

    @Test
    fun `a failed next page keeps its token so the next try retries`() {
        val id = "PLremote"
        coEvery { playlists.getPlaylistEntity(id) } returns null
        coEvery { YouTubeMusicService.fetchPlaylistDetails(id) } returns remote(id, listOf(track("a")), continuation = "next")
        coEvery { YouTubeMusicService.fetchPlaylistContinuation(id, "next") } returns (emptyList<MusicTrack>() to null)
        val viewModel = viewModel(id)
        viewModel.settled()

        viewModel.loadMore()

        val failed = runBlocking { withTimeout(2_000) { viewModel.state.first { it.moreFailed } } }
        assertThat(failed.details?.continuation).isEqualTo("next")
    }

    @Test
    fun `liked music lists liked songs newest first and unlikes them with an Undo`() {
        val liked =
            listOf(
                LikedVideoInfo("b", "B", "t", "Artist", likedAt = 2L, isMusic = true),
                LikedVideoInfo("a", "A", "t", "Artist", likedAt = 1L, isMusic = true),
            )
        every { likes.getLikedMusicFlow() } returns flowOf(liked)
        every { musicLibrary.favorites } returns flowOf(emptyList())
        coEvery { likedMedia.unlike(setOf("a")) } returns liked.takeLast(1)
        val viewModel = viewModel(PlaylistRepository.LIKED_MUSIC_ID)

        val state = viewModel.settled()
        viewModel.removeTracks(setOf("a"))

        assertThat(state.kind).isEqualTo(MusicCollectionKind.LIKED)
        assertThat(state.details?.tracks?.map { it.videoId }).containsExactly("b", "a").inOrder()
        val message = runBlocking { withTimeout(2_000) { viewModel.messages.first() } }
        assertThat(message.undo).isEqualTo(QuickActionUndo.Unlike(liked.takeLast(1)))
    }

    @Test
    fun `removing from your playlist offers an Undo that restores the same entries`() {
        val id = "3f9c"
        val entry = PlaylistVideoCrossRef(playlistId = id, videoId = "a", position = 4L, addedAt = 9L)
        coEvery { playlists.getPlaylistEntity(id) } returns entity(id, own = true)
        every { playlists.observePlaylistEntity(id) } returns flowOf(entity(id, own = true))
        every { playlists.getPlaylistVideosWithAddedAtFlow(id) } returns flowOf(listOf(video("a")))
        coEvery { playlists.takeVideosFromPlaylist(id, setOf("a")) } returns listOf(entry)
        val viewModel = viewModel(id)
        viewModel.settled()

        viewModel.removeTracks(setOf("a"))

        val message = runBlocking { withTimeout(2_000) { viewModel.messages.first() } }
        assertThat(message.undo).isEqualTo(QuickActionUndo.PlaylistRemoval(listOf(entry)))
    }

    @Test
    fun `deleting your playlist closes the page instead of showing an error`() {
        val id = "3f9c"
        val entity = MutableStateFlow<PlaylistEntity?>(entity(id, own = true))
        coEvery { playlists.getPlaylistEntity(id) } returns entity(id, own = true)
        every { playlists.observePlaylistEntity(id) } returns entity
        every { playlists.getPlaylistVideosWithAddedAtFlow(id) } returns flowOf(listOf(video("a")))
        val viewModel = viewModel(id)
        viewModel.settled()

        viewModel.delete()
        entity.value = null

        coVerify(timeout = 2_000) { playlists.deletePlaylist(id) }
        assertThat(viewModel.state.value.isDeleted).isTrue()
        assertThat(viewModel.state.value.failed).isFalse()
    }

    @Test
    fun `download all loads every page first and hands the whole playlist to the background queue`() {
        val id = "PLremote"
        coEvery { playlists.getPlaylistEntity(id) } returns null
        coEvery { YouTubeMusicService.fetchPlaylistDetails(id) } returns remote(id, listOf(track("a")), continuation = "next")
        coEvery { YouTubeMusicService.fetchPlaylistContinuation(id, "next") } returns (listOf(track("b")) to null)
        val viewModel = viewModel(id)
        viewModel.settled()

        viewModel.download()

        coVerify(timeout = 2_000) {
            downloads.queueCollectionSongs(
                match { it.id == id && it.kind == DownloadCollectionKind.MUSIC_PLAYLIST },
                match { songs -> songs.map { it.videoId } == listOf("a", "b") },
                complete = true,
            )
        }
    }

    @Test
    fun `a daily mix is saved as a music playlist of your own`() {
        val store = DailyMixStore().apply { publish(listOf(MusicSection(title = "Mix 1", tracks = listOf(track("a"), track("b"))))) }
        val viewModel =
            MusicCollectionViewModel(
                context,
                SavedStateHandle(mapOf(MUSIC_COLLECTION_ARG to "daily_mix_0")),
                playlists,
                store,
                mockk(relaxed = true),
                likes,
                musicLibrary,
                likedMedia,
                mockk(relaxed = true),
                mockk(relaxed = true),
                downloads,
                collections,
                syncState,
            )
        assertThat(viewModel.settled().kind).isEqualTo(MusicCollectionKind.DAILY_MIX)

        viewModel.saveAsPlaylist()

        coVerify(timeout = 2_000) { playlists.importPlaylist("Mix 1", any(), match { it.map(Video::id) == listOf("a", "b") }, true, any()) }
    }

    @Test
    fun `pages that repeat a song add it once`() {
        val first = remote("PL", listOf(track("a"), track("b")))

        val merged = first.appending(listOf(track("b"), track("c")), next = null)

        assertThat(merged.tracks.map { it.videoId }).containsExactly("a", "b", "c").inOrder()
        assertThat(merged.continuation).isNull()
    }

    @Test
    fun `creating a playlist from Add all copies every song onto the new list`() {
        val id = "3f9c"
        coEvery { playlists.getPlaylistEntity(id) } returns entity(id, own = true)
        every { playlists.observePlaylistEntity(id) } returns flowOf(entity(id, own = true))
        every { playlists.getPlaylistVideosWithAddedAtFlow(id) } returns flowOf(listOf(video("a"), video("b")))
        coEvery { playlists.createPlaylist(any(), any(), any(), any(), any()) } just runs
        coEvery { playlists.getPlaylistInfo(any()) } answers {
            PlaylistInfo(
                id = firstArg(),
                name = "Fresh",
                description = "notes",
                videoCount = 0,
                thumbnailUrl = "",
                isPrivate = false,
                createdAt = 0L,
            )
        }
        val viewModel = viewModel(id)
        viewModel.settled()

        viewModel.createAndAdd("Fresh", "notes")

        coVerify(timeout = 2_000) {
            playlists.createPlaylist(any(), "Fresh", "notes", false, true)
        }
        coVerify(timeout = 2_000) {
            playlists.addVideosToPlaylist(any(), match { it.map(Video::id) == listOf("a", "b") })
        }
        val message = runBlocking { withTimeout(2_000) { viewModel.messages.first() } }
        assertThat(message.pluralRes).isEqualTo(io.github.aedev.flow.R.plurals.merge_playlist_success)
        assertThat(message.args).contains("Fresh")
    }
}
