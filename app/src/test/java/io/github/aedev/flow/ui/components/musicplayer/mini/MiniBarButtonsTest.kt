package io.github.aedev.flow.ui.components.musicplayer.mini

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.MiniBarButton
import org.junit.Test

class MiniBarButtonsTest {
    @Test
    fun `a wide window puts Previous first when it isn't chosen`() {
        assertThat(barButtons(listOf(MiniBarButton.PLAY_PAUSE, MiniBarButton.NEXT), showPrevious = true))
            .containsExactly(MiniBarButton.PREVIOUS, MiniBarButton.PLAY_PAUSE, MiniBarButton.NEXT)
            .inOrder()
    }

    @Test
    fun `Previous is never shown twice, and a narrow window adds nothing`() {
        val chosen = listOf(MiniBarButton.NEXT, MiniBarButton.PREVIOUS)
        assertThat(barButtons(chosen, showPrevious = true)).isEqualTo(chosen)
        assertThat(barButtons(listOf(MiniBarButton.LIKE), showPrevious = false)).containsExactly(MiniBarButton.LIKE)
    }
}
