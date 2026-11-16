package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.deviceNames
import io.github.aedev.flow.sync2.linking.removeDevices
import io.github.aedev.flow.sync2.linking.renameDevice
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.memberSubject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MembershipTest {
    private fun meta(name: String) = DeviceMeta("app", name, "android")

    @Test
    fun a_removed_device_is_shut_out_and_the_others_continue() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val dbA = g.deviceDb()
            val dbB = g.deviceDb()
            removeDevices(Session.open(dbA, g.store, g.a), g.a, listOf(SyncBytes.toHex(g.b.did)))
            val state = g.store.head()
            assertEquals(1, state.epoch)
            assertFalse(SyncBytes.toHex(g.b.did) in state.members)
            assertTrue(SyncBytes.toHex(g.recovery.did) in state.members)
            assertTrue(runCatching { Session.open(dbB, g.store, g.b) }.exceptionOrNull() is LinkedException.Removed)
            assertEquals(1, Session.open(dbA, g.store, g.a).state.epoch)
        }

    @Test
    fun a_lost_race_is_reported_not_overwritten() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val stale = Session.open(g.deviceDb(), g.store, g.b)
            removeDevices(Session.open(g.deviceDb(), g.store, g.a), g.a, listOf(SyncBytes.toHex(g.b.did)))
            val error = runCatching { renameDevice(stale, g.b, SyncBytes.toHex(g.b.did), meta("Mine")) }.exceptionOrNull()
            assertEquals("roster-chain", (error as ProtocolException).error.code)
        }

    @Test
    fun names_come_from_the_latest_entry_and_survive_a_new_epoch() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val db = g.deviceDb()
            val (a, b) = SyncBytes.toHex(g.a.did) to SyncBytes.toHex(g.b.did)
            renameDevice(Session.open(db, g.store, g.a), g.a, a, meta("Old name"))
            renameDevice(Session.open(db, g.store, g.a), g.a, a, meta("Laptop"))
            renameDevice(Session.open(db, g.store, g.a), g.a, b, meta("Studio"))

            val c = newMember()
            val state = g.store.head()
            val add =
                buildEntry(
                    g.group,
                    state,
                    draft(Op.ADD, g.a).copy(add = listOf(memberSubject(c.sigPublic, c.kemPublic, Role.DEVICE))),
                    listOf(g.a, c),
                )
            g.store.appendRoster(state.head, add, wraps(g.group, 0, g.epochKey, listOf(c)), null)
            removeDevices(Session.open(db, g.store, g.a), g.a, listOf(SyncBytes.toHex(c.did)))

            val names = deviceNames(Session.open(db, g.store, g.a))
            assertEquals("Laptop", names.getValue(a).name)
            assertEquals("Studio", names.getValue(b).name)
        }

    @Test
    fun a_signed_name_that_can_not_be_read_is_left_out() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val db = g.deviceDb()
            val a = SyncBytes.toHex(g.a.did)
            renameDevice(Session.open(db, g.store, g.a), g.a, a, meta("Laptop"))
            for (sealed in listOf("not base64url!", SyncBytes.b64urlEncode(ByteArray(100)))) {
                val state = g.store.head()
                val rename = buildEntry(g.group, state, draft(Op.RENAME, g.a).copy(meta = mapOf(a to sealed)), listOf(g.a))
                g.store.appendRoster(state.head, rename, emptyMap(), null)
            }

            assertEquals("Laptop", deviceNames(Session.open(db, g.store, g.a)).getValue(a).name)
        }
}
