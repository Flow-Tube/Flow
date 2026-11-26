package io.github.aedev.flow.data.music.artwork

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LoadedLoopTest {
    private val loaded = LoadedLoop("song", AnimatedLoop(square = "square.m3u8", tall = "tall.m3u8"))

    @Test
    fun `the song it was looked up for gets the loop for its space`() {
        assertThat(loaded.videoFor("song", tallSpace = true)).isEqualTo(LoopVideo("tall.m3u8", isCanvas = false))
        assertThat(loaded.videoFor("song", tallSpace = false)).isEqualTo(LoopVideo("square.m3u8", isCanvas = false))
    }

    @Test
    fun `a canvas plays in every space and says it is one`() {
        val canvas = LoadedLoop("song", AnimatedLoop.canvas("canvas.mp4"))

        assertThat(canvas.videoFor("song", tallSpace = true)).isEqualTo(LoopVideo("canvas.mp4", isCanvas = true))
        assertThat(canvas.videoFor("song", tallSpace = false)).isEqualTo(LoopVideo("canvas.mp4", isCanvas = true))
    }

    @Test
    fun `any other song gets nothing`() {
        assertThat(loaded.videoFor("next", tallSpace = true)).isNull()
        assertThat(loaded.videoFor(null, tallSpace = true)).isNull()
    }
}
