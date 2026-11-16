package io.github.aedev.flow.ui.screens.subscriptions

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Video
import org.junit.Test

/** Pinned channels at the start of Quick access, and the Most watched sort (#1776). */
class SubscriptionPinsTest {
    private fun channel(
        id: String,
        isMusic: Boolean = false,
    ) = Channel(id = id, name = id, thumbnailUrl = "", subscriberCount = 0L, isMusic = isMusic)

    private val channels = listOf(channel("a"), channel("m", isMusic = true), channel("b"), channel("c"), channel("d"))

    @Test
    fun `pins lead in pin order, ignoring the music split`() {
        val row = quickAccessChannels(channels, listOf("m", "c"), limit = 4)

        assertThat(row.channels.map { it.id }).containsExactly("m", "c", "a", "b").inOrder()
        assertThat(row.pinnedCount).isEqualTo(2)
    }

    @Test
    fun `every pin shows even past the limit`() {
        val row = quickAccessChannels(channels, listOf("d", "c", "b"), limit = 2)

        assertThat(row.channels.map { it.id }).containsExactly("d", "c", "b").inOrder()
    }

    @Test
    fun `a pin for a channel no longer subscribed is skipped`() {
        val row = quickAccessChannels(channels, listOf("gone", "b"), limit = 3)

        assertThat(row.channels.map { it.id }).containsExactly("b", "a", "c").inOrder()
        assertThat(row.pinnedCount).isEqualTo(1)
    }

    @Test
    fun `pins round-trip through storage and toggle`() {
        val pins = parsePinnedChannels(" UCa,UCb,,UCa ")

        assertThat(pins).containsExactly("UCa", "UCb").inOrder()
        assertThat(parsePinnedChannels(pins.toPinnedStorage())).isEqualTo(pins)
        assertThat(togglePinned(pins, "UCc")).containsExactly("UCa", "UCb", "UCc").inOrder()
        assertThat(togglePinned(pins, "UCa")).containsExactly("UCb")
        assertThat(parsePinnedChannels(null)).isEmpty()
    }

    @Test
    fun `reordering the pins one tab shows leaves the other tab's pins in place`() {
        val all = listOf("v1", "m1", "v2", "m2", "v3")

        assertThat(reorderPinnedSubset(all, listOf("v3", "v1", "v2"))).containsExactly("v3", "m1", "v1", "m2", "v2").inOrder()
        assertThat(reorderPinnedSubset(all, listOf("m2", "m1"))).containsExactly("v1", "m2", "v2", "m1", "v3").inOrder()
    }

    @Test
    fun `most watched puts the channels watched most first`() {
        val sorted =
            sortSubscriptions(
                channels = channels,
                sortMode = SubscriptionSortMode.MOST_WATCHED,
                recentVideos = emptyList<Video>(),
                watchCounts = mapOf("c" to 5, "a" to 2),
            )

        assertThat(sorted.map { it.id }).containsExactly("c", "a", "m", "b", "d").inOrder()
    }
}
