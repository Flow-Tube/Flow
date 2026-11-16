package io.github.aedev.flow.ui.components.layout.navigation

import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FlowNavigationRuleTest {
    private fun rail(
        widthDp: Int,
        heightDp: Int,
    ): Boolean =
        usesNavigationRail(
            WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(widthDp.toFloat(), heightDp.toFloat()),
            isLandscape = widthDp > heightDp,
        )

    @Test
    fun `a portrait phone keeps the bottom bar`() {
        assertThat(rail(411, 891)).isFalse()
    }

    @Test
    fun `a landscape phone gets the rail`() {
        assertThat(rail(891, 411)).isTrue()
        assertThat(rail(780, 360)).isTrue()
    }

    @Test
    fun `tablets keep their navigation`() {
        assertThat(rail(1280, 800)).isTrue()
        assertThat(rail(800, 1280)).isFalse()
    }

    @Test
    fun `an unfolded foldable keeps its navigation`() {
        assertThat(rail(841, 701)).isTrue()
        assertThat(rail(701, 841)).isFalse()
    }

    @Test
    fun `a narrow portrait split window keeps the bottom bar`() {
        assertThat(rail(400, 600)).isFalse()
    }

    @Test
    fun `every tab shows when there is room`() {
        val tabs = FlowTab.entries.take(5)
        assertThat(splitTabsForOverflow(tabs, 5)).isEqualTo(tabs to emptyList<FlowTab>())
    }

    @Test
    fun `the last slot becomes More when the tabs do not fit`() {
        val tabs = FlowTab.entries.take(7)
        val (shown, overflow) = splitTabsForOverflow(tabs, 5)
        assertThat(shown).containsExactlyElementsIn(tabs.take(4)).inOrder()
        assertThat(overflow).containsExactlyElementsIn(tabs.drop(4)).inOrder()
    }

    @Test
    fun `a rail with room for one item shows only More`() {
        val tabs = FlowTab.entries.take(5)
        val (shown, overflow) = splitTabsForOverflow(tabs, 1)
        assertThat(shown).isEmpty()
        assertThat(overflow).isEqualTo(tabs)
    }

    @Test
    fun `rail capacity counts whole items only`() {
        assertThat(railItemCapacity(availableHeightPx = 320, itemHeightPx = 64)).isEqualTo(5)
        assertThat(railItemCapacity(availableHeightPx = 319, itemHeightPx = 64)).isEqualTo(4)
        assertThat(railItemCapacity(availableHeightPx = -10, itemHeightPx = 64)).isEqualTo(0)
        assertThat(railItemCapacity(availableHeightPx = 320, itemHeightPx = 0)).isEqualTo(0)
    }
}
