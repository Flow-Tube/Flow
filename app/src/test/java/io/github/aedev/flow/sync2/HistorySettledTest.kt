package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync2.changes.PlayerItem
import io.github.aedev.flow.sync2.changes.historySettled
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Watch history counts as changed on a pause, a stop or another item, never while one item plays on. */
class HistorySettledTest {
    private val playingA = PlayerItem("a", playing = true)
    private val pausedA = PlayerItem("a", playing = false)

    @Test
    fun a_pause_settles() = assertTrue(historySettled(playingA, pausedA))

    @Test
    fun a_stop_settles() = assertTrue(historySettled(playingA, PlayerItem(null, playing = false)))

    @Test
    fun closing_a_paused_item_settles() = assertTrue(historySettled(pausedA, PlayerItem(null, playing = false)))

    @Test
    fun the_next_item_settles_the_one_before() = assertTrue(historySettled(playingA, PlayerItem("b", playing = true)))

    @Test
    fun starting_or_resuming_does_not() {
        assertFalse(historySettled(PlayerItem(null, playing = false), playingA))
        assertFalse(historySettled(pausedA, playingA))
    }

    @Test
    fun loading_the_first_item_does_not() = assertFalse(historySettled(PlayerItem(null, playing = false), pausedA))
}
