package io.github.aedev.flow.ui.screens.home

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import org.junit.Test

class HomeColdStartTest {
    private fun video(id: String) = Video(id, "Title $id", "Channel", "ch-$id", "", 600, 1_000, uploadDate = "1 day ago")

    private fun videos(vararg ids: String) = ids.map(::video)

    private fun ids(list: List<Video>) = list.map { it.id }

    @Test
    fun `launch shows only cached cards never on screen`() {
        val cached = videos("a", "b", "c", "d", "e", "f", "g", "h")
        val unseen = unseenCachedCards(cached) { it in setOf("a", "c") }
        assertThat(ids(unseen)).containsExactly("b", "d", "e", "f", "g", "h").inOrder()
    }

    @Test
    fun `too few unseen cards show nothing`() {
        val cached = videos("a", "b", "c", "d", "e", "f", "g")
        assertThat(unseenCachedCards(cached) { it in setOf("a", "b") }).isEmpty()
        assertThat(unseenCachedCards(emptyList()) { false }).isEmpty()
    }

    @Test
    fun `the button puts the new feed first and keeps what late merges added`() {
        val onScreen = videos("c1", "c2", "c3", "late")
        val applied = feedWithPendingApplied(onScreen, cachedIds = setOf("c1", "c2", "c3"), pending = videos("n1", "n2", "late"))
        assertThat(ids(applied)).containsExactly("n1", "n2", "late").inOrder()
    }

    @Test
    fun `appending keeps every card on screen in place`() {
        val appended = feedWithPendingAppended(videos("c1", "c2"), videos("n1", "c2", "n2"))
        assertThat(ids(appended)).containsExactly("c1", "c2", "n1", "n2").inOrder()
    }

    @Test
    fun `the new feed follows once the viewer nears the last cached card`() {
        assertThat(nearsEndOfFeed(lastVisibleIndex = 3, itemCount = 10)).isFalse()
        assertThat(nearsEndOfFeed(lastVisibleIndex = 6, itemCount = 10)).isTrue()
        assertThat(nearsEndOfFeed(lastVisibleIndex = -1, itemCount = 0)).isFalse()
    }
}
