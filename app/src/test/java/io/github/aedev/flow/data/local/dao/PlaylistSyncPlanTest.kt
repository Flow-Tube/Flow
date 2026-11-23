package io.github.aedev.flow.data.local.dao

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaylistSyncPlanTest {
    private fun rows(vararg ids: String) = ids.mapIndexed { index, id -> PlaylistVideoPosition(id, index.toLong()) }

    private fun at(
        id: String,
        position: Long,
    ) = PlaylistVideoPosition(id, position)

    @Test
    fun `an unchanged playlist plans no writes`() {
        val plan = planPlaylistSync(rows("a", "b", "c"), listOf("a", "b", "c"))

        assertThat(plan.isEmpty).isTrue()
    }

    @Test
    fun `an empty playlist inserts every wanted video in order`() {
        val plan = planPlaylistSync(emptyList(), listOf("a", "b"))

        assertThat(plan.insert).containsExactly(at("a", 0), at("b", 1)).inOrder()
        assertThat(plan.remove).isEmpty()
        assertThat(plan.move).isEmpty()
    }

    @Test
    fun `videos the creator removed are removed and the rest move up`() {
        val plan = planPlaylistSync(rows("a", "gone", "b"), listOf("a", "b"))

        assertThat(plan.remove).containsExactly("gone")
        assertThat(plan.move).containsExactly(at("b", 1))
        assertThat(plan.insert).isEmpty()
    }

    @Test
    fun `a new video is inserted and only the rows after it move`() {
        val plan = planPlaylistSync(rows("a", "b", "c"), listOf("a", "new", "b", "c"))

        assertThat(plan.insert).containsExactly(at("new", 1))
        assertThat(plan.move).containsExactly(at("b", 2), at("c", 3)).inOrder()
        assertThat(plan.remove).isEmpty()
    }

    @Test
    fun `a second sync keeps every stored row, so dates added stay`() {
        val first = planPlaylistSync(emptyList(), listOf("a", "b", "c"))
        val stored = first.insert

        val second = planPlaylistSync(stored, listOf("c", "a", "b", "d"))

        assertThat(second.insert.map { it.videoId }).containsExactly("d")
        assertThat(second.remove).isEmpty()
        assertThat(second.move).containsExactly(at("c", 0), at("a", 1), at("b", 2)).inOrder()
    }

    @Test
    fun `legacy positions are renumbered once, then a later sync writes nothing`() {
        val legacy = listOf(at("a", -300), at("b", -200), at("c", 7))

        val plan = planPlaylistSync(legacy, listOf("a", "b", "c"))

        assertThat(plan.move).containsExactly(at("a", 0), at("b", 1), at("c", 2)).inOrder()
        assertThat(plan.insert).isEmpty()
        assertThat(planPlaylistSync(plan.move, listOf("a", "b", "c")).isEmpty).isTrue()
    }

    @Test
    fun `a repeated id is planned once`() {
        val plan = planPlaylistSync(emptyList(), listOf("a", "b", "a"))

        assertThat(plan.insert).containsExactly(at("a", 0), at("b", 1)).inOrder()
    }
}
