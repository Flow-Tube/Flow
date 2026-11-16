package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsChecksTest {
    private val timed = (0..5).map { LyricsEntry(1_000L + it * 5_000L, "line $it") }

    private fun worded(entry: LyricsEntry) =
        entry.copy(words = listOf(WordTimestamp("a", entry.time, entry.time + 500), WordTimestamp("b", entry.time + 500, entry.time + 900)))

    @Test
    fun `timed lines without words are synced but not word synced`() {
        assertThat(LyricsChecks.entriesAreSynced(timed)).isTrue()
        assertThat(LyricsChecks.hasWordSync(timed)).isFalse()
    }

    @Test
    fun `lines with words are word synced`() {
        assertThat(LyricsChecks.hasWordSync(timed.map(::worded))).isTrue()
    }

    @Test
    fun `one block of text is not synced`() {
        assertThat(LyricsChecks.entriesAreSynced(listOf(LyricsEntry(0L, "all of it")))).isFalse()
    }

    @Test
    fun `lines bunched in a few seconds are unreasonable`() {
        val bunched = (0..5).map { LyricsEntry(1_000L + it * 100L, "line $it") }

        assertThat(LyricsChecks.hasReasonableTimestamps(bunched, durationSec = 200)).isFalse()
        assertThat(LyricsChecks.hasReasonableTimestamps(timed, durationSec = 200)).isTrue()
    }

    @Test
    fun `a refused request is an auth failure and a miss is not`() {
        assertThat(LyricsChecks.isAuthFailure("API returned 401")).isTrue()
        assertThat(LyricsChecks.isAuthFailure("No lyrics found")).isFalse()
    }
}
