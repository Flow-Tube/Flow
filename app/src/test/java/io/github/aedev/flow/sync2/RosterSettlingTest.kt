package io.github.aedev.flow.sync2

import android.app.Application
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.epochCommitment
import io.github.aedev.flow.sync2.crypto.wrapEpochKey
import io.github.aedev.flow.sync2.data.LAN_STORE
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.RosterPinRow
import io.github.aedev.flow.sync2.data.toEntries
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.RosterState
import io.github.aedev.flow.sync2.roster.applyEntry
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.roster.epochDraft
import io.github.aedev.flow.sync2.roster.memberSubject
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.LocalStore
import io.github.aedev.flow.sync2.store.kcName
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settling the roster through a session (spec/protocol.md, Forks). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RosterSettlingTest {
    private fun hex(member: MemberSecrets) = SyncBytes.toHex(member.did)

    private class Holding(
        val c: MemberSecrets,
        val state: RosterState,
        val db: LinkedDatabase,
        val local: LocalStore,
    )

    /** A group where C joined last, and B holding all of it, on a LAN group unless [server]. */
    private suspend fun holding(
        g: LinkedGroup,
        server: Boolean = false,
    ): Holding {
        val c = newMember()
        val before = g.store.head()
        val add =
            buildEntry(
                g.group,
                before,
                draft(Op.ADD, g.a).copy(add = listOf(memberSubject(c.sigPublic, c.kemPublic, Role.DEVICE))),
                listOf(g.a, c),
            )
        g.store.roster += add
        val db = g.deviceDb()
        if (!server) db.groups().saveGroup(checkNotNull(db.groups().group()).copy(storeUrl = LAN_STORE))
        val local = heldStore(db)
        local.addEntries(g.store.roster)
        val did = hex(g.b)
        g.store.holdKeys(local, 0, did)
        Session.open(db, GroupStore(local, null), g.b)
        return Holding(c, applyEntry(before, add), db, local)
    }

    private fun removal(
        g: LinkedGroup,
        state: RosterState,
        signer: MemberSecrets,
        target: MemberSecrets,
        stays: List<MemberSecrets>,
        key: ByteArray,
    ): Entry =
        buildEntry(
            g.group,
            state,
            draft(
                Op.REMOVE,
                signer,
            ).copy(remove = listOf(hex(target)), epoch = epochDraft(key, g.group, state.epoch + 1, stays.map(::hex))),
            listOf(signer),
        )

    private suspend fun Holding.holdWrap(
        g: LinkedGroup,
        epoch: Int,
        key: ByteArray,
    ) = local.putKey(
        epoch,
        kcName(epochCommitment(key, g.group, epoch)),
        hex(g.b),
        wrapEpochKey(g.b.kemPublic, g.group, epoch, g.b.did, key),
    )

    @Test
    fun two_devices_that_removed_each_other_pause_sync_until_the_user_keeps_one() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val h = holding(g)
            val key = SyncCrypto.randomBytes(32)
            val keep = removal(g, h.state, g.a, h.c, listOf(g.a, g.b, g.recovery), key)
            val other = removal(g, h.state, h.c, g.a, listOf(g.b, h.c, g.recovery), SyncCrypto.randomBytes(32))
            h.holdWrap(g, 1, key)
            h.local.addEntries(listOf(keep, other))
            val before =
                h.db
                    .groups()
                    .roster()
                    .toEntries()

            val paused = runCatching { Session.open(h.db, GroupStore(h.local, null), g.b) }
            assertTrue(paused.exceptionOrNull() is LinkedException.Contested)
            assertEquals(
                "nothing was adopted",
                before,
                h.db
                    .groups()
                    .roster()
                    .toEntries(),
            )

            h.db.groups().addRosterPin(RosterPinRow(entryHash(keep)))
            val session = Session.open(h.db, GroupStore(h.local, null), g.b)
            assertEquals(entryHash(keep), session.state.head)
            assertFalse(hex(h.c) in session.state.members)
        }

    @Test
    fun a_removed_signer_is_ignored_with_no_question() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val h = holding(g)
            val key = SyncCrypto.randomBytes(32)
            val honest = removal(g, h.state, g.a, h.c, listOf(g.a, g.b, g.recovery), key)
            val joiner = newMember()
            val other =
                buildEntry(
                    g.group,
                    h.state,
                    draft(Op.ADD, h.c).copy(add = listOf(memberSubject(joiner.sigPublic, joiner.kemPublic, Role.DEVICE))),
                    listOf(h.c, joiner),
                )
            h.holdWrap(g, 1, key)
            h.local.addEntries(listOf(honest, other))
            assertEquals(entryHash(honest), Session.open(h.db, GroupStore(h.local, null), g.b).state.head)
        }

    @Test
    fun a_removal_that_extends_what_was_applied_says_removed() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val h = holding(g)
            h.local.addEntries(listOf(removal(g, h.state, g.a, g.b, listOf(g.a, h.c, g.recovery), SyncCrypto.randomBytes(32))))
            assertTrue(runCatching { Session.open(h.db, GroupStore(h.local, null), g.b) }.exceptionOrNull() is LinkedException.Removed)
        }

    @Test
    fun a_changed_roster_that_leaves_this_device_out_deletes_nothing_by_itself() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val h = holding(g)
            val rename = buildEntry(g.group, h.state, draft(Op.RENAME, g.b).copy(meta = mapOf(hex(g.b) to "x")), listOf(g.b))
            h.local.addEntries(listOf(rename))
            Session.open(h.db, GroupStore(h.local, null), g.b)
            h.local.addEntries(listOf(removal(g, h.state, g.a, g.b, listOf(g.a, h.c, g.recovery), SyncCrypto.randomBytes(32))))
            val opened = runCatching { Session.open(h.db, GroupStore(h.local, null), g.b) }
            assertTrue(opened.exceptionOrNull() is LinkedException.RemovalUnconfirmed)
            assertEquals(
                entryHash(rename),
                entryHash(
                    h.db
                        .groups()
                        .roster()
                        .toEntries()
                        .last(),
                ),
            )
        }

    @Test
    fun a_server_that_drops_an_applied_entry_stops_the_device() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val h = holding(g, server = true)
            g.store.roster.removeAt(g.store.roster.lastIndex)
            val opened = runCatching { Session.open(h.db, GroupStore(h.local, g.store), g.b) }
            assertTrue(opened.exceptionOrNull() is LinkedException.RosterRolledBack)
        }

    @Test
    fun a_server_that_changes_an_applied_entry_stops_the_device() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val h = holding(g, server = true)
            val roster = g.store.roster.toList()
            val before = roster.dropLast(1).fold(null as RosterState?) { state, e -> applyEntry(state, e) }
            g.store.roster += removal(g, checkNotNull(before), g.a, g.b, listOf(g.a, g.recovery), SyncCrypto.randomBytes(32))
            val opened = runCatching { Session.open(h.db, GroupStore(h.local, g.store), g.b) }.exceptionOrNull()
            assertTrue(opened is LinkedException.RemovalUnconfirmed || opened is LinkedException.RosterRewritten)
        }

    /** A peer on the LAN of a group on a server can't replace what its server ordered. */
    @Test
    fun an_entry_from_a_peer_that_does_not_extend_the_chain_is_not_adopted_on_a_server_group() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val h = holding(g, server = true)
            val head =
                h.db
                    .groups()
                    .roster()
                    .toEntries()
                    .last()
            val roster =
                h.db
                    .groups()
                    .roster()
                    .toEntries()
            val before = roster.dropLast(1).fold(null as RosterState?) { state, e -> applyEntry(state, e) }
            h.local.addEntries(listOf(removal(g, checkNotNull(before), g.a, g.b, listOf(g.a, g.recovery), SyncCrypto.randomBytes(32))))
            val session = Session.open(h.db, GroupStore(h.local, null), g.b)
            assertEquals(entryHash(head), session.state.head)
        }

    @Test
    fun a_group_from_before_group_ids_committed_to_their_keys_is_refused() =
        runTest {
            val member = newMember()
            val recovery = newMember()
            val group = SyncCrypto.randomBytes(16)
            val genesis =
                buildEntry(
                    group,
                    null,
                    draft(Op.GENESIS, member).copy(
                        add =
                            listOf(
                                memberSubject(member.sigPublic, member.kemPublic, Role.DEVICE),
                                memberSubject(recovery.sigPublic, recovery.kemPublic, Role.RECOVERY),
                            ),
                        epoch = epochDraft(SyncCrypto.randomBytes(32), group, 0, listOf(member, recovery).map(::hex)),
                    ),
                    listOf(member, recovery),
                )
            assertThrows(ProtocolException::class.java) { applyEntry(null, genesis) }
        }
}
