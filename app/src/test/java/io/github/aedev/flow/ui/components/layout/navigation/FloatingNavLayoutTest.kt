package io.github.aedev.flow.ui.components.layout.navigation

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.NavBarLabels
import org.junit.Test

class FloatingNavLayoutTest {
    private fun choose(
        slots: Int,
        labels: NavBarLabels = NavBarLabels.ALWAYS,
        expanding: Int = 300,
        pill: Int = 280,
        maxWidth: Int = 336,
    ) = chooseFloatingNavLayout(slots, labels, maxWidth, { expanding }, { pill })

    @Test
    fun `up to four tabs expand when the open name fits`() {
        assertThat(choose(slots = 4)).isEqualTo(FloatingNavLayout.EXPANDING)
        assertThat(choose(slots = 2, labels = NavBarLabels.SELECTED)).isEqualTo(FloatingNavLayout.EXPANDING)
    }

    @Test
    fun `five slots use the pill`() {
        assertThat(choose(slots = 5)).isEqualTo(FloatingNavLayout.PILL)
    }

    @Test
    fun `an expanding bar too wide for the window falls back to the pill`() {
        assertThat(choose(slots = 4, expanding = 400)).isEqualTo(FloatingNavLayout.PILL)
    }

    @Test
    fun `icons only never expands`() {
        assertThat(choose(slots = 3, labels = NavBarLabels.NEVER)).isEqualTo(FloatingNavLayout.PILL)
    }

    @Test
    fun `a pill too wide stretches to the full width`() {
        assertThat(choose(slots = 5, pill = 360)).isEqualTo(FloatingNavLayout.WIDE)
    }

    @Test
    fun `the expanding width is not measured when it can't be used`() {
        var measured = false
        val expanding = {
            measured = true
            0
        }
        chooseFloatingNavLayout(5, NavBarLabels.ALWAYS, 336, expanding, { 0 })
        assertThat(measured).isFalse()
    }
}
