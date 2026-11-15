package io.github.aedev.flow.ui.components.videoplayer.sheet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterHighlightPolicyTest {
    private val tapped = PendingChapterSeek(index = 3, targetMs = 1_085_000L, positionAtTapMs = 412_000L)
    private val nextChapterStartMs = 1_400_000L

    @Test
    fun `a reading from before the seek keeps the tapped chapter`() {
        assertFalse(tapped.isReleasedAt(412_000L, nextChapterStartMs))
    }

    @Test
    fun `the masked seek position does not release the tapped chapter`() {
        assertFalse(tapped.isReleasedAt(1_085_000L, nextChapterStartMs))
    }

    @Test
    fun `a keyframe landing short of the start keeps the tapped chapter`() {
        assertFalse(tapped.isReleasedAt(1_083_000L, nextChapterStartMs))
        assertFalse(tapped.isReleasedAt(1_080_000L, nextChapterStartMs))
    }

    @Test
    fun `a keyframe landing after the start keeps the tapped chapter`() {
        assertFalse(tapped.isReleasedAt(1_085_400L, nextChapterStartMs))
    }

    @Test
    fun `playback one second past the start releases the hold`() {
        assertTrue(tapped.isReleasedAt(1_086_000L, nextChapterStartMs))
    }

    @Test
    fun `a scrub outside the landing window releases the hold`() {
        assertTrue(tapped.isReleasedAt(1_079_000L, nextChapterStartMs))
        assertTrue(tapped.isReleasedAt(30_000L, nextChapterStartMs))
    }

    @Test
    fun `reaching the next chapter releases the hold`() {
        val shortChapter = tapped.copy(targetMs = 1_085_000L)
        assertTrue(shortChapter.isReleasedAt(1_085_500L, nextChapterStartMs = 1_085_500L))
    }

    @Test
    fun `the last chapter has no next chapter to reach`() {
        assertFalse(tapped.isReleasedAt(1_085_000L, nextChapterStartMs = null))
        assertTrue(tapped.isReleasedAt(1_086_000L, nextChapterStartMs = null))
    }
}
