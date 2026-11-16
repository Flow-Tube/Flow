package io.github.aedev.flow.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InstallPhaseTest {
    @Test
    fun `starting an install locks it straight away`() {
        assertThat(InstallPhase.Idle.on(InstallEvent.Started)).isEqualTo(InstallPhase.Installing(null))
        assertThat(InstallPhase.Failed.on(InstallEvent.Started)).isEqualTo(InstallPhase.Installing(null))
    }

    @Test
    fun `the system confirmation is its own phase`() {
        assertThat(InstallPhase.Installing(null).on(InstallEvent.NeedsConfirmation)).isEqualTo(InstallPhase.Confirming)
    }

    @Test
    fun `resuming while the session still exists keeps the install locked`() {
        assertThat(InstallPhase.Installing(0.8f).on(InstallEvent.Resumed(sessionExists = true))).isEqualTo(InstallPhase.Installing(0.8f))
        assertThat(InstallPhase.Confirming.on(InstallEvent.Resumed(sessionExists = true))).isEqualTo(InstallPhase.Confirming)
    }

    @Test
    fun `confirming moves on to installing once the session reports progress`() {
        assertThat(InstallPhase.Confirming.on(InstallEvent.Progress(0.8f))).isEqualTo(InstallPhase.Installing(0.8f))
    }

    @Test
    fun `progress passes through`() {
        assertThat(InstallPhase.Installing(0.8f).on(InstallEvent.Progress(0.95f))).isEqualTo(InstallPhase.Installing(0.95f))
    }

    @Test
    fun `progress does not restart a finished install`() {
        assertThat(InstallPhase.Idle.on(InstallEvent.Progress(0.5f))).isEqualTo(InstallPhase.Idle)
        assertThat(InstallPhase.Failed.on(InstallEvent.Progress(0.5f))).isEqualTo(InstallPhase.Failed)
    }

    @Test
    fun `cancelling the system confirmation offers install again`() {
        assertThat(InstallPhase.Confirming.on(InstallEvent.Aborted)).isEqualTo(InstallPhase.Idle)
    }

    @Test
    fun `a rejected install fails`() {
        assertThat(InstallPhase.Installing(0.9f).on(InstallEvent.Rejected)).isEqualTo(InstallPhase.Failed)
    }

    @Test
    fun `a session that vanished without a result unlocks the page`() {
        assertThat(InstallPhase.Installing(0.9f).on(InstallEvent.Resumed(sessionExists = false))).isEqualTo(InstallPhase.Idle)
        assertThat(InstallPhase.Confirming.on(InstallEvent.Resumed(sessionExists = false))).isEqualTo(InstallPhase.Idle)
    }

    @Test
    fun `a vanished session keeps a failure that already arrived`() {
        assertThat(InstallPhase.Failed.on(InstallEvent.Resumed(sessionExists = false))).isEqualTo(InstallPhase.Failed)
    }
}
