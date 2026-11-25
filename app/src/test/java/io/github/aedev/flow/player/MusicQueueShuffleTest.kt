package io.github.aedev.flow.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.random.Random

/** One shuffle must not edit the player's queue, and so rebuild the system session's, once per song (#2378). */
class MusicQueueShuffleTest {
    private val queue = MutableList(1_000) { MediaItem.Builder().setMediaId("song$it").build() }
    private var current = 400
    private var queueEdits = 0
    private val player = fakePlayer()

    private fun fakePlayer(): Player =
        mockk {
            every { mediaItemCount } answers { queue.size }
            every { currentMediaItemIndex } answers { current }
            every { getMediaItemAt(any()) } answers { queue[firstArg()] }
            every { replaceMediaItems(any(), any(), any()) } answers {
                val from = firstArg<Int>()
                val to = secondArg<Int>()
                val items = thirdArg<List<MediaItem>>()
                check(current !in from until to) { "the playing song was replaced" }
                repeat(to - from) { queue.removeAt(from) }
                queue.addAll(from, items)
                if (current >= to) current += items.size - (to - from)
                queueEdits++
            }
        }

    @Test
    fun `shuffling 1,000 songs is at most two queue edits`() {
        val order = PlaylistQueueOrder.shuffleFromCurrent(queue.indices.toList(), current, Random(7)).items
        val expected = order.map { queue[it].mediaId }

        player.reorderAround(order)

        assertThat(queueEdits).isAtMost(2)
        assertThat(queue.map { it.mediaId }).isEqualTo(expected)
        assertThat(queue[current].mediaId).isEqualTo("song400")
        assertThat(current).isEqualTo(0)
    }

    @Test
    fun `the app's shuffle keeps the song playing and matches the queue it shows`() {
        val ids = queue.map { it.mediaId }
        val target = listOf(ids[current]) + ids.filterIndexed { index, _ -> index != current }.shuffled(Random(3))

        PlaylistQueueOrder.indexOrder(ids, current, target, targetIndex = 0)!!.let(player::reorderAround)

        assertThat(queueEdits).isAtMost(2)
        assertThat(queue.map { it.mediaId }).isEqualTo(target)
        assertThat(queue[current].mediaId).isEqualTo("song400")
    }

    @Test
    fun `an order that leaves out the playing song changes nothing`() {
        player.reorderAround(queue.indices.filter { it != current } + 0)

        assertThat(queueEdits).isEqualTo(0)
    }
}
