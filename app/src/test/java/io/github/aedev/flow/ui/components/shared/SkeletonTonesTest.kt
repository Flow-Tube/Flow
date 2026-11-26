package io.github.aedev.flow.ui.components.shared

import androidx.compose.ui.graphics.Color
import io.github.aedev.flow.data.local.LoadingAnimation
import org.junit.Assert.assertEquals
import org.junit.Test

private const val CHANNEL_STEP = 2f / 255f

class SkeletonTonesTest {
    private val high = Color(0.09f, 0.09f, 0.09f)
    private val highest = Color(0.14f, 0.14f, 0.14f)
    private val plain = SkeletonGradient(high, high, highest, highest)

    private fun assertColor(
        expected: Color,
        actual: Color,
    ) {
        assertEquals(expected.red, actual.red, CHANNEL_STEP)
        assertEquals(expected.green, actual.green, CHANNEL_STEP)
        assertEquals(expected.blue, actual.blue, CHANNEL_STEP)
        assertEquals(expected.alpha, actual.alpha, CHANNEL_STEP)
    }

    @Test
    fun `subtle pulse keeps today's tones`() {
        assertEquals(plain, skeletonTones(plain, LoadingAnimation.SUBTLE_PULSE))
    }

    @Test
    fun `pulse swings twice as far from the resting tone`() {
        val tones = skeletonTones(plain, LoadingAnimation.PULSE)
        assertColor(high, tones.dimStart)
        assertColor(Color(0.19f, 0.19f, 0.19f), tones.brightStart)
        assertColor(Color(0.19f, 0.19f, 0.19f), tones.brightEnd)
    }

    @Test
    fun `a light theme stretches towards darker tones`() {
        val stretched = stretchTone(Color(0.93f, 0.93f, 0.93f), Color(0.89f, 0.89f, 0.89f), 2f)
        assertColor(Color(0.85f, 0.85f, 0.85f), stretched)
    }

    @Test
    fun `stretching never leaves the colour range`() {
        assertColor(Color(1f, 0f, 1f), stretchTone(Color(0.8f, 0.2f, 0.5f), Color(0.95f, 0.05f, 0.9f), 3f))
    }
}
