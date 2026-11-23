package io.github.aedev.flow.data.video

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SavedQueueWindowTest {
    @Test
    fun `a short queue is saved whole`() {
        assertThat(SavedQueueWindow.around(size = 30, index = 12, hasSource = true)).isEqualTo(SavedQueueWindow(0, 30, keepsSource = true))
    }

    @Test
    fun `a long queue keeps fifty before the current video and stops at the limit`() {
        val window = SavedQueueWindow.around(size = 6_000, index = 1_000, hasSource = false)

        assertThat(window.start).isEqualTo(950)
        assertThat(window.end - window.start).isEqualTo(SAVED_QUEUE_LIMIT)
    }

    @Test
    fun `the source is kept only when the saved part reaches the loaded end`() {
        assertThat(SavedQueueWindow.around(size = 1_200, index = 1_100, hasSource = true).keepsSource).isTrue()
        assertThat(SavedQueueWindow.around(size = 1_200, index = 100, hasSource = true).keepsSource).isFalse()
    }
}
