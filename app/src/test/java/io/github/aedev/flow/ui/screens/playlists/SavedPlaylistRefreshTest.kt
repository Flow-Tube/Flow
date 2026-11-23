package io.github.aedev.flow.ui.screens.playlists

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.SavedPlaylistCheck
import io.github.aedev.flow.data.local.SavedPlaylistSyncStore
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.RemotePlaylistPage
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SavedPlaylistRefreshTest {
    private val now = Instant.parse("2026-11-23T12:00:00Z")
    private val saved = listOf("a", "b", "c")

    private val playlists =
        mockk<PlaylistRepository>(relaxed = true).also { coEvery { it.getPlaylistVideoIds("p") } returns saved }
    private val remote =
        mockk<YouTubePlaylistRepository>(relaxed = true).also { coEvery { it.cachedComplete("p") } returns null }
    private val checks = mockk<SavedPlaylistSyncStore>(relaxed = true)
    private val refresh = SavedPlaylistRefresh("p", playlists, remote, checks, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `a copy checked within six hours requests nothing and writes nothing`() =
        runTest {
            lastCheckedAgo(Duration.ofHours(1), headerCount = 5, owner = "Owner")

            val header = refresh.run()

            assertThat(header).isEqualTo(SavedPlaylistHeader(ownerName = "Owner", hidesUnavailable = true))
            coVerify(exactly = 0) { remote.firstPage(any()) }
            coVerify(exactly = 0) { playlists.syncSavedPlaylistVideos(any(), any()) }
            coVerify(exactly = 0) { checks.recordCheck(any(), any()) }
        }

    @Test
    fun `a stale copy whose first page matches is only re-stamped`() =
        runTest {
            lastCheckedAgo(Duration.ofHours(7))
            coEvery { remote.firstPage("p") } returns page(listOf("a", "b"), more = true, count = 3)

            val header = refresh.run()

            assertThat(header?.ownerName).isEqualTo("Owner")
            coVerify(exactly = 0) { remote.completeFrom(any(), any()) }
            coVerify(exactly = 0) { playlists.syncSavedPlaylistVideos(any(), any()) }
            coVerify { checks.recordCheck("p", SavedPlaylistCheck(now.toEpochMilli(), 3, "Owner")) }
        }

    @Test
    fun `a stale copy whose first page differs loads every page from it and stores the list`() =
        runTest {
            lastCheckedAgo(Duration.ofHours(7))
            val first = page(listOf("new", "a"), more = true, count = 4)
            val complete = first.copy(videos = videos("new", "a", "b", "c"), continuation = null)
            coEvery { remote.firstPage("p") } returns first
            coEvery { remote.completeFrom("p", first) } returns complete

            refresh.run()

            coVerify(exactly = 1) { remote.firstPage("p") }
            coVerify(exactly = 0) { remote.complete(any()) }
            coVerify { playlists.syncSavedPlaylistVideos("p", complete.videos) }
            coVerify { checks.recordCheck("p", SavedPlaylistCheck(now.toEpochMilli(), 4, "Owner")) }
        }

    @Test
    fun `a playlist never checked is compared with its first page`() =
        runTest {
            coEvery { checks.lastCheck("p") } returns null
            coEvery { remote.firstPage("p") } returns page(saved, more = false, count = 3)

            refresh.run()

            coVerify(exactly = 1) { remote.firstPage("p") }
            coVerify(exactly = 0) { playlists.syncSavedPlaylistVideos(any(), any()) }
            coVerify { checks.recordCheck("p", any()) }
        }

    @Test
    fun `a walk that stops short stores nothing and is not recorded`() =
        runTest {
            lastCheckedAgo(Duration.ofHours(7))
            coEvery { remote.firstPage("p") } returns page(listOf("new"), more = true, count = 9)
            coEvery { remote.completeFrom("p", any()) } returns null

            refresh.run()

            coVerify(exactly = 0) { playlists.syncSavedPlaylistVideos(any(), any()) }
            coVerify(exactly = 0) { checks.recordCheck(any(), any()) }
        }

    @Test
    fun `a failed store is not recorded and shows nothing`() =
        runTest {
            lastCheckedAgo(Duration.ofHours(7))
            val first = page(listOf("new"), more = false, count = 1)
            coEvery { remote.firstPage("p") } returns first
            coEvery { remote.completeFrom("p", first) } returns first.copy(continuation = null)
            coEvery { playlists.syncSavedPlaylistVideos(any(), any()) } throws IllegalStateException("db")

            assertThat(refresh.run()).isNull()
            coVerify(exactly = 0) { checks.recordCheck(any(), any()) }
        }

    @Test
    fun `no network shows nothing and records nothing`() =
        runTest {
            lastCheckedAgo(Duration.ofHours(7))
            coEvery { remote.firstPage("p") } returns null

            assertThat(refresh.run()).isNull()
            coVerify(exactly = 0) { checks.recordCheck(any(), any()) }
        }

    private fun lastCheckedAgo(
        age: Duration,
        headerCount: Int? = 3,
        owner: String? = "Owner",
    ) {
        coEvery { checks.lastCheck("p") } returns SavedPlaylistCheck(now.minus(age).toEpochMilli(), headerCount, owner)
    }

    private fun page(
        ids: List<String>,
        more: Boolean,
        count: Int,
    ) = RemotePlaylistPage(
        title = "Title",
        ownerName = "Owner",
        ownerId = null,
        description = "",
        thumbnailUrl = "",
        videos = videos(*ids.toTypedArray()),
        continuation = if (more) "next" else null,
        videoCount = count,
    )

    private fun videos(vararg ids: String) =
        ids.map {
            Video(id = it, title = it, channelName = "", channelId = "", thumbnailUrl = "", duration = 0, viewCount = 0L, uploadDate = "")
        }
}
