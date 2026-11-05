package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.recoveryKeys
import io.github.aedev.flow.sync2.crypto.recoverySecret
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.addEntries
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.newEpoch
import io.github.aedev.flow.sync2.linking.replaceRecovery
import io.github.aedev.flow.sync2.linking.restore
import io.github.aedev.flow.sync2.roster.Draft
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.applyEntry
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.memberSubject
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

    @Test
    fun a_member_replaces_the_recovery_words_and_the_old_ones_get_no_new_key() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val words = replaceRecovery(Session.open(g.deviceDb(), g.store, g.a), g.a)
            val state = g.store.head()
            assertEquals(1, state.epoch)
            val next = SyncBytes.toHex(recoveryKeys(checkNotNull(recoverySecret(words))).did)
            assertEquals(Role.RECOVERY, state.members.getValue(next).role)
            assertFalse(SyncBytes.toHex(g.recovery.did) in state.members)
            val wrapped =
                g.store.keys.keys
                    .filter { it.epoch == 1 }
                    .map { it.name }
                    .toSet()
            assertEquals(setOf(SyncBytes.toHex(g.a.did), SyncBytes.toHex(g.b.did), next, "chain"), wrapped)
            val other = Session.open(g.deviceDb(), g.store, g.b)
            assertEquals(1, other.state.epoch)
            assertEquals(32, other.currentKey().size)
        }

    @Test
    fun replaced_words_cannot_restore() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            replaceRecovery(Session.open(g.deviceDb(), g.store, g.a), g.a)
            val refused = runCatching { restore(g.store, g.store, g.recovery, newMember(), RESTORED) }.exceptionOrNull()
            assertTrue("$refused", refused is LinkedException.RecoveryUsed)

            val state = g.store.head()
            val device = newMember()
            val kems = state.members.mapValues { (_, member) -> member.kem } + (SyncBytes.toHex(device.did) to device.kemPublic)
            val forged =
                Draft(
                    op = Op.RESTORE,
                    signer = SyncBytes.toHex(g.recovery.did),
                    add = listOf(memberSubject(device.sigPublic, device.kemPublic, Role.DEVICE)),
                    epoch = newEpoch(g.group, state.epoch + 1, kems, SyncCrypto.randomBytes(32)).field,
                )
            val entry = buildEntry(g.group, state, forged, listOf(g.recovery, device))
            assertTrue(runCatching { applyEntry(state, entry) }.exceptionOrNull() is ProtocolException)
        }

    @Test
    fun a_replacement_that_loses_the_race_appends_nothing() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val late = Session.open(g.deviceDb(), g.store, g.a)
            replaceRecovery(Session.open(g.deviceDb(), g.store, g.b), g.b)
            val before = g.store.roster.size
            assertTrue(runCatching { replaceRecovery(late, g.a) }.isFailure)
            assertEquals(before, g.store.roster.size)
        }
}
