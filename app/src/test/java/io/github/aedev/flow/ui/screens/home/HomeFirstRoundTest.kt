package io.github.aedev.flow.ui.screens.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeFirstRoundTest {
    @Test
    fun `a normal heap keeps three searches and four related seeds side by side`() {
        assertThat(
            HomeFirstRound.forHeap(isSmallHeap = false),
        ).isEqualTo(HomeFirstRound(queryCount = 3, relatedSeeds = 4, lanesTogether = true))
    }

    @Test
    fun `a small heap parses at most two responses at a time`() {
        val round = HomeFirstRound.forHeap(isSmallHeap = true)

        assertThat(round.queryCount).isEqualTo(2)
        assertThat(round.relatedSeeds).isEqualTo(2)
        assertThat(round.lanesTogether).isFalse()
    }
}
