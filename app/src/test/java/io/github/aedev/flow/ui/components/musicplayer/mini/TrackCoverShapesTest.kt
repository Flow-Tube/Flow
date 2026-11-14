package io.github.aedev.flow.ui.components.musicplayer.mini

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.CoverShape
import org.junit.Test

class TrackCoverShapesTest {
    @Test
    fun `the player's shape is the one the mini player gives the song, with the next one beside it`() {
        val shapes = listOf(CoverShape.CIRCLE, CoverShape.COOKIE_9, CoverShape.HEART)
        val index = miniCoverShapeIndex("dQw4w9WgXcQ", shapes.size)
        assertThat(trackCoverShapes("dQw4w9WgXcQ", shapes))
            .isEqualTo(shapes[index] to shapes[(index + 1) % shapes.size])
    }

    @Test
    fun `a single chosen shape gets a different partner`() {
        assertThat(trackCoverShapes("a", listOf(CoverShape.HEART))).isEqualTo(CoverShape.HEART to CoverShape.CIRCLE)
        assertThat(trackCoverShapes("a", listOf(CoverShape.CIRCLE))).isEqualTo(CoverShape.CIRCLE to CoverShape.COOKIE_9)
    }
}
