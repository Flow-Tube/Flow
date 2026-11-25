package io.github.aedev.flow.platform.memory

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceTierTest {
    @Test
    fun `a 128 MB TV heap is small`() {
        assertThat(DeviceTier.isSmall(memoryClassMb = 128, isLowRamDevice = false)).isTrue()
    }

    @Test
    fun `the 192 MB and 256 MB heaps most devices report are not`() {
        assertThat(DeviceTier.isSmall(memoryClassMb = 192, isLowRamDevice = false)).isFalse()
        assertThat(DeviceTier.isSmall(memoryClassMb = 256, isLowRamDevice = false)).isFalse()
    }

    @Test
    fun `a device Android flags as low-RAM is small whatever its heap`() {
        assertThat(DeviceTier.isSmall(memoryClassMb = 512, isLowRamDevice = true)).isTrue()
    }
}
