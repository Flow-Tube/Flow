package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoverShapePrefsTest {
    @Test
    fun `nothing stored gives the default six`() {
        assertThat(parseCoverShapes(null)).isEqualTo(DefaultCoverShapes)
        assertThat(parseCoverShapes("")).isEqualTo(DefaultCoverShapes)
    }

    @Test
    fun `stored shapes come back in order, unknown names skipped`() {
        assertThat(parseCoverShapes("HEART,NOT_A_SHAPE,PILL,HEART")).containsExactly(CoverShape.HEART, CoverShape.PILL).inOrder()
    }

    @Test
    fun `the paused shape defaults to a circle and can keep the song's shape`() {
        assertThat(parsePausedCoverShape(null)).isEqualTo(CoverShape.CIRCLE)
        assertThat(parsePausedCoverShape("TRACK")).isNull()
        assertThat(parsePausedCoverShape("GEM")).isEqualTo(CoverShape.GEM)
        assertThat(parsePausedCoverShape("GONE")).isEqualTo(CoverShape.CIRCLE)
    }
}
