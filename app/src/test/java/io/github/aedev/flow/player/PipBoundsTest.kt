package io.github.aedev.flow.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipBoundsTest {
    @Test
    fun `a video that fills its box keeps the whole box`() {
        val box = PipBounds(0, 100, 1080, 708)

        assertEquals(box, pipSourceBounds(box, 1080f / 608f))
    }

    @Test
    fun `a narrow video in a wide box is pillarboxed around the centre`() {
        val box = PipBounds(0, 0, 1920, 1080)

        assertEquals(PipBounds(240, 0, 1680, 1080), pipSourceBounds(box, 4f / 3f))
    }

    @Test
    fun `a wide video in a tall box is letterboxed around the centre`() {
        val box = PipBounds(0, 200, 1080, 2120)

        assertEquals(PipBounds(0, 856, 1080, 1464), pipSourceBounds(box, 16f / 9f))
    }

    @Test
    fun `an empty box or ratio gives no hint`() {
        assertNull(pipSourceBounds(PipBounds(0, 0, 0, 0), 16f / 9f))
        assertNull(pipSourceBounds(PipBounds(0, 0, 100, 100), 0f))
    }
}
