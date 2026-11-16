package io.github.aedev.flow.sync2

import android.content.Context
import io.github.aedev.flow.data.local.dao.SyncLogDao
import io.github.aedev.flow.data.profile.ProfileSlices
import io.github.aedev.flow.sync.apply.PeerInfo
import io.github.aedev.flow.sync.apply.ReceivedCollection
import io.github.aedev.flow.sync.apply.SyncApplier
import io.github.aedev.flow.sync.apply.SyncApplyException
import io.github.aedev.flow.sync.identity.DeviceIdentity
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** FLOW-SYNC/1 skips a payload it applied before; linked devices must not, or a re-sent identical payload is lost. */
class ApplyOnceTest {
    private val syncLog = mockk<SyncLogDao>(relaxed = true) { coEvery { isAlreadyApplied(any(), any(), any()) } returns true }

    // The pre-merge backup fails, so a call that gets past the guard ends there, without touching any data.
    private val slices = mockk<ProfileSlices> { coEvery { export(any<File>()) } returns Result.failure(IllegalStateException()) }

    private val applier =
        SyncApplier(
            context = mockk<Context> { every { cacheDir } returns createTempDirectory("cache").toFile() },
            db = mockk(),
            dataAccess = mockk(),
            tracker = mockk(),
            brains = mockk(),
            syncLogDao = syncLog,
            syncPeerDao = mockk(relaxed = true),
            deviceIdentity =
                mockk<DeviceIdentity> {
                    coEvery { hlcNode() } returns "node0000"
                    coEvery { deviceId() } returns "device"
                },
            slices = slices,
        )

    private val received = mapOf(SyncCollection.LIKES to ReceivedCollection(listOf("""{"kind":"video","id":"a"}"""), "hash"))
    private val peer = PeerInfo("did", "Phone", "android")

    @Test
    fun flow_sync_1_skips_a_payload_it_already_applied() =
        runTest {
            val stats = applier.applyPayload(peer, received, "0:0:node0000")
            assertEquals(1, stats.getValue(SyncCollection.LIKES).skipped)
            coVerify(exactly = 0) { slices.export(any<File>()) }
        }

    @Test
    fun linked_devices_apply_it_again() =
        runTest {
            val error = runCatching { applier.applyPayload(peer, received, "0:0:node0000", onceEach = false) }.exceptionOrNull()
            assertTrue("$error", error is SyncApplyException)
            coVerify(exactly = 0) { syncLog.isAlreadyApplied(any(), any(), any()) }
            coVerify(exactly = 1) { slices.export(any<File>()) }
        }

    // The Room transaction undoes a failed merge of Room collections, so no full-profile backup is taken.
    @Test
    fun a_merge_inside_the_room_transaction_takes_no_backup() =
        runTest {
            val history = mapOf(SyncCollection.WATCH_HISTORY to ReceivedCollection(listOf("""{"videoId":"a"}"""), "hash"))
            val error = runCatching { applier.applyPayload(peer, history, "0:0:node0000", onceEach = false) }.exceptionOrNull()
            assertTrue("$error", error is SyncApplyException)
            coVerify(exactly = 0) { slices.export(any<File>()) }
            coVerify(exactly = 0) { slices.import(any()) }
        }
}
