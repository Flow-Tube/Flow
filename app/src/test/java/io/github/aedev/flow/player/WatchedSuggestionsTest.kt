package io.github.aedev.flow.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.WatchedThreshold
import io.github.aedev.flow.data.local.dao.WatchHistoryDao
import io.github.aedev.flow.data.local.dao.WatchProgress
import io.github.aedev.flow.data.model.Video
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WatchedSuggestionsTest {
    private val dao = mockk<WatchHistoryDao>()

    private fun preferences(hide: Boolean) =
        mockk<PlayerPreferences> {
            every { hideWatchedSuggestions } returns flowOf(hide)
            every { watchedThreshold } returns flowOf(WatchedThreshold.ALMOST_FINISHED)
            every { countImportedAsWatched } returns flowOf(false)
        }

    @Test
    fun `nothing is read while the switch is off`() =
        runTest {
            val watched = WatchedSuggestions(preferences(hide = false), dao).watchedAmong(listOf(video("a")))

            assertThat(watched).isEmpty()
            coVerify(exactly = 0) { dao.getProgressFor(any()) }
        }

    @Test
    fun `a finished video counts and a started one does not`() =
        runTest {
            coEvery { dao.getProgressFor(listOf("done", "started")) } returns
                listOf(
                    WatchProgress("done", position = 600_000L, duration = 600_000L, timestamp = 1L),
                    WatchProgress("started", position = 60_000L, duration = 600_000L, timestamp = 1L),
                )

            val watched = WatchedSuggestions(preferences(hide = true), dao).watchedAmong(listOf(video("done"), video("started")))

            assertThat(watched).containsExactly("done")
        }

    @Test
    fun `a history read that fails hides nothing`() =
        runTest {
            coEvery { dao.getProgressFor(any()) } throws IllegalStateException("closed")

            assertThat(WatchedSuggestions(preferences(hide = true), dao).watchedAmong(listOf(video("a")))).isEmpty()
        }

    private fun video(id: String) =
        Video(
            id = id,
            title = id,
            channelName = "channel",
            channelId = "channel-id",
            thumbnailUrl = "thumbnail",
            duration = 600,
            viewCount = 1L,
            uploadDate = "today",
        )
}
