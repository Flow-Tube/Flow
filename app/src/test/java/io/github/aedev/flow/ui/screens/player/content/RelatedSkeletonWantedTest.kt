package io.github.aedev.flow.ui.screens.player.content

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RelatedSkeletonWantedTest {
    private fun wanted(
        gradientEnabled: Boolean = true,
        playerExpanded: Boolean = true,
        relatedEmpty: Boolean = true,
        hasError: Boolean = false,
    ) = relatedSkeletonWanted(gradientEnabled, playerExpanded, relatedEmpty, hasError)

    @Test
    fun `an open player with no related videos yet wants placeholders`() {
        assertThat(wanted()).isTrue()
    }

    @Test
    fun `the page stays as it was with the setting off`() {
        assertThat(wanted(gradientEnabled = false)).isFalse()
    }

    @Test
    fun `nothing pulses behind the mini player`() {
        assertThat(wanted(playerExpanded = false)).isFalse()
    }

    @Test
    fun `real related videos and errors replace the placeholders`() {
        assertThat(wanted(relatedEmpty = false)).isFalse()
        assertThat(wanted(hasError = true)).isFalse()
    }
}
