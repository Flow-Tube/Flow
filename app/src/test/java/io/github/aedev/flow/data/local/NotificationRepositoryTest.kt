package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.dao.NotificationDao
import io.github.aedev.flow.data.local.entity.NotificationEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NotificationRepositoryTest {
    private val dao: NotificationDao =
        mockk(relaxed = true) {
            every { getAllNotifications() } returns flowOf(emptyList())
            every { getUnreadCount() } returns flowOf(0)
        }
    private val repository = NotificationRepository(dao)

    private fun entry(videoId: String) = NotificationEntity(videoId = videoId, title = videoId, channelName = "c", thumbnailUrl = null)

    @Test
    fun `videos already in the inbox are skipped`() =
        runTest {
            coEvery { dao.existingVideoIds(any()) } returns listOf("a")

            val stored = repository.recordNew(listOf(entry("a"), entry("b")))

            assertThat(stored.map { it.videoId }).containsExactly("b")
            coVerify { dao.insertNotifications(match { list -> list.map { it.videoId } == listOf("b") }) }
        }

    @Test
    fun `one video listed twice is stored once`() =
        runTest {
            coEvery { dao.existingVideoIds(any()) } returns emptyList()

            val stored = repository.recordNew(listOf(entry("a"), entry("a")))

            assertThat(stored.map { it.videoId }).containsExactly("a")
        }

    @Test
    fun `nothing new writes nothing`() =
        runTest {
            coEvery { dao.existingVideoIds(any()) } returns listOf("a")

            repository.recordNew(listOf(entry("a")))
            repository.recordNew(emptyList())

            coVerify(exactly = 0) { dao.insertNotifications(any()) }
            coVerify(exactly = 1) { dao.existingVideoIds(any()) }
        }

    @Test
    fun `a long list is looked up in chunks SQLite accepts`() =
        runTest {
            coEvery { dao.existingVideoIds(any()) } returns emptyList()

            repository.recordNew((1..2_000).map { entry("v$it") })

            coVerify(exactly = 3) { dao.existingVideoIds(match { it.size <= 900 }) }
        }
}
