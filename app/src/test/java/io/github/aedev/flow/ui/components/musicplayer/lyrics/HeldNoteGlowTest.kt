package io.github.aedev.flow.ui.components.musicplayer.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldNoteGlowTest {
    @Test
    fun `there is no glow outside the word`() {
        assertEquals(-1, heldNoteGlowLevel(-0.1f))
        assertEquals(-1, heldNoteGlowLevel(0f))
        assertEquals(-1, heldNoteGlowLevel(1f))
        assertEquals(-1, heldNoteGlowLevel(1.1f))
    }

    @Test
    fun `the glow peaks halfway through the word`() {
        val levels = (1..999).map { heldNoteGlowLevel(it / 1000f) }
        assertEquals(levels.max(), heldNoteGlowLevel(0.5f))
        assertEquals(63, heldNoteGlowLevel(0.5f))
    }

    @Test
    fun `the glow rises and falls without a visible step`() {
        // At 120 Hz a 1.1 s note, the shortest that glows, moves about 0.76 % per frame.
        val levels = (0..132).map { heldNoteGlowLevel((it / 132f).coerceIn(0.0001f, 0.9999f)) }
        val largestJump = levels.zipWithNext { a, b -> kotlin.math.abs(a - b) }.max()
        assertTrue("jumped $largestJump levels between frames", largestJump <= 2)
    }

    @Test
    fun `the rise mirrors the fall`() {
        for (percent in 1..49) {
            val rising = heldNoteGlowLevel(percent / 100f)
            val falling = heldNoteGlowLevel(1f - percent / 100f)
            assertTrue("$rising against $falling", kotlin.math.abs(rising - falling) <= 1)
        }
    }
}
