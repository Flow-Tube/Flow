package io.github.aedev.flow.ui.screens.subscriptions

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.SubscriptionGroup
import org.junit.Test

/** The optional Ungrouped chip (#1640): what it covers, where it sits, and when it shows. */
class SubscriptionGroupSelectionTest {
    private val tech = SubscriptionGroup("Tech", listOf("UCa", "UCb"))
    private val music = SubscriptionGroup("Music", listOf("UCc"))
    private val subscribed = listOf("UCa", "UCb", "UCc", "UCd", "UCe")

    private fun channel(id: String) = Channel(id = id, name = id, thumbnailUrl = "", subscriberCount = 0L)

    private fun state(
        enabled: Boolean = true,
        position: Int = UNGROUPED_LAST,
        groups: List<SubscriptionGroup> = listOf(tech, music),
        selected: String? = null,
        channels: List<String> = subscribed,
    ) = SubscriptionsUiState(
        subscribedChannels = channels.map(::channel),
        groups = groups,
        selectedGroupName = selected,
        ungroupedEnabled = enabled,
        ungroupedPosition = position,
    )

    @Test
    fun `ungrouped covers the channels in no group`() {
        assertThat(ungroupedChannelIds(subscribed, listOf(tech, music))).containsExactly("UCd", "UCe").inOrder()
        assertThat(state(selected = UNGROUPED_GROUP_KEY).selectedGroupScope()).containsExactly("UCd", "UCe")
    }

    @Test
    fun `the chip sits last by default and at its saved place otherwise`() {
        assertThat(state().groupChips().map { it.name }).containsExactly("Tech", "Music", UNGROUPED_GROUP_KEY).inOrder()
        assertThat(state(position = 0).groupChips().map { it.name }).containsExactly(UNGROUPED_GROUP_KEY, "Tech", "Music").inOrder()
        assertThat(state(position = 9).groupChips().map { it.name }).containsExactly("Tech", "Music", UNGROUPED_GROUP_KEY).inOrder()
    }

    @Test
    fun `no chip while switched off, without groups, or with every channel grouped`() {
        assertThat(state(enabled = false).groupChips().none { it.isUngrouped() }).isTrue()
        assertThat(state(groups = emptyList()).groupChips()).isEmpty()
        assertThat(state(channels = listOf("UCa", "UCc")).groupChips().none { it.isUngrouped() }).isTrue()
    }

    @Test
    fun `a selected Ungrouped without its chip falls back to All`() {
        val off = state(enabled = false, selected = UNGROUPED_GROUP_KEY)
        assertThat(off.selectedChip()).isNull()
        assertThat(off.selectedGroupScope()).isNull()
    }

    @Test
    fun `a reordered list splits into the group order and the chip's place`() {
        assertThat(splitUngroupedOrder(listOf("Tech", UNGROUPED_GROUP_KEY, "Music"))).isEqualTo(listOf("Tech", "Music") to 1)
        assertThat(splitUngroupedOrder(listOf("Tech", "Music", UNGROUPED_GROUP_KEY))).isEqualTo(listOf("Tech", "Music") to UNGROUPED_LAST)
        assertThat(splitUngroupedOrder(listOf("Music", "Tech"))).isEqualTo(listOf("Music", "Tech") to null)
    }

    @Test
    fun `a named group keeps its own channels`() {
        assertThat(state(selected = "Tech").selectedGroupScope()).containsExactly("UCa", "UCb")
        assertThat(state(selected = "Gone").selectedGroupScope()).isNull()
    }
}
