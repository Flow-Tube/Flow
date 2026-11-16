package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.data.observeEpoch
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.leaveGroup
import io.github.aedev.flow.sync2.linking.removeDevices
import io.github.aedev.flow.sync2.linking.rotateEpoch
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.memberSubject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Leaving a group (spec/protocol.md, Leaving): the device signs itself out, and the next writer rotates without it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LeavingTest {
    private fun LinkedGroup.hex(member: MemberSecrets) = SyncBytes.toHex(member.did)

    /** Adds a third device, C, as A. */
    private suspend fun LinkedGroup.addThird(): MemberSecrets {
        val c = newMember()
        val state = store.head()
        val add =
            buildEntry(
                group,
                state,
                draft(Op.ADD, a).copy(add = listOf(memberSubject(c.sigPublic, c.kemPublic, Role.DEVICE))),
                listOf(a, c),
            )
        store.appendRoster(state.head, add, wraps(group, 0, epochKey, listOf(c)), null)
        return c
    }

    @Test
    fun a_device_that_leaves_is_out_and_the_next_writer_rotates_without_it() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (dbA, dbB) = g.deviceDb() to g.deviceDb()
            leaveGroup(Session.open(dbB, g.store, g.b), g.b)
            assertEquals(
                Op.LEAVE,
                g.store.roster
                    .last()
                    .op,
            )
            assertTrue(runCatching { Session.open(dbB, g.store, g.b) }.exceptionOrNull() is LinkedException.Removed)

            val a = Session.open(dbA, g.store, g.a)
            assertTrue("a rotate is due", a.state.rotate)
            assertTrue(rotateEpoch(a, g.a))

            val after = Session.open(dbA, g.store, g.a).state
            assertEquals(1, after.epoch)
            assertEquals("LAN sync follows the epoch it advertises under", 1, dbA.groups().observeEpoch().first())
            assertFalse(after.rotate)
            assertEquals(setOf(g.hex(g.a), g.hex(g.recovery)), after.members.keys)
            assertTrue("the recovery member gets the new key", g.store.holdsKey(1, g.hex(g.recovery)))
            assertFalse("the device that left never does", g.store.holdsKey(1, g.hex(g.b)))
            assertTrue(g.store.holdsKey(1, "chain"))
        }

    @Test
    fun a_rotate_that_lost_the_race_is_not_repeated() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val c = g.addThird()
            leaveGroup(Session.open(g.deviceDb(), g.store, g.b), g.b)
            val (dbA, dbC) = g.deviceDb() to g.deviceDb()
            val stale = Session.open(dbC, g.store, c)
            assertTrue(rotateEpoch(Session.open(dbA, g.store, g.a), g.a))

            assertFalse("A's rotate got there first", rotateEpoch(stale, c))
            val adopted = Session.open(dbC, g.store, c).state
            assertFalse(adopted.rotate)
            assertEquals(1, adopted.epoch)
        }

    @Test
    fun the_last_device_can_t_leave_and_a_remove_needs_no_rotate() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val dbA = g.deviceDb()
            removeDevices(Session.open(dbA, g.store, g.a), g.a, listOf(g.hex(g.b)))
            assertFalse(Session.open(dbA, g.store, g.a).state.rotate)

            val error = runCatching { leaveGroup(Session.open(dbA, g.store, g.a), g.a) }.exceptionOrNull()
            assertEquals("roster-op", (error as ProtocolException).error.code)
        }
}
