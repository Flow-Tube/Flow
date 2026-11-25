package io.github.aedev.flow.platform.memory

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MemoryGaugesTest {
    @Test
    fun `a snapshot reads every registered gauge`() {
        val gauges = MemoryGauges()
        gauges.register("home") { 107 }
        gauges.register("feed") { 3 }

        assertThat(gauges.snapshot()).containsExactly("feed", 3, "home", 107).inOrder()
    }

    @Test
    fun `a gauge that throws reads as -1 instead of failing the breadcrumb`() {
        val gauges = MemoryGauges()
        gauges.register("home") { error("cleared") }

        assertThat(gauges.snapshot()).containsExactly("home", -1)
    }

    @Test
    fun `an older owner leaving keeps the newer owner's gauge`() {
        val gauges = MemoryGauges()
        val older = { 1 }
        val newer = { 2 }
        gauges.register("home", older)
        gauges.register("home", newer)

        gauges.unregister("home", older)

        assertThat(gauges.snapshot()).containsExactly("home", 2)
    }
}
