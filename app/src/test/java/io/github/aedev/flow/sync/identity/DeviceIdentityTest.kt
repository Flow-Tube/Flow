package io.github.aedev.flow.sync.identity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceIdentityTest {
    @Test
    fun `an edit stamps now, or just after the newest stamp applied`() {
        assertThat(editStamp(nowMs = 20, seen = 9)).isEqualTo(20)
        assertThat(editStamp(nowMs = 5, seen = 9)).isEqualTo(10)
    }

    @Test
    fun `a stamp applied at the largest time keeps edits there instead of wrapping`() {
        assertThat(editStamp(nowMs = 5, seen = Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE)
    }
}
