package io.github.aedev.flow.data.music.artwork

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArtworkLoopQualityTest {
    @Test
    fun `nothing saved or an unknown value reads as auto`() {
        assertThat(resolveArtworkLoopQuality(null)).isEqualTo(ArtworkLoopQuality.AUTO)
        assertThat(resolveArtworkLoopQuality("ULTRA")).isEqualTo(ArtworkLoopQuality.AUTO)
        assertThat(resolveArtworkLoopQuality("MEDIUM")).isEqualTo(ArtworkLoopQuality.MEDIUM)
    }

    @Test
    fun `only auto sizes the loop to its box`() {
        assertThat(ArtworkLoopQuality.entries.filter { it.followsViewport }).containsExactly(ArtworkLoopQuality.AUTO)
    }

    @Test
    fun `no choice reaches the 2160 loops and auto stops where high does`() {
        assertThat(ArtworkLoopQuality.AUTO.maxWidth).isEqualTo(ArtworkLoopQuality.HIGH.maxWidth)
        assertThat(ArtworkLoopQuality.entries.map { it.maxWidth }.max()).isAtMost(1080)
        assertThat(listOf(ArtworkLoopQuality.LOW, ArtworkLoopQuality.MEDIUM, ArtworkLoopQuality.HIGH).map { it.maxWidth })
            .isInStrictOrder()
    }
}
