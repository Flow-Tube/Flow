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
    fun `a saved playlist drops stale videos, links the new order and updates its thumbnail`() =
        runTest {
            coEvery { dao.getPlaylist("p") } returns playlist("p")
            coEvery { dao.getVideoIdsInPlaylist("p") } returns listOf("old", "a")
            coEvery { dao.getFirstVideoThumbnail("p") } returns "thumb"

            dao.replacePlaylistVideos("p", listOf("a", "b"))

            coVerifyOrder {
                dao.removeVideoFromPlaylist("p", "old")
                dao.insertPlaylistVideoCrossRef(link("a", 0))
                dao.insertPlaylistVideoCrossRef(link("b", 1))
                dao.updatePlaylistThumbnail("p", "thumb")
            }
            coVerify(exactly = 0) { dao.removeVideoFromPlaylist("p", "a") }
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
