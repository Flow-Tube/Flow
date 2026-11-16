package io.github.aedev.flow.player.error

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackErrorLogTest {
    @Test
    fun `the first failure starts the log`() {
        assertThat(appendCapped(null, "one")).isEqualTo("one")
    }

    @Test
    fun `new failures go last`() {
        assertThat(appendCapped("one", "two")).isEqualTo("one\ntwo")
    }

    @Test
    fun `only the newest thirty are kept`() {
        val full = (1..30).joinToString("\n") { "line$it" }

        val lines = appendCapped(full, "line31").lines()

        assertThat(lines).hasSize(30)
        assertThat(lines.first()).isEqualTo("line2")
        assertThat(lines.last()).isEqualTo("line31")
    }
}
