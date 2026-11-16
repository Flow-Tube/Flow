package io.github.aedev.flow.sync

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.sync.transport.SyncForegroundGate
import io.github.aedev.flow.sync.transport.SyncForegroundGate.Stop
import org.junit.Test

class SyncForegroundGateTest {
    @Test
    fun `a stop before the service starts waits for the promotion`() {
        val gate = SyncForegroundGate()
        val token = gate.requestStart()

        assertThat(gate.requestStop(token)).isEqualTo(Stop.AFTER_START)
        assertThat(gate.promoted()).isTrue()
    }

    @Test
    fun `a stop after the promotion stops at once`() {
        val gate = SyncForegroundGate()
        val token = gate.requestStart()

        assertThat(gate.promoted()).isFalse()
        assertThat(gate.requestStop(token)).isEqualTo(Stop.NOW)
        assertThat(gate.requestStop(token)).isEqualTo(Stop.NOTHING)
    }

    @Test
    fun `an earlier session's stop leaves the current one running`() {
        val gate = SyncForegroundGate()
        val old = gate.requestStart()
        gate.promoted()
        gate.requestStart()

        assertThat(gate.requestStop(old)).isEqualTo(Stop.NOTHING)
        assertThat(gate.promoted()).isFalse()
    }

    @Test
    fun `a stop waits for every start still on its way`() {
        val gate = SyncForegroundGate()
        gate.requestStart()
        val token = gate.requestStart()

        assertThat(gate.requestStop(token)).isEqualTo(Stop.AFTER_START)
        assertThat(gate.promoted()).isFalse()
        assertThat(gate.promoted()).isTrue()
    }

    @Test
    fun `a start that threw holds no later stop`() {
        val gate = SyncForegroundGate()
        val token = gate.requestStart()
        gate.startFailed()

        assertThat(gate.requestStop(token)).isEqualTo(Stop.NOTHING)
    }

    @Test
    fun `a stop with nothing started does nothing`() {
        assertThat(SyncForegroundGate().requestStop(0L)).isEqualTo(Stop.NOTHING)
    }
}
