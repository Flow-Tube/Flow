package io.github.aedev.flow.ui.components.videoplayer.gesture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VideoPlayerZoomFillTest {
    @Test
    fun `a wide video in a wider window fills at the ratio of the two shapes`() {
        val fill = fillScaleFor(width = 2400, height = 1080, videoAspectRatio = 16f / 9f)
        assertThat(fill).isNotNull()
        assertThat(fill!!).isWithin(0.001f).of((2400f / 1080f) / (16f / 9f))
    }

    @Test
    fun `a tall video in a wide window fills at the inverse ratio`() {
        val fill = fillScaleFor(width = 2400, height = 1080, videoAspectRatio = 9f / 16f)
        assertThat(fill!!).isWithin(0.001f).of((2400f / 1080f) / (9f / 16f))
    }

    @Test
    fun `no detent when the video already nearly fills or its shape is unknown`() {
        assertThat(fillScaleFor(width = 1920, height = 1080, videoAspectRatio = 16f / 9f)).isNull()
        assertThat(fillScaleFor(width = 2400, height = 1080, videoAspectRatio = 0f)).isNull()
    }

    @Test
    fun `within four percent the scale locks onto fill and outside it stays free`() {
        assertThat(snapToFill(1.27f, 1.25f)).isEqualTo(1.25f)
        assertThat(snapToFill(1.21f, 1.25f)).isEqualTo(1.25f)
        assertThat(snapToFill(1.35f, 1.25f)).isEqualTo(1.35f)
        assertThat(snapToFill(1.35f, null)).isEqualTo(1.35f)
    }
}
