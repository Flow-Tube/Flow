package io.github.aedev.flow.data.video.downloader.work

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgressGateTest {
    private val gate = ProgressGate()
    private val mib = 1024L * 1024

    @Test
    fun `progress is written on each new percent, not on every tick`() {
        val total = 1_000 * mib

        assertThat(gate.passes(0, total)).isTrue()
        assertThat(gate.passes(5 * mib, total)).isFalse()
        assertThat(gate.passes(10 * mib, total)).isTrue()
        assertThat(gate.passes(12 * mib, total)).isFalse()
    }

    @Test
    fun `a huge file is written every 16 MiB even within one percent`() {
        val total = 10_000 * mib

        gate.passes(0, total)
        assertThat(gate.passes(15 * mib, total)).isFalse()
        assertThat(gate.passes(16 * mib, total)).isTrue()
    }

    @Test
    fun `the finished size is always written`() {
        gate.passes(0, 100)
        gate.passes(99, 100)

        assertThat(gate.passes(100, 100)).isTrue()
        assertThat(gate.passes(100, 100)).isFalse()
    }
}
