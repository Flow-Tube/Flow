package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.crypto.recoverySecret
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.engine.recoveryChange
import io.github.aedev.flow.sync2.engine.writeSnapshot
import io.github.aedev.flow.sync2.linking.leaveGroup
import io.github.aedev.flow.sync2.linking.removeDevices
import io.github.aedev.flow.sync2.linking.replaceRecovery
import io.github.aedev.flow.sync2.roster.devicesAddedBy
import io.github.aedev.flow.sync2.roster.recoveryMember
import io.github.aedev.flow.sync2.store.ObjectType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A recovery key doesn't outlast the device that set it (spec/protocol.md, Validation rule 8), and devices tell the user when it changes (spec/protocol.md, Recovery). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RecoveryKeyTest {
    private val link = "link"

    private fun history(vararg ids: String) =
        mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(ids.map { """{"title":"t","videoId":"$it"}""" }, ids.size, ""))

    private suspend fun bSetsTheKey(
        g: LinkedGroup,
        db: LinkedDatabase,
    ) {
        replaceRecovery(Session.open(db, g.store, g.b), g.b)
    }

    @Test
    fun removing_the_device_that_set_the_key_replaces_it_in_the_same_entry() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            bSetsTheKey(g, g.deviceDb())
            val planted = checkNotNull(recoveryMember(g.store.head().members)).key

            val words = removeDevices(Session.open(g.deviceDb(), g.store, g.a), g.a, listOf(SyncBytes.toHex(g.b.did)))

            assertNotNull(recoverySecret(checkNotNull(words)))
            val state = g.store.head()
            val current = checkNotNull(recoveryMember(state.members))
            assertNotEquals(planted, current.key)
            assertEquals(SyncBytes.toHex(g.a.did), current.value.installer)
            assertFalse(state.recoveryDue)
            assertFalse(
                planted in
                    checkNotNull(
                        g.store.roster
                            .last()
                            .epoch,
                    ).to,
            )
        }

    @Test
    fun removing_a_device_that_did_not_set_the_key_leaves_it() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val before = checkNotNull(recoveryMember(g.store.head().members)).key

            val words = removeDevices(Session.open(g.deviceDb(), g.store, g.a), g.a, listOf(SyncBytes.toHex(g.b.did)))

            assertNull(words)
            assertEquals(before, checkNotNull(recoveryMember(g.store.head().members)).key)
        }

    @Test
    fun nothing_is_written_after_the_device_that_set_the_key_left() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (dbA, dbB) = g.deviceDb() to g.deviceDb()
            bSetsTheKey(g, dbB)
            leaveGroup(Session.open(dbB, g.store, g.b), g.b)
            val objects = g.store.objects.size

            val a = Session.open(dbA, g.store, g.a)
            assertTrue(a.state.recoveryDue)
            assertTrue(checkNotNull(dbA.groups().group()).recoveryDue)
            assertEquals(0, a.push(history("aaaaaaaaaaa"), link))
            a.writeSnapshot(history("aaaaaaaaaaa"), link)
            assertEquals(objects, g.store.objects.size)
            assertTrue(runCatching { a.write(ObjectType.CHANGES, ByteArray(1)) }.exceptionOrNull() is LinkedException.RecoveryDue)

            replaceRecovery(a, g.a)
            val after = Session.open(dbA, g.store, g.a)
            assertFalse(after.state.recoveryDue)
            assertFalse(after.state.rotate)
            assertFalse(checkNotNull(dbA.groups().group()).recoveryDue)
            assertTrue(after.push(history("aaaaaaaaaaa"), link) > 0)
        }

    @Test
    fun another_device_changing_the_key_is_noticed_until_answered() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val dbA = g.deviceDb()
            Session.open(dbA, g.store, g.a)
            bSetsTheKey(g, g.deviceDb())

            Session.open(dbA, g.store, g.a)

            assertEquals(SyncBytes.toHex(g.b.did), checkNotNull(dbA.groups().group()).recoveryNoticeDevice)
            dbA.groups().setRecoveryNotice(null, null)
            Session.open(dbA, g.store, g.a)
            assertNull(checkNotNull(dbA.groups().group()).recoveryNoticeDevice)
        }

    @Test
    fun this_device_changing_the_key_shows_no_notice() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val dbB = g.deviceDb()
            Session.open(dbB, g.store, g.b)
            bSetsTheKey(g, dbB)

            Session.open(dbB, g.store, g.b)

            assertNull(checkNotNull(dbB.groups().group()).recoveryNoticeDevice)
        }

    @Test
    fun a_device_with_nothing_applied_is_only_catching_up() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            bSetsTheKey(g, g.deviceDb())
            assertNull(recoveryChange(SyncBytes.toHex(g.a.did), emptyList(), g.store.roster))
            assertEquals(SyncBytes.toHex(g.b.did), recoveryChange(SyncBytes.toHex(g.a.did), g.store.roster.take(1), g.store.roster))
        }

    @Test
    fun what_a_device_added_is_followed_through_the_devices_it_added() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (a, b) = SyncBytes.toHex(g.a.did) to SyncBytes.toHex(g.b.did)
            assertEquals(listOf(b), devicesAddedBy(g.store.roster, a))
            assertTrue(devicesAddedBy(g.store.roster, b).isEmpty())
        }
}
