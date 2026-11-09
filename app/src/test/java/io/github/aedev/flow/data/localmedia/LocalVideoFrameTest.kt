package io.github.aedev.flow.data.localmedia

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalVideoFrameTest {
    @Test
    fun `a device video reads its cover first and otherwise a frame a third of the way in`() {
        val frame = localVideoFrameFor("content://media/external/video/media/42")

        assertThat(frame).isEqualTo(LocalVideoFrame(preferEmbeddedThumbnail = true, framePercent = 1.0 / 3))
    }

    @Test
    fun `a video on another volume gets the same still`() {
        assertThat(localVideoFrameFor("content://media/external_primary/video/media/7"))
            .isEqualTo(localVideoFrameFor("content://media/external/video/media/42"))
    }

    @Test
    fun `a network thumbnail keeps its plain request`() {
        assertThat(localVideoFrameFor("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg")).isNull()
    }

    @Test
    fun `a device song keeps its plain request`() {
        assertThat(localVideoFrameFor("content://media/external/audio/media/42")).isNull()
    }

    @Test
    fun `a saved download cover keeps its plain request`() {
        assertThat(localVideoFrameFor("file:///data/user/0/io.github.aedev.flow/files/thumbs/abc.jpg")).isNull()
    }
}
