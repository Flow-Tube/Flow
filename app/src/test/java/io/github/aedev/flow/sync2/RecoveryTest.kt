package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.recoveryKeys
import io.github.aedev.flow.sync2.crypto.recoverySecret
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.addEntries
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.restore
import io.github.aedev.flow.sync2.roster.Role
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Restoring with the recovery key: a new device joins alone, the used key is replaced at once and never works again. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RecoveryTest {
    @Test
    fun a_restored_device_joins_and_the_recovery_key_is_replaced() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val device = newMember()
            val result = restore(g.store, g.store, g.recovery, device, RESTORED)
            val state = g.store.head()
            assertEquals(2, state.epoch)
            val next = recoveryKeys(checkNotNull(recoverySecret(result.words)))
            assertEquals(Role.RECOVERY, state.members.getValue(SyncBytes.toHex(next.did)).role)
            assertFalse(SyncBytes.toHex(g.recovery.did) in state.members)
            for (member in listOf(device, g.a, g.b)) {
                assertEquals(2, Session.open(g.deviceDb(), g.store, member).state.epoch)
            }
        }

    @Test
    fun a_replaced_recovery_key_cannot_restore_again() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            restore(g.store, g.store, g.recovery, newMember(), RESTORED)
            val again = runCatching { restore(g.store, g.store, g.recovery, newMember(), RESTORED) }.exceptionOrNull()
            assertTrue("$again", again is LinkedException.RecoveryUsed)
        }

    @Test
    fun a_restored_device_sees_the_link_other_devices_sync() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            Session
                .open(g.deviceDb(), g.store, g.a)
                .push(
                    mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"aaaaaaaaaaa"}"""), 1, "")),
                    LINK,
                )
            val (device, db) = restoredDevice(g)
            val session = Session.open(db, g.store, device)
            assertEquals(setOf(LINK), session.namedLinks())
            assertTrue(SyncBytes.toHex(g.a.did) in session.pull(setOf(LINK)).batches)
        }

    @Test
    fun a_restored_device_sees_no_link_when_nothing_was_synced() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (device, db) = restoredDevice(g)
            assertEquals(emptySet<String>(), Session.open(db, g.store, device).namedLinks())
        }
}
