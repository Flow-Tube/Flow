package io.github.aedev.flow.ui.components.layout.navigation

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.NavBarLabels
import org.junit.Test

class FloatingNavGeometryTest {
    private val metrics =
        FloatingNavMetrics(
            iconItem = 48f,
            icon = 24f,
            labelledStart = 16f,
            iconLabelGap = 8f,
            labelledEnd = 20f,
            namedItemMin = 64f,
            namedItemPadding = 16f,
            plainItem = 56f,
            gap = 2f,
            belowIndicatorWidth = 56f,
        )

    private fun geometry(
        names: List<Float> = listOf(40f, 44f, 42f, 34f, 50f),
        open: Int = 0,
        labels: NavBarLabels = NavBarLabels.SELECTED,
        maxWidth: Float = 320f,
        collapsed: Boolean = false,
    ) = floatingNavGeometry(names, open, labels, maxWidth, collapsed, metrics)

    @Test
    fun `the open tab is named beside its icon and the rest stay icon sized`() {
        val g = geometry(open = 1)

        assertThat(g.names).isEqualTo(FloatingNavNames.BESIDE_OPEN)
        assertThat(g.widths).containsExactly(48f, 16f + 24f + 8f + 44f + 20f, 48f, 48f, 48f).inOrder()
        assertThat(g.indicatorStart).isEqualTo(g.starts[1])
        assertThat(g.indicatorEnd).isEqualTo(g.starts[1] + g.widths[1])
    }

    @Test
    fun `items are laid out in order with a gap between them`() {
        val g = geometry(open = 0)

        assertThat(g.starts[0]).isEqualTo(0f)
        assertThat(g.starts[1]).isEqualTo(g.widths[0] + 2f)
        assertThat(g.width).isEqualTo(g.widths.sum() + 4 * 2f)
    }

    @Test
    fun `a name that does not fit drops every name and keeps the indicator on the open tab`() {
        val g = geometry(open = 4, maxWidth = 280f)

        assertThat(g.names).isEqualTo(FloatingNavNames.NONE)
        assertThat(g.widths.toSet()).containsExactly(48f)
        assertThat(g.indicatorStart).isEqualTo(g.starts[4])
    }

    @Test
    fun `icons only use the roomier item when it fits`() {
        assertThat(geometry(labels = NavBarLabels.NEVER).widths.toSet()).containsExactly(56f)
        assertThat(geometry(labels = NavBarLabels.NEVER, maxWidth = 250f).widths.toSet()).containsExactly(48f)
    }

    @Test
    fun `names under the icons put a fixed width indicator over the open icon`() {
        val g = geometry(open = 2, labels = NavBarLabels.ALWAYS, maxWidth = 400f)

        assertThat(g.names).isEqualTo(FloatingNavNames.BELOW)
        assertThat(g.widths).containsExactly(64f, 64f, 64f, 64f, 66f).inOrder()
        val centre = g.starts[2] + g.widths[2] / 2f
        assertThat(g.indicatorStart).isEqualTo(centre - 28f)
        assertThat(g.indicatorEnd).isEqualTo(centre + 28f)
    }

    @Test
    fun `names under the icons share the width when they do not fit`() {
        val g = geometry(labels = NavBarLabels.ALWAYS, maxWidth = 300f)

        assertThat(g.widths.toSet()).containsExactly((300f - 8f) / 5f)
    }

    @Test
    fun `collapsing folds the bar down to the open icon`() {
        val g = geometry(open = 3, collapsed = true)

        assertThat(g.widths).containsExactly(0f, 0f, 0f, 48f, 0f).inOrder()
        assertThat(g.width).isEqualTo(48f)
        assertThat(g.indicatorStart).isEqualTo(g.starts[3])
    }

    @Test
    fun `the item at a point is the one whose centre is nearest, skipping folded ones`() {
        val g = geometry(open = 0)

        assertThat(g.itemAt(0f)).isEqualTo(0)
        assertThat(g.itemAt(g.starts[2] + 10f)).isEqualTo(2)
        assertThat(g.itemAt(g.width + 100f)).isEqualTo(4)
        assertThat(geometry(open = 3, collapsed = true).itemAt(0f)).isEqualTo(3)
    }
}
