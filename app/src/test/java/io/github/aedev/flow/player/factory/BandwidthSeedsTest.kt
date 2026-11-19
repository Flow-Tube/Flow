package io.github.aedev.flow.player.factory

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BandwidthSeedsTest {
    private val wifi = 2
    private val lte = 6

    @Test
    fun `seeds survive a round trip`() {
        val seeds = mapOf(wifi to BandwidthSeed(42_000_000, 1_000), lte to BandwidthSeed(8_000_000, 2_000))

        assertThat(BandwidthSeeds.decode(BandwidthSeeds.encode(seeds))).isEqualTo(seeds)
    }

    @Test
    fun `broken entries are skipped, not fatal`() {
        assertThat(BandwidthSeeds.decode("")).isEmpty()
        assertThat(BandwidthSeeds.decode("2:abc:1;x;6:8000000:5")).containsExactly(lte, BandwidthSeed(8_000_000, 5))
    }

    @Test
    fun `only recent seeds start a session`() {
        val now = 10 * BandwidthSeeds.MAX_AGE_MS
        val seeds =
            mapOf(
                wifi to BandwidthSeed(42_000_000, now - 60_000),
                lte to BandwidthSeed(8_000_000, now - BandwidthSeeds.MAX_AGE_MS - 1),
            )

        assertThat(BandwidthSeeds.fresh(seeds, now)).containsExactly(wifi, 42_000_000L)
    }

    @Test
    fun `a seed from the future is ignored`() {
        val seeds = mapOf(wifi to BandwidthSeed(42_000_000, 5_000))

        assertThat(BandwidthSeeds.fresh(seeds, nowMs = 1_000)).isEmpty()
    }

    @Test
    fun `recording replaces only that network type and skips unusable estimates`() {
        val seeds = mapOf(wifi to BandwidthSeed(42_000_000, 1))

        assertThat(BandwidthSeeds.record(seeds, lte, 9_000_000, 7)).containsExactly(
            wifi,
            BandwidthSeed(42_000_000, 1),
            lte,
            BandwidthSeed(9_000_000, 7),
        )
        assertThat(BandwidthSeeds.record(seeds, wifi, 10, 7)).isEqualTo(seeds)
        assertThat(BandwidthSeeds.record(seeds, 0, 9_000_000, 7)).isEqualTo(seeds)
    }
}
