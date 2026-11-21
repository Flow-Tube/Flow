package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.dao.WatchProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WatchProgressStoreTest {
    private fun row(
        id: String,
        position: Long,
        duration: Long = 100L,
        timestamp: Long = 1L,
    ) = WatchProgress(videoId = id, position = position, duration = duration, timestamp = timestamp)

    private val history: ViewHistory = mockk(relaxed = true)

    private fun tableOf(vararg rows: WatchProgress) = WatchProgressTable().apply { replaceAll(rows.toList()) }

    @Test
    fun `a save updates only its own row`() =
        runTest {
            val table = tableOf(row("a", 10), row("b", 20))
            coEvery { history.readProgressRows(setOf("a")) } returns listOf(row("a", 50))

            val changed = table.applyChange(HistoryChange.Rows(setOf("a")), history)

            assertThat(changed).isTrue()
            assertThat(table.snapshot()).containsExactly("a", row("a", 50), "b", row("b", 20))
            coVerify(exactly = 0) { history.readAllProgress() }
        }

    @Test
    fun `a new video is added`() {
        val table = tableOf(row("a", 10))

        assertThat(table.applyRows(setOf("new"), listOf(row("new", 5)))).isTrue()
        assertThat(table.snapshot().keys).containsExactly("a", "new")
    }

    @Test
    fun `a row the database no longer holds is removed`() {
        val table = tableOf(row("a", 10), row("b", 20))

        assertThat(table.applyRows(setOf("a"), emptyList())).isTrue()
        assertThat(table.snapshot().keys).containsExactly("b")
    }

    @Test
    fun `rewriting a row with the same values reports no change`() {
        val table = tableOf(row("a", 10))

        assertThat(table.applyRows(setOf("a"), listOf(row("a", 10)))).isFalse()
        assertThat(table.applyRows(setOf("missing"), emptyList())).isFalse()
    }

    @Test
    fun `a bulk change reloads the whole table`() =
        runTest {
            val table = tableOf(row("a", 10))
            coEvery { history.readAllProgress() } returns listOf(row("x", 1), row("y", 2))

            assertThat(table.applyChange(HistoryChange.All, history)).isTrue()
            assertThat(table.snapshot().keys).containsExactly("x", "y")
        }

    @Test
    fun `a settled table that matches the database is not reread`() =
        runTest {
            val table = tableOf(row("a", 10), WatchProgress("s", 5, 30, 2, isShort = true))
            coEvery { history.progressFingerprint() } returns table.fingerprint()

            assertThat(table.applyChange(null, history)).isFalse()
            coVerify(exactly = 0) { history.readAllProgress() }
        }

    @Test
    fun `a write that bypassed the history repository triggers a full reload`() =
        runTest {
            val table = tableOf(row("a", 10))
            val synced = tableOf(row("a", 10), row("from-sync", 70))
            coEvery { history.progressFingerprint() } returns synced.fingerprint()
            coEvery { history.readAllProgress() } returns synced.snapshot().values.toList()

            assertThat(table.applyChange(null, history)).isTrue()
            assertThat(table.snapshot().keys).containsExactly("a", "from-sync")
        }

    @Test
    fun `the fingerprint notices a changed position, a new row and a changed flag`() {
        val base = tableOf(row("a", 10)).fingerprint()

        assertThat(tableOf(row("a", 11)).fingerprint()).isNotEqualTo(base)
        assertThat(tableOf(row("a", 10), row("b", 0, duration = 0, timestamp = 0)).fingerprint()).isNotEqualTo(base)
        assertThat(tableOf(WatchProgress("a", 10, 100, 1, isMusic = true)).fingerprint()).isNotEqualTo(base)
    }

    @Test
    fun `a snapshot does not change when the table moves on`() {
        val table = tableOf(row("a", 10))
        val before = table.snapshot()

        table.applyRows(setOf("a"), listOf(row("a", 90)))

        assertThat(before["a"]).isEqualTo(row("a", 10))
    }
}
