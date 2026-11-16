package io.github.aedev.flow.ui.screens.update

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.update.InstallPhase
import io.github.aedev.flow.data.update.UpdateDownload
import io.github.aedev.flow.data.update.UpdateFailure
import org.junit.Test

class UpdateStageTest {
    @Test
    fun `nothing downloaded offers the download`() {
        assertThat(updateStage(UpdateDownload.Idle, canInstall = true, install = InstallPhase.Idle))
            .isEqualTo(UpdateStage.Available)
    }

    @Test
    fun `a finished download asks for the install permission before offering install`() {
        assertThat(updateStage(UpdateDownload.Ready, canInstall = false, install = InstallPhase.Idle))
            .isEqualTo(UpdateStage.NeedsPermission)
        assertThat(updateStage(UpdateDownload.Ready, canInstall = true, install = InstallPhase.Idle))
            .isEqualTo(UpdateStage.Ready)
    }

    @Test
    fun `a rejected install is offered again as a failure`() {
        assertThat(updateStage(UpdateDownload.Ready, canInstall = true, install = InstallPhase.Failed))
            .isEqualTo(UpdateStage.Failed(UpdateFailure.INSTALL))
    }

    @Test
    fun `the system confirmation keeps the page locked`() {
        assertThat(updateStage(UpdateDownload.Ready, canInstall = true, install = InstallPhase.Confirming))
            .isEqualTo(UpdateStage.Installing(null))
    }

    @Test
    fun `an install in progress shows the session progress`() {
        assertThat(updateStage(UpdateDownload.Ready, canInstall = true, install = InstallPhase.Installing(0.9f)))
            .isEqualTo(UpdateStage.Installing(0.9f))
    }

    @Test
    fun `an install in progress stays locked even if the permission check reads false`() {
        assertThat(updateStage(UpdateDownload.Ready, canInstall = false, install = InstallPhase.Installing(null)))
            .isEqualTo(UpdateStage.Installing(null))
    }

    @Test
    fun `a failed download keeps its reason`() {
        assertThat(updateStage(UpdateDownload.Failed(UpdateFailure.CHECKSUM), canInstall = true, install = InstallPhase.Idle))
            .isEqualTo(UpdateStage.Failed(UpdateFailure.CHECKSUM))
    }
}
