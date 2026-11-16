package io.github.aedev.flow.data.subscriptions

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.SubscriptionGroup
import org.junit.Test

/** What the All chip covers once groups are hidden or kept out of it (#1387). */
class SubscriptionAllScopeTest {
    private val tech = SubscriptionGroup("Tech", listOf("UCa", "UCb"))
    private val music = SubscriptionGroup("Music", listOf("UCb", "UCc"))
    private val news = SubscriptionGroup("News", listOf("UCd"))
    private val groups = listOf(tech, music, news)
    private val subscribed = listOf("UCa", "UCb", "UCc", "UCd", "UCe")

    @Test
    fun `every subscription while no group is left out`() {
        assertThat(allChipScope(subscribed, groups, emptySet(), emptySet())).isNull()
        assertThat(allChipScope(subscribed, groups, setOf("Gone"), setOf("Also gone"))).isNull()
    }

    @Test
    fun `a hidden group's channels are left out`() {
        assertThat(allChipScope(subscribed, groups, setOf("News"), emptySet()))
            .containsExactly("UCa", "UCb", "UCc", "UCe")
            .inOrder()
    }

    @Test
    fun `a group kept out of All is left out the same way`() {
        assertThat(allChipScope(subscribed, groups, emptySet(), setOf("News")))
            .containsExactly("UCa", "UCb", "UCc", "UCe")
            .inOrder()
    }

    @Test
    fun `a channel also in a group All still covers stays`() {
        assertThat(allChipScope(subscribed, groups, setOf("Tech"), emptySet()))
            .containsExactly("UCb", "UCc", "UCd", "UCe")
            .inOrder()
    }

    @Test
    fun `a channel only in hidden or out-of-All groups is left out`() {
        assertThat(allChipScope(subscribed, groups, setOf("Tech"), setOf("Music")))
            .containsExactly("UCd", "UCe")
            .inOrder()
    }

    @Test
    fun `channels in no group always stay`() {
        assertThat(allChipScope(subscribed, groups, setOf("Tech", "Music", "News"), emptySet())).containsExactly("UCe")
    }

    @Test
    fun `an empty hidden group changes nothing`() {
        val empty = SubscriptionGroup("Empty", emptyList())
        assertThat(allChipScope(subscribed, groups + empty, setOf("Empty"), emptySet())).isNull()
    }
}
