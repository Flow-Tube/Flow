package io.github.aedev.flow.data.music.artwork

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LoadedLoopTest {
    private val loaded = LoadedLoop("song", AnimatedLoop(square = "square.m3u8", tall = "tall.m3u8"))

    @Test
    fun `the song it was looked up for gets the loop for its space`() {
        assertThat(loaded.urlFor("song", tallSpace = true)).isEqualTo("tall.m3u8")
        assertThat(loaded.urlFor("song", tallSpace = false)).isEqualTo("square.m3u8")
    }

    @Test
    fun `any other song gets nothing`() {
        assertThat(loaded.urlFor("next", tallSpace = true)).isNull()
        assertThat(loaded.urlFor(null, tallSpace = true)).isNull()
    }
}
