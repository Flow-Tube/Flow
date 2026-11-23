package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.dao.PlaylistDao
import io.github.aedev.flow.data.local.dao.VideoDao
import io.github.aedev.flow.data.local.entity.PlaylistVideoCrossRef
import io.github.aedev.flow.data.model.Video
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/** Guards the album/playlist order reported in issue #676. */
class PlaylistOrderingTest {
    private val inserted = mutableListOf<PlaylistVideoCrossRef>()
    private val existingIds = mutableListOf<String>()

    private val playlistDao =
        mockk<PlaylistDao>(relaxed = true).also { dao ->
            coEvery { dao.getVideoIdsInPlaylist(any()) } answers { existingIds.toList() }
            coEvery { dao.getMaxPlaylistPosition(any()) } answers { inserted.maxOfOrNull { it.position } }
            val crossRef = slot<PlaylistVideoCrossRef>()
            coEvery { dao.insertPlaylistVideoCrossRef(capture(crossRef)) } answers { inserted += crossRef.captured }
            coEvery { dao.appendPlaylistVideos(any(), any(), any()) } answers { callOriginal() }
        }

    private val repository = PlaylistRepository(playlistDao, mockk<VideoDao>(relaxed = true))

    @After
    fun teardown() = unmockkAll()

    private fun video(id: String) =
        Video(
            id = id,
            title = id,
            channelName = "artist",
            channelId = "channel",
            thumbnailUrl = "",
            duration = 0,
            viewCount = 0L,
            uploadDate = "",
        )

    @Test
    fun `bulk add keeps the given track order`() =
        runTest {
            val tracks = listOf("t1", "t2", "t3", "t4")

            repository.addVideosToPlaylist("album", tracks.map(::video))

            assertThat(inserted.sortedBy { it.position }.map { it.videoId }).isEqualTo(tracks)
        }

    @Test
    fun `single add appends after the existing tail`() =
        runTest {
            repository.addVideosToPlaylist("album", listOf(video("t1"), video("t2")))
            existingIds += listOf("t1", "t2")

            repository.addVideoToPlaylist("album", video("t3"))

            assertThat(inserted.sortedBy { it.position }.map { it.videoId })
                .isEqualTo(listOf("t1", "t2", "t3"))
        }

    @Test
    fun `re-adding a track does not move it`() =
        runTest {
            repository.addVideosToPlaylist("album", listOf(video("t1"), video("t2")))
            existingIds += listOf("t1", "t2")

            repository.addVideoToPlaylist("album", video("t1"))

            assertThat(inserted.map { it.videoId }).isEqualTo(listOf("t1", "t2"))
        }

    @Test
    fun `bulk add is one playlist change`() =
        runTest {
            repository.addVideosToPlaylist("album", listOf("t1", "t2", "t3").map(::video))

            coVerify(exactly = 1) { playlistDao.appendPlaylistVideos("album", listOf("t1", "t2", "t3"), any()) }
            coVerify(exactly = 1) { playlistDao.updatePlaylistThumbnail("album", any()) }
        }
}
