package io.github.aedev.flow.ui.screens.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomePreviewSelectionTest {
    private val window = PreviewWindow(top = 0, bottom = 1000)
    private val any: (Any) -> Boolean = { true }

    private fun select(
        vararg slots: PreviewSlot,
        scrolling: Boolean = false,
        current: Any? = null,
        previewable: (Any) -> Boolean = any,
    ) = HomePreviewSelection.select(slots.toList(), window, scrolling, current, previewable)

    @Test
    fun `the fully visible card at the top is picked`() {
        assertThat(select(PreviewSlot("a", 0, 350), PreviewSlot("b", 360, 350))).isEqualTo("a")
    }

    @Test
    fun `a card whose top is below the top 40 percent is never picked`() {
        assertThat(select(PreviewSlot("a", -200, 350), PreviewSlot("b", 410, 350))).isNull()
    }

    @Test
    fun `a card scrolled more than a fifth out of view gives way to the next one`() {
        assertThat(select(PreviewSlot("a", -100, 350), PreviewSlot("b", 260, 350))).isEqualTo("b")
    }

    @Test
    fun `exactly 80 percent visible still counts`() {
        assertThat(select(PreviewSlot("a", -70, 350))).isEqualTo("a")
        assertThat(select(PreviewSlot("a", -71, 350))).isNull()
    }

    @Test
    fun `only one card is picked, the highest, and shelves are skipped`() {
        val picked =
            select(
                PreviewSlot("shelf", 0, 300),
                PreviewSlot("b", 310, 300),
                PreviewSlot("c", 310, 300),
                previewable = { it != "shelf" },
            )
        assertThat(picked).isEqualTo("b")
    }

    @Test
    fun `nothing new is picked while the list scrolls`() {
        assertThat(select(PreviewSlot("a", 0, 350), scrolling = true)).isNull()
    }

    @Test
    fun `the playing card is kept while scrolling until half of it leaves`() {
        assertThat(select(PreviewSlot("a", -170, 350), scrolling = true, current = "a")).isEqualTo("a")
        assertThat(select(PreviewSlot("a", -180, 350), scrolling = true, current = "a")).isNull()
    }

    @Test
    fun `the window excludes the padding under the top bar`() {
        val padded = PreviewWindow(top = 200, bottom = 1000)
        val picked = HomePreviewSelection.select(listOf(PreviewSlot("a", 0, 350), PreviewSlot("b", 360, 350)), padded, false, null, any)
        assertThat(picked).isEqualTo("b")
    }

    @Test
    fun `an empty window picks nothing`() {
        assertThat(HomePreviewSelection.select(listOf(PreviewSlot("a", 0, 10)), PreviewWindow(0, 0), false, null, any)).isNull()
    }
}
