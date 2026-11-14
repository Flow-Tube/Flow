package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MiniBarButtonPrefsTest {
    @Test
    fun `nothing stored gives today's buttons`() {
        assertThat(parseMiniBarButtons(null)).containsExactly(MiniBarButton.PLAY_PAUSE, MiniBarButton.NEXT).inOrder()
    }

    @Test
    fun `every button switched off stays off`() {
        assertThat(parseMiniBarButtons("")).isEmpty()
    }

    @Test
    fun `stored buttons keep their order, skip unknowns and stop at three`() {
        assertThat(parseMiniBarButtons("LIKE,GONE,QUEUE,LIKE,NEXT,CLOSE"))
            .containsExactly(MiniBarButton.LIKE, MiniBarButton.QUEUE, MiniBarButton.NEXT)
            .inOrder()
    }
}
