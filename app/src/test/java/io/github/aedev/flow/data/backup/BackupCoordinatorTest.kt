package io.github.aedev.flow.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.BackupRepository
import io.github.aedev.flow.data.profile.ActiveProfile
import io.github.aedev.flow.data.profile.MasterRestored
import io.github.aedev.flow.data.profile.ProfileSlices
import io.github.aedev.flow.data.profile.RunningProfile
import io.github.aedev.flow.data.recommendation.music.FavouriteArtistsStore
import io.github.aedev.flow.notification.NotificationHelper
import io.github.aedev.flow.sync2.service.LinkedReset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class BackupCoordinatorTest {
    private val context: Context = mockk(relaxed = true)
    private val repository: BackupRepository = mockk(relaxed = true)
    private val first: Uri = mockk()
    private val second: Uri = mockk()
    private val favourites: FavouriteArtistsStore = mockk(relaxed = true)
    private val linkedReset: LinkedReset = mockk(relaxed = true)
    private val slices: ProfileSlices = mockk(relaxed = true)

    private fun coordinator() =
        BackupCoordinator(
            context,
            repository,
            mockk(relaxed = true),
            mockk(relaxed = true),
            linkedReset,
            slices,
            mockk(relaxed = true),
        )

    @Before
    fun setUp() {
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.deleteDocument(any(), any()) } returns true
        mockkObject(NotificationHelper)
        every { NotificationHelper.cancelImportNotification(any()) } returns Unit
        every { NotificationHelper.hasNotificationPermission(any()) } returns false
        mockkObject(ActiveProfile)
        every { ActiveProfile.get(any()) } returns RunningProfile(id = "d", isDefault = true)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `an export started while another runs deletes the file the save dialog created`() {
        val gate = CompletableDeferred<Result<Unit>>()
        coEvery { repository.exportData(first) } coAnswers { gate.await() }
        val coordinator = coordinator()

        assertThat(coordinator.exportAppData(first)).isTrue()
        assertThat(coordinator.exportNewPipeSubscriptions(second)).isFalse()

        verify(timeout = 2_000) { DocumentsContract.deleteDocument(any(), second) }
        verify(exactly = 0) { DocumentsContract.deleteDocument(any(), first) }
        gate.cancel()
    }

    @Test
    fun `a failed export deletes its empty file and reports the failure`() =
        runBlocking {
            coEvery { repository.exportSubscriptionsAsNewPipe(first) } returns Result.failure(IllegalStateException())
            val coordinator = coordinator()

            assertThat(coordinator.exportNewPipeSubscriptions(first)).isTrue()

            val outcome = withTimeout(2_000) { coordinator.operation.first { it !is BackupOperation.Running } }
            assertThat(outcome).isInstanceOf(BackupOperation.Failed::class.java)
            verify { DocumentsContract.deleteDocument(any(), first) }
        }

    @Test
    fun `a successful export keeps its file`() =
        runBlocking {
            coEvery { repository.exportSubscriptionsAsNewPipe(first) } returns Result.success(NewPipeSubscriptionExport("{}", skipped = 0))
            val coordinator = coordinator()

            coordinator.exportNewPipeSubscriptions(first)

            val outcome = withTimeout(2_000) { coordinator.operation.first { it !is BackupOperation.Running } }
            assertThat(outcome).isInstanceOf(BackupOperation.Succeeded::class.java)
            verify(exactly = 0) { DocumentsContract.deleteDocument(any(), any()) }
        }

    @Test
    fun `a profile's backup carries the picked artists and its restore puts them back`() =
        runBlocking {
            val picks = "[]".toByteArray()
            coEvery { favourites.export() } returns picks
            coEvery { repository.exportMasterBackup(first, any(), any(), picks) } returns Result.success(Unit)
            val restore = slot<suspend (ByteArray) -> Unit>()
            coEvery { repository.importMasterBackup(second, any(), any(), capture(restore)) } coAnswers {
                restore.captured(picks)
                Result.success(Unit)
            }
            val realSlices = ProfileSlices(context, repository, mockk(relaxed = true), mockk(relaxed = true), favourites, linkedReset)

            realSlices.export(first)
            realSlices.import(second)

            coVerify { repository.exportMasterBackup(first, any(), any(), picks) }
            coVerify { favourites.restore(picks) }
        }

    @Test
    fun `a backup from before profiles asks which profile it goes into, and only a yes restores it`() =
        runBlocking {
            coEvery { slices.oldMasterTarget(second) } returns "Study"
            coEvery { slices.restoreMaster(second) } returns Result.success(MasterRestored(restoredNow = true, waiting = emptyList()))
            val coordinator = coordinator()

            coordinator.importMaster(second)
            withTimeout(2_000) { coordinator.operation.first { it is BackupOperation.Confirming } }
            coordinator.cancelConfirmation()
            coVerify(exactly = 0) { slices.restoreMaster(any()) }

            coordinator.importMaster(second)
            withTimeout(2_000) { coordinator.operation.first { it is BackupOperation.Confirming } }
            coordinator.confirm()
            withTimeout(2_000) { coordinator.operation.first { it is BackupOperation.Succeeded } }
            coVerify(exactly = 1) { slices.restoreMaster(second) }
        }

    @Test
    fun `the outcome is published after the last notification call`() =
        runBlocking {
            coEvery { slices.oldMasterTarget(second) } returns null
            coEvery { slices.restoreMaster(second) } returns Result.success(MasterRestored(restoredNow = true, waiting = emptyList()))
            val coordinator = coordinator()
            val seen = CopyOnWriteArrayList<BackupOperation>()
            every { NotificationHelper.hasNotificationPermission(any()) } answers {
                seen += coordinator.operation.value
                false
            }

            coordinator.importMaster(second)
            withTimeout(2_000) { coordinator.operation.first { it is BackupOperation.Succeeded } }

            assertThat(seen).isNotEmpty()
            assertThat(seen.filterIsInstance<BackupOperation.Succeeded>()).isEmpty()
        }

    @Test
    fun `an import starts both syncs over, so missing records aren't sent as deletions`() =
        runBlocking {
            coEvery { slices.oldMasterTarget(second) } returns null
            coEvery { slices.restoreMaster(second) } returns Result.success(MasterRestored(restoredNow = true, waiting = emptyList()))
            val coordinator = coordinator()

            coordinator.importMaster(second)
            withTimeout(2_000) { coordinator.operation.first { it is BackupOperation.Succeeded } }
            coVerify(exactly = 1) { linkedReset.dataReplaced() }
        }
}
