package io.github.aedev.flow.ui.components.videoplayer.gesture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChapterSkipTest {
    private val starts = listOf(0L, 60_000L, 180_000L, 300_000L)

    @Test
    fun `forward opens the next chapter`() {
        assertThat(chapterSkipTarget(starts, 75_000L, forward = true)).isEqualTo(2)
    }

    @Test
    fun `forward from the last chapter goes nowhere`() {
        assertThat(chapterSkipTarget(starts, 310_000L, forward = true)).isNull()
    }

    @Test
    fun `back well into a chapter restarts it`() {
        assertThat(chapterSkipTarget(starts, 75_000L, forward = false)).isEqualTo(1)
    }

    @Test
    fun `back just after a chapter starts opens the one before`() {
        assertThat(chapterSkipTarget(starts, 62_000L, forward = false)).isEqualTo(0)
    }

    @Test
    fun `back in the first chapter restarts it`() {
        assertThat(chapterSkipTarget(starts, 1_000L, forward = false)).isEqualTo(0)
    }

    @Test
    fun `before the first chapter start, forward opens it and back goes nowhere`() {
        val late = listOf(10_000L, 60_000L)

        assertThat(chapterSkipTarget(late, 2_000L, forward = true)).isEqualTo(0)
        assertThat(chapterSkipTarget(late, 2_000L, forward = false)).isNull()
    }

    @Test
    fun `no chapters means no skip`() {
        assertThat(chapterSkipTarget(emptyList(), 5_000L, forward = true)).isNull()
    }
}
