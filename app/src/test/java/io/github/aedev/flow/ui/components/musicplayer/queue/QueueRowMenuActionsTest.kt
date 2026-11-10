package io.github.aedev.flow.ui.components.musicplayer.queue

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueueRowMenuActionsTest {
    @Test
    fun `a radio row can only join the queue`() {
        assertThat(queueRowMenuActions(isRadioItem = true, index = -1, lastIndex = 5))
            .containsExactly(QueueRowMenuAction.PLAY_NEXT, QueueRowMenuAction.ADD_TO_QUEUE)
            .inOrder()
    }

    @Test
    fun `a middle row moves either way`() {
        assertThat(queueRowMenuActions(isRadioItem = false, index = 2, lastIndex = 5))
            .containsExactly(
                QueueRowMenuAction.PLAY_NEXT,
                QueueRowMenuAction.MOVE_UP,
                QueueRowMenuAction.MOVE_DOWN,
                QueueRowMenuAction.MOVE_TO_END,
                QueueRowMenuAction.REMOVE,
            ).inOrder()
    }

    @Test
    fun `the first row can't move up and the last can't move down`() {
        assertThat(queueRowMenuActions(isRadioItem = false, index = 0, lastIndex = 3)).doesNotContain(QueueRowMenuAction.MOVE_UP)
        assertThat(queueRowMenuActions(isRadioItem = false, index = 3, lastIndex = 3))
            .containsExactly(QueueRowMenuAction.PLAY_NEXT, QueueRowMenuAction.MOVE_UP, QueueRowMenuAction.REMOVE)
            .inOrder()
    }
}
