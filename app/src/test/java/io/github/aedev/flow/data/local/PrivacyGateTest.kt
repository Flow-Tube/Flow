package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PrivacyGateTest {
    private fun gate(
        deepFlow: Boolean,
        historyPaused: Boolean = false,
        scrobbleDuringDeepFlow: Boolean = false,
    ) = PrivacyGate(
        deepFlowActive = { deepFlow },
        watchHistoryPaused = { historyPaused },
        scrobbleDuringDeepFlow = { scrobbleDuringDeepFlow },
    )

    @Test
    fun `scrobbling runs while Deep Flow is off`() =
        runTest {
            assertThat(gate(deepFlow = false).allowsScrobbling()).isTrue()
        }

    @Test
    fun `Deep Flow stops scrobbling by default`() =
        runTest {
            assertThat(gate(deepFlow = true).allowsScrobbling()).isFalse()
        }

    @Test
    fun `Deep Flow scrobbles when the user chose to`() =
        runTest {
            assertThat(gate(deepFlow = true, scrobbleDuringDeepFlow = true).allowsScrobbling()).isTrue()
        }

    @Test
    fun `searches are saved while Deep Flow is off`() =
        runTest {
            assertThat(gate(deepFlow = false).allowsSearchHistory()).isTrue()
        }

    @Test
    fun `Deep Flow keeps searches out of history`() =
        runTest {
            assertThat(gate(deepFlow = true).allowsSearchHistory()).isFalse()
        }

    @Test
    fun `paused watch history does not change what Deep Flow does to searches`() =
        runTest {
            assertThat(gate(deepFlow = false, historyPaused = true).allowsSearchHistory()).isTrue()
            assertThat(gate(deepFlow = true, historyPaused = true).allowsSearchHistory()).isFalse()
        }

    @Test
    fun `the gate reports both settings as they are`() =
        runTest {
            val gate = gate(deepFlow = true, historyPaused = true)

            assertThat(gate.isDeepFlowActive()).isTrue()
            assertThat(gate.isWatchHistoryPaused()).isTrue()
        }
}
