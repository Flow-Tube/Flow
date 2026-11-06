package io.github.aedev.flow.ui.components.musicplayer.lyrics

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.lyrics.LyricsEntry
import org.junit.Test

class LyricsPanelStateTest {
    private val lines =
        listOf(
            LyricsEntry(0L, ""),
            LyricsEntry(1_000L, "first"),
            LyricsEntry(5_000L, "second"),
            LyricsEntry(9_000L, "third"),
            LyricsEntry(13_000L, "fourth"),
        )

    private fun tracking(position: Long) = LyricsPanelState(position).apply { startTracking(position) }

    @Test
    fun `the sung line becomes active and the panel scrolls to it`() {
        val state = tracking(0L)
        state.advance(lines, 5_500L, hasWordTimings = false)
        assertThat(state.activeLineIndices).containsExactly(2)
        assertThat(state.scrollTargetIndex).isEqualTo(2)
        assertThat(state.currentPosition).isEqualTo(5_500L)
    }

    @Test
    fun `a seek back more than two seconds retargets the scroll`() {
        val state = tracking(0L)
        state.advance(lines, 9_500L, hasWordTimings = false)
        state.advance(lines, 1_200L, hasWordTimings = false)
        assertThat(state.scrollTargetIndex).isEqualTo(1)
        assertThat(state.deferredCurrentLineIndex).isEqualTo(1)
    }

    @Test
    fun `a user scroll pauses auto scroll until a jump or resync`() {
        val state = tracking(0L)
        state.onUserScroll(offset = -120f, nowMs = 42L)
        assertThat(state.isAutoScrollEnabled).isFalse()
        assertThat(state.lastPreviewTime).isEqualTo(42L)

        state.jumpTo(3)
        assertThat(state.isAutoScrollEnabled).isTrue()
        assertThat(state.scrollTargetIndex).isEqualTo(3)
        assertThat(state.deferredCurrentLineIndex).isEqualTo(3)
    }

    @Test
    fun `resync returns to the line being sung`() {
        val state = tracking(0L)
        state.advance(lines, 9_200L, hasWordTimings = false)
        state.onUserScroll(offset = 300f, nowMs = 1L)
        state.resync(lines)
        assertThat(state.isAutoScrollEnabled).isTrue()
        assertThat(state.deferredCurrentLineIndex).isEqualTo(3)
        assertThat(state.lastPreviewTime).isEqualTo(0L)
    }

    @Test
    fun `a panel opened during a break settles on the line coming up`() {
        val withBreak =
            listOf(LyricsEntry(0L, ""), LyricsEntry(1_000L, "sung"), LyricsEntry(70_000L, "after"), LyricsEntry(80_000L, "last"))
        val state = tracking(30_000L)

        state.advance(withBreak, 30_000L, hasWordTimings = false)

        assertThat(state.activeLineIndices).isEmpty()
        assertThat(state.scrollTargetIndex).isEqualTo(2)
    }

    @Test
    fun `a seek back into a break settles on the line coming up`() {
        val withBreak =
            listOf(LyricsEntry(0L, ""), LyricsEntry(1_000L, "sung"), LyricsEntry(70_000L, "after"), LyricsEntry(80_000L, "last"))
        val state = tracking(0L)
        state.advance(withBreak, 85_000L, hasWordTimings = false)

        state.advance(withBreak, 30_000L, hasWordTimings = false)

        assertThat(state.scrollTargetIndex).isEqualTo(2)
    }

    @Test
    fun `a background vocal keeps its main line active`() {
        val withBackground =
            listOf(
                LyricsEntry(0L, ""),
                LyricsEntry(1_000L, "main"),
                LyricsEntry(1_500L, "(echo)", isBackground = true),
                LyricsEntry(4_000L, "next"),
            )
        val active = withMainLinesOfBackground(withBackground, findActiveLineIndices(withBackground, 1_600L))
        assertThat(active).containsAtLeast(1, 2)
        assertThat(mainLineAt(withBackground, 1_200L)).isEqualTo(1)
        // Only the echo is active here, so the scroll target falls back to it.
        assertThat(mainLineAt(withBackground, 1_600L)).isEqualTo(2)
    }

    @Test
    fun `rows are laid out from the active row with gaps only before main lines`() {
        val items =
            listOf(
                LyricsListItem.Line(0, LyricsEntry(0L, "a")),
                LyricsListItem.Indicator(0, 1_000L, 9_000L),
                LyricsListItem.Line(1, LyricsEntry(9_000L, "b")),
                LyricsListItem.Line(2, LyricsEntry(10_000L, "(echo)", isBackground = true)),
            )
        val metrics = LyricsRowMetrics(lineHeightPx = 60f, indicatorHeightPx = 72f, constraintLineHeightPx = 120f, gapPx = 16f)
        val offsets = lyricsRowOffsets(items, heights = mapOf(0 to 100, 1 to 50, 2 to 100, 3 to 40), activeIndex = 0, metrics = metrics)
        assertThat(offsets).containsExactly(0, 0f, 1, 100f, 2, 166f, 3, 266f)
    }
}
