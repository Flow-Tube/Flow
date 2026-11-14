package io.github.aedev.flow.ui.components.musicplayer.queue

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class QueueSelectionTest {
    private fun track(id: String): MusicTrack = mockk { every { videoId } returns id }

    /** Applies [moves] the way the player does, to see where every row ends up. */
    private fun apply(
        size: Int,
        moves: List<Pair<Int, Int>>,
    ): List<Int> {
        val rows = MutableList(size) { it }
        moves.forEach { (from, to) -> rows.add(to, rows.removeAt(from)) }
        return rows
    }

    @Test
    fun `a song queued twice gets two keys`() {
        assertThat(queueKeys(listOf(track("a"), track("b"), track("a")))).containsExactly("a#1", "b#1", "a#2").inOrder()
    }

    @Test
    fun `picked songs after the playing one move up behind it in order`() {
        val moves = playNextMoves(picked = listOf(5, 3), current = 1, size = 7)

        assertThat(apply(7, moves)).containsExactly(0, 1, 3, 5, 2, 4, 6).inOrder()
    }

    @Test
    fun `picked songs before the playing one move behind it too`() {
        val moves = playNextMoves(picked = listOf(0, 4), current = 2, size = 6)

        assertThat(apply(6, moves)).containsExactly(1, 2, 0, 4, 3, 5).inOrder()
    }

    @Test
    fun `the playing song is never moved or removed`() {
        assertThat(apply(4, playNextMoves(picked = listOf(1), current = 1, size = 4))).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(removalOrder(picked = listOf(0, 1, 3), current = 1)).containsExactly(3, 0).inOrder()
    }

    @Test
    fun `select all picks everything, then nothing`() {
        val selection = QueueSelection()
        selection.start()
        selection.toggleAll(listOf("a#1", "b#1"))
        assertThat(selection.keys).containsExactly("a#1", "b#1")
        selection.toggleAll(listOf("a#1", "b#1"))
        assertThat(selection.keys).isEmpty()
    }

    @Test
    fun `picks of songs that left the queue are dropped`() {
        val selection = QueueSelection()
        selection.start("a#1")
        selection.toggle("b#1")
        selection.retain(listOf("b#1", "c#1"))
        assertThat(selection.keys).containsExactly("b#1")
    }
}
