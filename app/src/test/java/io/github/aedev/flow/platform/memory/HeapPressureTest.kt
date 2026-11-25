package io.github.aedev.flow.platform.memory

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HeapPressureTest {
    private val mb = 1024L * 1024L

    @Test
    fun `a heap under 60 percent full writes nothing`() {
        assertThat(HeapPressure.isHigh(usedBytes = 76 * mb, maxBytes = 128 * mb)).isFalse()
    }

    @Test
    fun `a heap at or over 60 percent full is recorded`() {
        assertThat(HeapPressure.isHigh(usedBytes = 77 * mb, maxBytes = 128 * mb)).isTrue()
        assertThat(HeapPressure.isHigh(usedBytes = 125 * mb, maxBytes = 128 * mb)).isTrue()
    }

    @Test
    fun `an unknown max heap is never high`() {
        assertThat(HeapPressure.isHigh(usedBytes = 10 * mb, maxBytes = 0)).isFalse()
    }

    @Test
    fun `the breadcrumb names the heap and every holding`() {
        val text = HeapPressure.describe(100 * mb, 128 * mb, sortedMapOf("home" to 107, "watch" to 3))

        assertThat(text).isEqualTo("heap 100/128 MB, home 107, watch 3")
    }
}
