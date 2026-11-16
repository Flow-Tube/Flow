package io.github.aedev.flow.ui.components.musicplayer.mini

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RingStepGlidesTest {
    private val threeMinutes = 180_000L

    @Test
    fun `a position tick steps`() {
        assertThat(ringStepGlides(0.5f, 0.5f + 1_000f / threeMinutes, threeMinutes)).isFalse()
    }

    @Test
    fun `a late tick at double speed still steps`() {
        assertThat(ringStepGlides(0.5f, 0.5f + 4_000f / threeMinutes, threeMinutes)).isFalse()
    }

    @Test
    fun `a seek glides in both directions`() {
        assertThat(ringStepGlides(0.2f, 0.6f, threeMinutes)).isTrue()
        assertThat(ringStepGlides(0.6f, 0.2f, threeMinutes)).isTrue()
    }

    @Test
    fun `a new track glides back to the start`() {
        assertThat(ringStepGlides(0.8f, 0f, threeMinutes)).isTrue()
    }

    @Test
    fun `an unknown duration glides`() {
        assertThat(ringStepGlides(0.8f, 0f, 0L)).isTrue()
    }
}
