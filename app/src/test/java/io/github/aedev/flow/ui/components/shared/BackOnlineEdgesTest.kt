package io.github.aedev.flow.ui.components.shared

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BackOnlineEdgesTest {
    @Test
    fun `each return to online fires once`() =
        runTest {
            assertThat(flowOf(true, false, false, true, true, false, true).backOnlineEdges().toList()).hasSize(2)
        }

    @Test
    fun `starting offline then connecting fires once`() =
        runTest {
            assertThat(flowOf(false, true).backOnlineEdges().toList()).hasSize(1)
        }

    @Test
    fun `being online from the start fires nothing`() =
        runTest {
            assertThat(flowOf(true, true).backOnlineEdges().toList()).isEmpty()
        }
}
