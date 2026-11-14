package io.github.aedev.flow.ui.components.shared.card

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.dao.WatchProgress
import org.junit.Test

class VideoCardStateTest {
    private fun entry(
        videoId: String,
        position: Long,
        duration: Long,
    ) = WatchProgress(
        videoId = videoId,
        position = position,
        duration = duration,
        timestamp = 0L,
    )

    @Test
    fun `partially watched videos map to fractional progress`() {
        val map = listOf(entry("a", position = 50, duration = 100)).toWatchProgressMap()

        assertThat(map["a"]).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `barely started videos are omitted so cards show no bar`() {
        val map = listOf(entry("a", position = 2, duration = 100)).toWatchProgressMap()

        assertThat(map).isEmpty()
    }

    @Test
    fun `the three percent threshold is inclusive`() {
        val map = listOf(entry("a", position = 3, duration = 100)).toWatchProgressMap()

        assertThat(map["a"]).isWithin(0.001f).of(0.03f)
    }

    @Test
    fun `near-complete videos fill the bar rather than stopping short`() {
        val map = listOf(entry("a", position = 95, duration = 100)).toWatchProgressMap()

        assertThat(map["a"]).isEqualTo(1f)
    }

    @Test
    fun `entries without a known duration are omitted`() {
        val map = listOf(entry("a", position = 10, duration = 0)).toWatchProgressMap()

        assertThat(map).isEmpty()
    }

    @Test
    fun `every qualifying entry is keyed by video id in one pass`() {
        val map =
            listOf(
                entry("a", position = 50, duration = 100),
                entry("b", position = 1, duration = 100),
                entry("c", position = 99, duration = 100),
            ).toWatchProgressMap()

        assertThat(map.keys).containsExactly("a", "c")
    }

    @Test
    fun `an empty history produces an empty map`() {
        assertThat(emptyList<WatchProgress>().toWatchProgressMap()).isEmpty()
    }

    @Test
    fun `a video marked watched reads as watched from its history entry alone`() {
        val progress = listOf(entry("a", position = 1_000, duration = 1_000)).toWatchProgressMap()["a"]

        assertThat(isWatchedProgress(progress)).isTrue()
    }

    @Test
    fun `clearing progress makes a finished video read as unwatched`() {
        val cleared = listOf(entry("a", position = 0, duration = 1_000)).toWatchProgressMap()["a"]

        assertThat(isWatchedProgress(cleared)).isFalse()
    }

    @Test
    fun `watched starts at ninety percent`() {
        assertThat(isWatchedProgress(0.89f)).isFalse()
        assertThat(isWatchedProgress(0.90f)).isTrue()
    }

    @Test
    fun `no progress is not watched`() {
        assertThat(isWatchedProgress(null)).isFalse()
    }

    @Test
    fun `imported videos fill the bar only when counted as watched`() {
        val entries = listOf(entry("imported", position = 0, duration = 0), entry("half", position = 50, duration = 100))

        assertThat(entries.toWatchProgressMap()).doesNotContainKey("imported")
        assertThat(entries.toWatchProgressMap(countImported = true)["imported"]).isEqualTo(1f)
        assertThat(entries.toWatchProgressMap(countImported = true)["half"]).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `imported music and local rows never fill a bar`() {
        val entries =
            listOf(
                WatchProgress("song", position = 0, duration = 0, timestamp = 0L, isMusic = true),
                WatchProgress("file", position = 0, duration = 0, timestamp = 0L, isLocal = true),
            )

        assertThat(entries.toWatchProgressMap(countImported = true)).isEmpty()
    }

    @Test
    fun `a row marked unwatched after import stops counting`() {
        assertThat(listOf(entry("cleared", position = 0, duration = 1)).toWatchProgressMap(countImported = true)).isEmpty()
    }
}
