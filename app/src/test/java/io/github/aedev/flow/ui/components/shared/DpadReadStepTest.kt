package io.github.aedev.flow.ui.components.shared

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DpadReadStepTest {
    @Test
    fun `down steps through the text and stops with its end in view`() {
        assertThat(DpadReadStep.next(top = 0f, step = 300f, height = 1000f, down = true)).isEqualTo(300f)
        assertThat(DpadReadStep.next(top = 600f, step = 300f, height = 1000f, down = true)).isEqualTo(700f)
        assertThat(DpadReadStep.next(top = 700f, step = 300f, height = 1000f, down = true)).isNull()
    }

    @Test
    fun `up steps back and stops at the top`() {
        assertThat(DpadReadStep.next(top = 700f, step = 300f, height = 1000f, down = false)).isEqualTo(400f)
        assertThat(DpadReadStep.next(top = 100f, step = 300f, height = 1000f, down = false)).isEqualTo(0f)
        assertThat(DpadReadStep.next(top = 0f, step = 300f, height = 1000f, down = false)).isNull()
    }

    @Test
    fun `text shorter than a step passes focus on at once`() {
        assertThat(DpadReadStep.next(top = 0f, step = 300f, height = 200f, down = true)).isNull()
        assertThat(DpadReadStep.next(top = 0f, step = 300f, height = 200f, down = false)).isNull()
    }
}
