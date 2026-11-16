package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.rotateEpoch
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.roster.memberSubject
import io.github.aedev.flow.sync2.store.Appended
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.Remote
import io.github.aedev.flow.sync2.store.StorePaths
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What a device holds of its group, and how a server group fills it and drains it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HeldStoreTest {
    private suspend fun TestScope.heldCopy(g: LinkedGroup): Pair<LinkedDatabase, GroupStore> {
        val db = g.deviceDb()
        return db to GroupStore(heldStore(db), g.store)
    }

    @Test
    fun objects_are_held_once_paged_in_arrival_order_and_listed_above_a_version_vector() =
        runTest {
            val store = heldStore(emptyDb())
            val did = "ab".repeat(16)
            store.putObject(StorePaths.log(did, 1), byteArrayOf(1))
            store.putObject(StorePaths.log(did, 2), byteArrayOf(2))
            store.putObject(StorePaths.log(did, 1), byteArrayOf(9))
            assertArrayEquals(byteArrayOf(1), store.getObject(StorePaths.log(did, 1)))
            val (changes, next) = store.changes(0)
            assertEquals(listOf(StorePaths.log(did, 1), StorePaths.log(did, 2)), changes.map { it.path })
            assertTrue(store.changes(next).first.isEmpty())
            assertEquals(listOf(StorePaths.log(did, 2)), store.list(mapOf(did to 1L)).first)
            assertTrue(runCatching { store.putObject("dev/../../escape", byteArrayOf(0)) }.isFailure)
            assertEquals(null, store.getObject("dev/../../escape"))
        }

    @Test
    fun the_roster_follows_the_fork_winner_and_an_append_names_the_head_it_builds_on() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val store = heldStore(emptyDb())
            store.addEntries(g.store.roster)
            val state = g.store.head()
            val (c, d) = newMember() to newMember()
            val addC =
                buildEntry(
                    g.group,
                    state,
                    draft(Op.ADD, g.a).copy(add = listOf(memberSubject(c.sigPublic, c.kemPublic, Role.DEVICE))),
                    listOf(g.a, c),
                )
            val addD =
                buildEntry(
                    g.group,
                    state,
                    draft(Op.ADD, g.b).copy(add = listOf(memberSubject(d.sigPublic, d.kemPublic, Role.DEVICE))),
                    listOf(g.b, d),
                )
            store.addEntries(listOf(addC, addD))
            val winner = listOf(addC, addD).minBy { entryHash(it) }
            assertEquals(entryHash(winner), entryHash(store.rosterAfter(-1).last()))
            assertEquals("fork candidates are kept", 4, store.entries().size)
            val stale = store.appendRoster(state.head, addC, emptyMap(), null)
            assertEquals(Appended.Conflict(entryHash(winner)), stale)
        }

    @Test
    fun a_server_whose_cursor_never_moves_is_downloaded_once() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = SyncBytes.toHex(g.a.did)
            Session.open(g.deviceDb(), g.store, g.a).push(
                mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"aaaaaaaaaaa"}"""), 1, "")),
                LINK,
            )
            val db = g.deviceDb()
            val store = GroupStore(heldStore(db), StuckPages(g.store))
            assertEquals(0L, store.download(SyncBytes.toHex(g.b.did), 0, objects = true))
            assertTrue(store.local.getObject(StorePaths.log(a, 1)) != null)
        }

    @Test
    fun a_server_group_syncs_through_what_each_device_holds() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (dbA, storeA) = heldCopy(g)
            val (dbB, storeB) = heldCopy(g)
            val (a, b) = SyncBytes.toHex(g.a.did) to SyncBytes.toHex(g.b.did)

            storeA.download(a, 0, objects = true)
            Session.open(dbA, storeA, g.a).push(
                mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"aaaaaaaaaaa"}"""), 1, "")),
                LINK,
            )
            assertTrue("nothing reaches the server before the upload", g.store.objects.isEmpty())
            storeA.upload(a, 0)
            assertTrue(StorePaths.log(a, 1) in g.store.objects)

            storeB.download(b, 0, objects = true)
            assertTrue(a in Session.open(dbB, storeB, g.b).pull(setOf(LINK)).batches)
        }

    @Test
    fun a_download_asks_only_for_keys_the_store_holds() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            check(rotateEpoch(Session.open(g.deviceDb(), g.store, g.a), g.a))
            val state = g.store.head()
            val c = newMember()
            val add =
                buildEntry(
                    g.group,
                    state,
                    draft(Op.ADD, g.a).copy(add = listOf(memberSubject(c.sigPublic, c.kemPublic, Role.DEVICE))),
                    listOf(g.a, c),
                )
            val current = Session.open(g.deviceDb(), g.store, g.a).currentKey()
            check(g.store.appendRoster(state.head, add, wraps(g.group, 1, current, listOf(c)), null) == Appended.Done)
            for (member in listOf(g.b, c)) {
                val asked = AskedKeys(g.store)
                GroupStore(heldStore(emptyDb()), asked).download(SyncBytes.toHex(member.did), 0, objects = false)
                assertTrue("${asked.missing}", asked.missing.isEmpty())
            }
        }

    /** Records each key a download asks for that the store doesn't hold. */
    private class AskedKeys(
        private val inner: MemoryStore,
    ) : Remote by inner {
        val missing = ArrayList<Pair<Int, String>>()

        override suspend fun key(
            epoch: Int,
            kc: String,
            name: String,
        ) = inner.key(epoch, kc, name).also { if (it == null) missing += epoch to name }
    }
}
