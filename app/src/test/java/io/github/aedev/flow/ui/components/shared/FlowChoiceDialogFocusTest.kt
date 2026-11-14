package io.github.aedev.flow.ui.components.shared

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FlowChoiceDialogFocusTest {
    @Test
    fun startsOnTheSelectedOption() {
        assertThat(initialChoiceFocusIndex(listOf("auto", "2160", "1080", "720"), "1080")).isEqualTo(2)
    }

    @Test
    fun startsOnTheFirstOptionWhenNothingIsSelected() {
        assertThat(initialChoiceFocusIndex(listOf("a", "b"), null)).isEqualTo(0)
    }

    @Test
    fun startsOnTheFirstOptionWhenTheSelectionIsFilteredOut() {
        assertThat(initialChoiceFocusIndex(listOf("a", "b"), "z")).isEqualTo(0)
    }

    @Test
    fun hasNoRowInAnEmptyList() {
        assertThat(initialChoiceFocusIndex(emptyList<String>(), "a")).isEqualTo(-1)
    }
}
