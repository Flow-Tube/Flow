package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.data.CollectedRow
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.engine.Facts
import io.github.aedev.flow.sync2.engine.Logged
import io.github.aedev.flow.sync2.engine.Manifest
import io.github.aedev.flow.sync2.engine.Plan
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.engine.Tip
import io.github.aedev.flow.sync2.engine.VersionVector
import io.github.aedev.flow.sync2.engine.collectGarbage
import io.github.aedev.flow.sync2.engine.writeSnapshot
import io.github.aedev.flow.sync2.store.ChangeList
import io.github.aedev.flow.sync2.store.StoreException
import io.github.aedev.flow.sync2.store.StorePaths
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class GarbageCollectionTest {
    private val link = "link"

    private fun snapshot(
        did: String,
        seq: Long,
        vararg covers: Pair<String, Long>,
        of: String? = link,
    ) = Logged(did, seq, Manifest("", of, emptyList(), covers.associate { (d, s) -> d to Tip("", s) }))

    private fun plan(
        current: Set<String>,
        collectable: Set<String>,
        acks: Map<String, VersionVector>,
        manifests: List<Logged>,
        links: Set<String?> = setOf(link),
    ) = Facts(current, collectable, links, acks, manifests, emptyMap()).plan()

    @Test
    fun nothing_goes_before_every_device_acked_it_and_a_snapshot_covers_it() {
        val snap = listOf(snapshot("a", 6, "a" to 5))
        val devices = setOf("a", "b")
        assertEquals(Plan(), plan(devices, setOf("a"), emptyMap(), snap))
        val acked = mapOf("b" to mapOf("a" to 6L))
        assertEquals(Plan(), plan(devices, setOf("a"), acked, emptyList()))
        assertEquals(mapOf("a" to 1L..5L), plan(devices, setOf("a"), acked, snap).logs)
        assertEquals(Plan(), plan(devices, setOf("a"), mapOf("b" to mapOf("a" to 3L)), snap))
    }

    @Test
    fun a_removed_device_log_goes_once_the_remaining_devices_acked_it() {
        val snap = listOf(snapshot("a", 9, "a" to 8, "gone" to 4))
        val acks = mapOf("b" to mapOf("a" to 9L, "gone" to 4L), "a" to mapOf("gone" to 4L))
        assertEquals(1L..4L, plan(setOf("a", "b"), setOf("a", "gone"), acks, snap).logs["gone"])
    }

    @Test
    fun a_snapshot_goes_once_a_newer_acknowledged_one_covers_it() {
        val old = snapshot("a", 3, "a" to 2, "b" to 1)
        val new = snapshot("a", 7, "a" to 6, "b" to 4)
        assertEquals(listOf(old), plan(setOf("a", "b"), setOf("a"), mapOf("b" to mapOf("a" to 7L)), listOf(old, new)).manifests)
        assertTrue(plan(setOf("a", "b"), setOf("a"), mapOf("b" to mapOf("a" to 4L)), listOf(old, new)).manifests.isEmpty())
    }

    @Test
    fun a_snapshot_stays_until_every_device_read_past_it_even_when_another_covers_it() {
        // The Mac's manifest 573 is covered by the phone's acknowledged snapshot, but the phone has read the Mac's log only to 572.
        val mac = snapshot("mac", 573, "mac" to 572, "phone" to 40)
        val phone = snapshot("phone", 41, "mac" to 572, "phone" to 40)
        val acks = mapOf("mac" to mapOf("phone" to 41L))
        val devices = setOf("mac", "phone")
        assertTrue(plan(devices, setOf("mac"), acks + ("phone" to mapOf("mac" to 572L)), listOf(mac, phone)).manifests.isEmpty())
        assertEquals(listOf(mac), plan(devices, setOf("mac"), acks + ("phone" to mapOf("mac" to 573L)), listOf(mac, phone)).manifests)
    }

    @Test
    fun every_link_batches_name_needs_an_acknowledged_snapshot() {
        val acks = mapOf("b" to mapOf("a" to 9L))
        val links = setOf(link, null)
        assertEquals(Plan(), plan(setOf("a", "b"), setOf("a"), acks, listOf(snapshot("a", 6, "a" to 5)), links))
        val both = listOf(snapshot("a", 6, "a" to 5), snapshot("a", 8, "a" to 7, of = null))
        assertEquals(mapOf("a" to 1L..5L), plan(setOf("a", "b"), setOf("a"), acks, both, links).logs)
    }

    @Test
    fun old_objects_go_once_acknowledged_and_covered_by_a_snapshot() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (dbA, dbB) = g.deviceDb() to g.deviceDb()
            val first = StorePaths.log(SyncBytes.toHex(g.a.did), 1)
            val watched = mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"aaaaaaaaaaa"}"""), 1, ""))
            val readAll =
                suspend { db: LinkedDatabase, device: MemberSecrets ->
                    val session = Session.open(db, g.store, device)
                    session.finish(session.pull(setOf(LINK)))
                }

            Session.open(dbA, g.store, g.a).apply {
                push(watched, LINK)
                collectGarbage(setOf(LINK))
            }
            assertTrue("nobody acknowledged it yet", first in g.store.objects)

            readAll(dbB, g.b)
            readAll(dbA, g.a)
            Session.open(dbA, g.store, g.a).collectGarbage(setOf(LINK))
            assertTrue("no snapshot covers it yet", first in g.store.objects)

            val a = Session.open(dbA, g.store, g.a)
            a.writeSnapshot(watched, LINK)
            val manifest = StorePaths.log(SyncBytes.toHex(g.a.did), a.record.ownSeq)
            readAll(dbB, g.b)
            readAll(dbA, g.a)
            Session.open(dbA, g.store, g.a).collectGarbage(setOf(LINK))
            assertFalse(first in g.store.objects)
            assertTrue("the newest snapshot stays", manifest in g.store.objects)
            assertTrue(
                "another device's log is not this device's to delete",
                StorePaths.log(SyncBytes.toHex(g.b.did), 1) in g.store.objects,
            )
        }

    /** The group's store, recording each delete it is asked for, or refusing them all as rate limited. */
    private class Watched(
        private val inner: MemoryStore,
        private val refuse: Boolean = false,
    ) : ChangeList by inner {
        val deleted = ArrayList<String>()

        override suspend fun deleteObject(path: String) {
            if (refuse) throw StoreException.Refused(429, "rate_limited")
            deleted += path
            inner.deleteObject(path)
        }
    }

    /** Device A's first log object, acknowledged by both devices and covered by an acknowledged snapshot. */
    private suspend fun TestScope.collectable(): Triple<LinkedGroup, LinkedDatabase, String> {
        val g = LinkedGroup(this).apply { create() }
        val (dbA, dbB) = g.deviceDb() to g.deviceDb()
        val watched = mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"aaaaaaaaaaa"}"""), 1, ""))
        Session.open(dbA, g.store, g.a).apply {
            push(watched, LINK)
            writeSnapshot(watched, LINK)
        }
        for ((db, device) in listOf(dbB to g.b, dbA to g.a)) Session.open(db, g.store, device).run { finish(pull(setOf(LINK))) }
        return Triple(g, dbA, StorePaths.log(SyncBytes.toHex(g.a.did), 1))
    }

    @Test
    fun a_collection_that_starts_over_deletes_only_what_this_device_still_holds() =
        runTest {
            val (g, dbA, first) = collectable()
            Session.open(dbA, g.store, g.a).collectGarbage(setOf(LINK))
            assertFalse(first in g.store.objects)

            // The marker is gone, as after leaving and joining again: nothing already deleted is asked for again.
            dbA.collection().setCollected(CollectedRow(SyncBytes.toHex(g.a.did), 0))
            val store = Watched(g.store)
            Session.open(dbA, store, g.a).collectGarbage(setOf(LINK))
            assertEquals(emptyList<String>(), store.deleted)
        }

    @Test
    fun a_rate_limited_collection_stops_and_finishes_on_a_later_sync() =
        runTest {
            val (g, dbA, first) = collectable()
            Session.open(dbA, Watched(g.store, refuse = true), g.a).collectGarbage(setOf(LINK))
            assertTrue(first in g.store.objects)

            Session.open(dbA, g.store, g.a).collectGarbage(setOf(LINK))
            assertFalse(first in g.store.objects)
        }
}
