package io.github.aedev.flow.data.local.dao

import io.github.aedev.flow.data.local.entity.PlaylistEntity
import io.github.aedev.flow.data.local.entity.PlaylistVideoCrossRef
import io.mockk.MockKMatcherScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** #2232: a saved playlist removed while its refresh was loading failed the cross-ref foreign key. */
class PlaylistDaoReplaceTest {
    private val dao =
        mockk<PlaylistDao>(relaxed = true).also {
            coEvery { it.replacePlaylistVideos(any(), any()) } answers { callOriginal() }
        }

    @Test
    fun `a removed playlist gets no links and no thumbnail`() =
        runTest {
            coEvery { dao.getPlaylist("gone") } returns null

            dao.replacePlaylistVideos("gone", listOf("a", "b"))

            coVerify(exactly = 0) { dao.insertPlaylistVideoCrossRef(any()) }
            coVerify(exactly = 0) { dao.removeVideoFromPlaylist(any(), any()) }
            coVerify(exactly = 0) { dao.updatePlaylistThumbnail(any(), any()) }
        }

    @Test
    fun `a saved playlist drops stale videos, moves kept ones, links only new ones and updates its thumbnail`() =
        runTest {
            coEvery { dao.getPlaylist("p") } returns playlist("p")
            coEvery { dao.getPlaylistVideoPositions("p") } returns listOf(PlaylistVideoPosition("old", 0), PlaylistVideoPosition("a", 1))
            coEvery { dao.getFirstVideoThumbnail("p") } returns "thumb"

            dao.replacePlaylistVideos("p", listOf("a", "b"))

            coVerifyOrder {
                dao.removeVideoFromPlaylist("p", "old")
                dao.updatePlaylistVideoPosition("p", "a", 0)
                dao.insertPlaylistVideoCrossRef(link("b", 1))
                dao.updatePlaylistThumbnail("p", "thumb")
            }
            coVerify(exactly = 0) { dao.removeVideoFromPlaylist("p", "a") }
            coVerify(exactly = 0) { dao.insertPlaylistVideoCrossRef(match { it.videoId == "a" }) }
        }

    @Test
    fun `an unchanged saved playlist is not written`() =
        runTest {
            coEvery { dao.getPlaylist("p") } returns playlist("p").copy(thumbnailUrl = "thumb")
            coEvery { dao.getPlaylistVideoPositions("p") } returns listOf(PlaylistVideoPosition("a", 0), PlaylistVideoPosition("b", 1))
            coEvery { dao.getFirstVideoThumbnail("p") } returns "thumb"

            dao.replacePlaylistVideos("p", listOf("a", "b"))

            coVerify(exactly = 0) { dao.removeVideoFromPlaylist(any(), any()) }
            coVerify(exactly = 0) { dao.updatePlaylistVideoPosition(any(), any(), any()) }
            coVerify(exactly = 0) { dao.insertPlaylistVideoCrossRef(any()) }
            coVerify(exactly = 0) { dao.updatePlaylistThumbnail(any(), any()) }
        }

    private fun MockKMatcherScope.link(
        videoId: String,
        position: Long,
    ) = match<PlaylistVideoCrossRef> { it.playlistId == "p" && it.videoId == videoId && it.position == position }

    private fun playlist(id: String) =
        PlaylistEntity(
            id = id,
            name = id,
            description = "",
            thumbnailUrl = "",
            isPrivate = false,
            createdAt = 0,
        )
}
