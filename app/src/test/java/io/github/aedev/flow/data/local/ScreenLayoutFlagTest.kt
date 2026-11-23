package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenLayoutFlagTest {
    @Test
    fun `an untouched grid flag follows Home`() {
        assertThat(screenLayoutFlag(stored = null, homeViewMode = null, flagMeansList = false)).isTrue()
        assertThat(screenLayoutFlag(stored = null, homeViewMode = HomeViewMode.GRID.name, flagMeansList = false)).isTrue()
        assertThat(screenLayoutFlag(stored = null, homeViewMode = HomeViewMode.LIST.name, flagMeansList = false)).isFalse()
    }

    @Test
    fun `an untouched list flag follows Home`() {
        assertThat(screenLayoutFlag(stored = null, homeViewMode = null, flagMeansList = true)).isFalse()
        assertThat(screenLayoutFlag(stored = null, homeViewMode = HomeViewMode.GRID.name, flagMeansList = true)).isFalse()
        assertThat(screenLayoutFlag(stored = null, homeViewMode = HomeViewMode.LIST.name, flagMeansList = true)).isTrue()
    }

    @Test
    fun `a screen's own choice wins over Home`() {
        assertThat(screenLayoutFlag(stored = false, homeViewMode = HomeViewMode.GRID.name, flagMeansList = false)).isFalse()
        assertThat(screenLayoutFlag(stored = true, homeViewMode = HomeViewMode.LIST.name, flagMeansList = false)).isTrue()
        assertThat(screenLayoutFlag(stored = true, homeViewMode = HomeViewMode.GRID.name, flagMeansList = true)).isTrue()
        assertThat(screenLayoutFlag(stored = false, homeViewMode = HomeViewMode.LIST.name, flagMeansList = true)).isFalse()
    }
}
