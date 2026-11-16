package io.github.aedev.flow.ui.screens.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.LiveChatMessage
import io.github.aedev.flow.data.repository.LiveChatRepository.ReplayMessage
import io.github.aedev.flow.data.repository.LiveChatRepository.ReplayPage
import io.github.aedev.flow.ui.screens.player.ChatReplayTimeline.Fetch
import org.junit.Test

class ChatReplayTimelineTest {
    private fun message(
        id: String,
        offsetMs: Long,
    ) = ReplayMessage(offsetMs, LiveChatMessage(id = id, author = "a", authorPhotoUrl = null, message = id, timestamp = null))

    private fun page(
        vararg messages: ReplayMessage,
        next: String? = "next",
        seek: String? = "seek",
    ) = ReplayPage(messages.toList(), next, seek)

    private fun ChatReplayTimeline.shownIds(positionMs: Long) = shownAt(positionMs).map { it.id }

    @Test
    fun `the first request starts at the position with the seed`() {
        val timeline = ChatReplayTimeline(seed = "seed")

        assertThat(timeline.fetchAt(90_000L)).isEqualTo(Fetch.Seek("seed"))
    }

    @Test
    fun `messages show as the video reaches them`() {
        val timeline = ChatReplayTimeline(seed = "seed")
        timeline.onPage(Fetch.Seek("seed"), page(message("a", 1_000), message("b", 10_000), message("c", 25_000)), 0L)

        assertThat(timeline.shownIds(0L)).isEmpty()
        assertThat(timeline.shownIds(10_000L)).containsExactly("a", "b").inOrder()
        assertThat(timeline.fetchAt(10_000L)).isEqualTo(Fetch.None)
    }

    @Test
    fun `more is fetched shortly before playback reaches the end of the fetched chat`() {
        val timeline = ChatReplayTimeline(seed = "seed")
        timeline.onPage(Fetch.Seek("seed"), page(message("a", 1_000), message("b", 25_000), next = "n1"), 0L)

        assertThat(timeline.fetchAt(19_000L)).isEqualTo(Fetch.None)
        assertThat(timeline.fetchAt(20_000L)).isEqualTo(Fetch.Next("n1"))

        timeline.onPage(Fetch.Next("n1"), page(message("b", 25_000), message("c", 40_000), next = "n2"), 20_000L)

        assertThat(timeline.shownIds(40_000L)).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `the end of the replay stops asking`() {
        val timeline = ChatReplayTimeline(seed = "seed")
        timeline.onPage(Fetch.Seek("seed"), page(message("a", 1_000), next = null), 0L)

        assertThat(timeline.fetchAt(2_000L)).isEqualTo(Fetch.None)
    }

    @Test
    fun `a jump back before the fetched chat starts over there with the seek token`() {
        val timeline = ChatReplayTimeline(seed = "seed")
        timeline.onPage(Fetch.Seek("seed"), page(message("a", 61_000), seek = "s1"), 60_000L)

        assertThat(timeline.fetchAt(30_000L)).isEqualTo(Fetch.Seek("s1"))

        timeline.onPage(Fetch.Seek("s1"), page(message("x", 31_000)), 30_000L)

        assertThat(timeline.shownIds(62_000L)).containsExactly("x")
    }

    @Test
    fun `a small step back inside the fetched chat needs no request`() {
        val timeline = ChatReplayTimeline(seed = "seed")
        timeline.onPage(Fetch.Seek("seed"), page(message("a", 1_000), message("b", 20_000)), 0L)

        assertThat(timeline.fetchAt(5_000L)).isEqualTo(Fetch.None)
        assertThat(timeline.shownIds(5_000L)).containsExactly("a")
    }

    @Test
    fun `a jump forward past the fetched chat asks at the new position`() {
        val timeline = ChatReplayTimeline(seed = "seed")
        timeline.onPage(Fetch.Seek("seed"), page(message("a", 1_000), message("b", 20_000), seek = "s1"), 0L)

        assertThat(timeline.fetchAt(3_600_000L)).isEqualTo(Fetch.Seek("s1"))
    }

    @Test
    fun `a quiet stretch is not asked for again every second`() {
        val timeline = ChatReplayTimeline(seed = "seed")
        timeline.onPage(Fetch.Seek("seed"), page(), 0L)

        assertThat(timeline.fetchAt(1_000L)).isEqualTo(Fetch.None)
        assertThat(timeline.fetchAt(4_000L)).isEqualTo(Fetch.None)
        assertThat(timeline.fetchAt(5_000L)).isEqualTo(Fetch.Next("next"))
    }

    @Test
    fun `only the latest messages are kept behind the position`() {
        val timeline = ChatReplayTimeline(seed = "seed", maxShown = 2)
        timeline.onPage(Fetch.Seek("seed"), page(message("a", 1_000), message("b", 2_000), message("c", 3_000)), 0L)
        timeline.onPage(Fetch.Next("next"), page(message("d", 4_000)), 3_500L)

        assertThat(timeline.shownIds(4_000L)).containsExactly("c", "d").inOrder()
        assertThat(timeline.fetchAt(1_500L)).isEqualTo(Fetch.Seek("seek"))
    }
}
