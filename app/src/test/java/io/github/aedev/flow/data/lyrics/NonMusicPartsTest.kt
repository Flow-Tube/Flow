package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

private const val VIDEO_MS = 400_000L

class NonMusicPartsTest {
    private val intro = NonMusicPart(0L, 75_000L)

    private fun lines(vararg times: Long) = times.map { LyricsEntry(it, "Line $it") }

    @Test
    fun `lines sung inside a non-music part move past it`() {
        val moved = onVideoClock(lines(5_800L, 20_000L, 200_000L), listOf(intro), VIDEO_MS)

        assertThat(moved.map { it.time }).containsExactly(80_800L, 95_000L, 275_000L).inOrder()
    }

    @Test
    fun `lyrics already on the video's clock stay where they are`() {
        val entries = lines(36_000L, 60_000L)

        assertThat(onVideoClock(entries, listOf(NonMusicPart(0L, 23_100L), NonMusicPart(241_700L, 262_500L)), VIDEO_MS))
            .isSameInstanceAs(entries)
    }

    @Test
    fun `moving lyrics twice changes nothing`() {
        val once = onVideoClock(lines(5_800L, 20_000L, 40_000L), listOf(intro), VIDEO_MS)

        assertThat(onVideoClock(once, listOf(intro), VIDEO_MS)).isEqualTo(once)
    }

    @Test
    fun `one stray line is not enough to move anything`() {
        val entries = lines(5_000L, 80_000L)

        assertThat(onVideoClock(entries, listOf(intro), VIDEO_MS)).isSameInstanceAs(entries)
    }

    @Test
    fun `lines right at the end of a part stay`() {
        val entries = lines(74_000L, 74_500L, 90_000L)

        assertThat(onVideoClock(entries, listOf(intro), VIDEO_MS)).isSameInstanceAs(entries)
    }

    @Test
    fun `a part in the middle only moves the lines after it`() {
        val parts = listOf(NonMusicPart(0L, 10_000L), NonMusicPart(50_000L, 60_000L))

        val moved = onVideoClock(lines(1_000L, 5_000L, 30_000L, 45_000L), parts, VIDEO_MS)

        // 45 s of song lands at 55 s of video, which is inside the second part, so it moves past that too.
        assertThat(moved.map { it.time }).containsExactly(11_000L, 15_000L, 40_000L, 65_000L).inOrder()
    }

    @Test
    fun `overlapping parts count once`() {
        val parts = listOf(NonMusicPart(0L, 38_900L), NonMusicPart(36_200L, 43_600L))

        val moved = onVideoClock(lines(1_000L, 8_000L), parts, VIDEO_MS)

        assertThat(moved.map { it.time }).containsExactly(44_600L, 51_600L).inOrder()
    }

    @Test
    fun `word timings move with their line`() {
        val entries =
            listOf(
                LyricsEntry(
                    1_000L,
                    "Hello there",
                    words = listOf(WordTimestamp("Hello", 1_000L, 1_500L), WordTimestamp("there", 1_600L, 2_000L)),
                ),
                LyricsEntry(8_000L, "Second"),
            )

        val moved = onVideoClock(entries, listOf(intro), VIDEO_MS)

        assertThat(moved.first().words!!.map { it.startTime to it.endTime })
            .containsExactly(76_000L to 76_500L, 76_600L to 77_000L)
            .inOrder()
    }

    @Test
    fun `backing lines do not count towards moving but move with the rest`() {
        val backing =
            listOf(
                LyricsEntry(1_000L, "Ooh", isBackground = true),
                LyricsEntry(2_000L, "Ooh", isBackground = true),
                LyricsEntry(80_000L, "Line"),
            )

        assertThat(onVideoClock(backing, listOf(intro), VIDEO_MS)).isSameInstanceAs(backing)
        val withLines = backing + lines(3_000L, 4_000L)
        assertThat(onVideoClock(withLines, listOf(intro), VIDEO_MS).first().time).isEqualTo(76_000L)
    }

    @Test
    fun `lyrics that would run past the end of the video stay`() {
        val spokenOutro = NonMusicPart(200_000L, 245_000L)
        val entries = lines(3_000L, 100_000L, 205_000L, 220_000L, 241_000L)

        assertThat(onVideoClock(entries, listOf(spokenOutro), videoMs = 245_000L)).isSameInstanceAs(entries)
    }

    @Test
    fun `lyrics that only just fit move`() {
        val moved = onVideoClock(lines(5_000L, 10_000L, 125_000L), listOf(intro), videoMs = 200_000L)

        assertThat(moved.last().time).isEqualTo(200_000L)
    }

    @Test
    fun `lyrics stay when the video's length is unknown`() {
        val entries = lines(5_000L, 10_000L)

        assertThat(onVideoClock(entries, listOf(intro), videoMs = 0L)).isSameInstanceAs(entries)
    }

    @Test
    fun `no parts leave lyrics alone`() {
        val entries = lines(1_000L, 2_000L)

        assertThat(onVideoClock(entries, emptyList(), VIDEO_MS)).isSameInstanceAs(entries)
    }
}
