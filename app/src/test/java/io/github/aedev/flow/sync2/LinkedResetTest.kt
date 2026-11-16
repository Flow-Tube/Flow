package io.github.aedev.flow.sync2

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import io.github.aedev.flow.data.profile.ActiveProfile
import io.github.aedev.flow.data.profile.RunningProfile
import io.github.aedev.flow.sync.apply.CollectionLedger
import io.github.aedev.flow.sync.apply.LedgerEntry
import io.github.aedev.flow.sync.apply.SyncChangeLedgerStore
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.data.SentRecord
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.device.StoreCredentials
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.lan.LanSync
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.service.LinkedError
import io.github.aedev.flow.sync2.service.LinkedReset
import io.github.aedev.flow.sync2.service.LinkedSync
import io.github.aedev.flow.sync2.store.StoreException
import io.github.aedev.flow.sync2.store.StorePaths
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/** A restore or import while linked starts both syncs over instead of sending every missing record as a deletion. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LinkedResetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A store that can't be reached, as when restoring offline. */
    private val unreachable =
        dagger.Lazy {
            mockk<LinkedSync> {
                coEvery {
                    open(
                        any(),
                        any(),
                    )
                } throws StoreException.Network(IOException())
            }
        }

    /** These groups live on a store; a LAN farewell is never asked for. */
    private val noLan = dagger.Lazy<LanSync> { error("not a LAN group") }

    @Before
    fun workManager() {
        if (!WorkManager.isInitialized()) WorkManager.initialize(context, Configuration.Builder().build())
    }

    @Test
    fun data_replaced_in_another_profile_changes_nothing_default_syncs() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val db = g.deviceDb()
            mockkObject(ActiveProfile)
            try {
                every { ActiveProfile.get(any()) } returns RunningProfile(id = "b", isDefault = false)
                LinkedReset(
                    context,
                    db,
                    heldStore(db),
                    SyncChangeLedgerStore(context),
                    DeviceKeys(context),
                    StoreCredentials(context),
                    unreachable,
                    noLan,
                ).dataReplaced()
            } finally {
                unmockkObject(ActiveProfile)
            }
            assertTrue(db.groups().group() != null)
        }

    @Test
    fun replaced_data_asks_before_forgetting_a_group_it_can_t_leave_then_forgets_all_of_it() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val db = g.deviceDb()
            val local = heldStore(db)
            val path = StorePaths.log(SyncBytes.toHex(g.a.did), 1)
            local.addEntries(g.store.roster)
            local.putObject(path, byteArrayOf(1))
            db.sent().upsert(listOf(SentRecord(LINK, SyncCollection.WATCH_HISTORY, "aaaaaaaaaaa", "hash", 0)))
            val ledger = SyncChangeLedgerStore(context)
            ledger.update(SyncCollection.WATCH_HISTORY) { CollectionLedger(true, mapOf("aaaaaaaaaaa" to LedgerEntry(hash = 1, atMs = 1))) }

            val keys =
                mockk<DeviceKeys> {
                    coEvery { load() } returns g.a
                    coJustRun { delete() }
                }
            val reset = LinkedReset(context, db, local, ledger, keys, StoreCredentials(context), unreachable, noLan)
            reset.dataReplaced()

            assertEquals(CollectionLedger(), ledger.load(SyncCollection.WATCH_HISTORY))
            assertEquals("the user is asked, nothing is forgotten silently", LinkedError.UNREACHABLE, reset.leaveFailed.value)
            assertTrue(db.groups().group() != null)

            reset.forget()
            assertNull(reset.leaveFailed.value)
            assertNull(db.groups().group())
            assertTrue(db.sent().forCollection(LINK, SyncCollection.WATCH_HISTORY).isEmpty())
            assertTrue(local.entries().isEmpty())
            assertNull(local.getObject(path))
            coVerify { keys.delete() }
        }

    @Test
    fun replaced_data_tells_the_other_devices_this_one_left() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val db = g.deviceDb()
            val keys =
                mockk<DeviceKeys> {
                    coEvery { load() } returns g.a
                    coJustRun { delete() }
                }
            val sync = dagger.Lazy { mockk<LinkedSync> { coEvery { open(any(), any()) } coAnswers { Session.open(db, g.store, g.a) } } }

            LinkedReset(
                context,
                db,
                heldStore(db),
                SyncChangeLedgerStore(context),
                keys,
                StoreCredentials(context),
                sync,
                noLan,
            ).dataReplaced()

            assertEquals(
                "a leave carries no data, so nothing is sent as a deletion",
                Op.LEAVE,
                g.store.roster
                    .last()
                    .op,
            )
            assertTrue(g.store.objects.isEmpty())
            assertEquals(null, db.groups().group())
            coVerify { keys.delete() }
        }
}
