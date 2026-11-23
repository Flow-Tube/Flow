package io.github.aedev.flow.data.local.dao

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlaylistReorderPlanTest {
    private val stored = rows("a", "b", "c", "d", "e")

    private fun rows(vararg ids: String) = ids.mapIndexed { index, id -> PlaylistVideoPosition(id, index.toLong()) }

    private fun at(
        id: String,
        position: Long,
    ) = PlaylistVideoPosition(id, position)

    @Test
    fun `moving down writes only the range it crossed`() {
        assertThat(changedPositions(stored, listOf("a", "c", "d", "b", "e")))
            .containsExactly(at("c", 1), at("d", 2), at("b", 3))
            .inOrder()
    }

    @Test
    fun `moving up writes only the range it crossed`() {
        assertThat(changedPositions(stored, listOf("a", "d", "b", "c", "e")))
            .containsExactly(at("d", 1), at("b", 2), at("c", 3))
            .inOrder()
    }

    @Test
    fun `moving to the top writes the rows above it`() {
        assertThat(changedPositions(stored, listOf("d", "a", "b", "c", "e")))
            .containsExactly(at("d", 0), at("a", 1), at("b", 2), at("c", 3))
            .inOrder()
    }

    @Test
    fun `moving to the bottom writes the rows below it`() {
        assertThat(changedPositions(stored, listOf("a", "c", "d", "e", "b")))
            .containsExactly(at("c", 1), at("d", 2), at("e", 3), at("b", 4))
            .inOrder()
    }

    @Test
    fun `an unchanged order writes nothing`() {
        assertThat(changedPositions(stored, listOf("a", "b", "c", "d", "e"))).isEmpty()
    }

    @Test
    fun `gapped or negative positions get every row renumbered`() {
        val legacy = listOf(at("a", -30), at("b", -20), at("c", 4))

        assertThat(changedPositions(legacy, listOf("b", "a", "c")))
            .containsExactly(at("b", 0), at("a", 1), at("c", 2))
            .inOrder()
    }

    @Test
    fun `an order that does not match the stored videos writes every row`() {
        assertThat(changedPositions(rows("a", "b", "c"), listOf("b", "a")))
            .containsExactly(at("b", 0), at("a", 1))
            .inOrder()
    }

    @Test
    fun `the dao writes only moved rows and keeps the thumbnail when the first video stays`() =
        runTest {
            val dao = daoWith(rows("a", "b", "c"))

            dao.reorderPlaylistVideos("p", listOf("a", "c", "b"))

            coVerify(exactly = 1) { dao.updatePlaylistVideoPosition("p", "c", 1) }
            coVerify(exactly = 1) { dao.updatePlaylistVideoPosition("p", "b", 2) }
            coVerify(exactly = 0) { dao.updatePlaylistVideoPosition("p", "a", any()) }
            coVerify(exactly = 0) { dao.updatePlaylistThumbnail(any(), any()) }
        }

    @Test
    fun `the dao updates the thumbnail when the first video changes`() =
        runTest {
            val dao = daoWith(rows("a", "b", "c"))
            coEvery { dao.getFirstVideoThumbnail("p") } returns "b-thumb"

            dao.reorderPlaylistVideos("p", listOf("b", "a", "c"))

            coVerify(exactly = 1) { dao.updatePlaylistThumbnail("p", "b-thumb") }
        }

    private fun daoWith(current: List<PlaylistVideoPosition>) =
        mockk<PlaylistDao>(relaxed = true).also {
            coEvery { it.reorderPlaylistVideos(any(), any()) } answers { callOriginal() }
            coEvery { it.getPlaylistVideoPositions("p") } returns current
        }
}
