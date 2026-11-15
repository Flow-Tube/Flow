package io.github.aedev.flow.ui.components.layout.navigation

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.NavBarLabels
import org.junit.Test

class NavBarLabelsTest {
    @Test
    fun `always names every tab`() {
        assertThat(NavBarLabels.ALWAYS.shows(selected = true)).isTrue()
        assertThat(NavBarLabels.ALWAYS.shows(selected = false)).isTrue()
    }

    @Test
    fun `selected names only the open tab`() {
        assertThat(NavBarLabels.SELECTED.shows(selected = true)).isTrue()
        assertThat(NavBarLabels.SELECTED.shows(selected = false)).isFalse()
    }

    @Test
    fun `never shows icons only`() {
        assertThat(NavBarLabels.NEVER.shows(selected = true)).isFalse()
        assertThat(NavBarLabels.NEVER.shows(selected = false)).isFalse()
    }
}
