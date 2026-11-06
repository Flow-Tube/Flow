package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.engine.writeSnapshot
import io.github.aedev.flow.sync2.linking.leaveGroup
import io.github.aedev.flow.sync2.linking.removeDevices
import io.github.aedev.flow.sync2.store.StorePaths
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Where a removed device's log ends (spec/store.md, Reading an envelope): readers stop at the object its removal names, along the chain that reaches it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RemovedLogTest {
    private val link = "link"

    private fun bDid(g: LinkedGroup) = SyncBytes.toHex(g.b.did)

    private fun history(vararg ids: String) =
        mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(ids.map { """{"title":"t","videoId":"$it"}""" }, ids.size, ""))

    private suspend fun readAll(session: Session) = session.finish(session.pull(setOf(link)))

    private suspend fun batchesANewReaderGets(g: LinkedGroup): Int {
        val reader = Session.open(g.deviceDb(), g.store, g.a)
        return reader.pull(setOf(link)).batches[bDid(g)]?.size ?: 0
    }

    private fun takeLog(g: LinkedGroup): Map<Long, ByteArray> =
        (1L..64L)
            .mapNotNull { seq ->
                g.store.objects[StorePaths.log(bDid(g), seq)]?.let {
                    seq to
                        g.store.take(StorePaths.log(bDid(g), seq))
                }
            }.toMap()

    private fun putLog(
        g: LinkedGroup,
        log: Map<Long, ByteArray>,
    ) = log.forEach { (seq, bytes) -> g.store.put(StorePaths.log(bDid(g), seq), bytes) }

    /** B's log as a second copy of B would write it, kept out of the store. */
    private suspend fun otherCopy(
        g: LinkedGroup,
        vararg writes: Array<String>,
    ): Map<Long, ByteArray> {
        val genuine = takeLog(g)
        val copy = Session.open(g.deviceDb(), g.store, g.b)
        for (ids in writes) copy.push(history(*ids), link)
        val other = takeLog(g)
        putLog(g, genuine)
        return other
    }

    private suspend fun ownSeq(db: LinkedDatabase) = checkNotNull(db.groups().group()).ownSeq

    @Test
    fun what_a_removed_device_writes_later_is_not_read() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val dbA = g.deviceDb()
            val a = Session.open(dbA, g.store, g.a)
            val b = Session.open(g.deviceDb(), g.store, g.b)
            b.push(history("aaaaaaaaaaa"), link)
            val before = batchesANewReaderGets(g)
            readAll(a)
            removeDevices(a, g.a, listOf(bDid(g)))

            b.push(history("aaaaaaaaaaa", "bbbbbbbbbbb"), link)

            assertTrue(before > 0)
            assertEquals(before, batchesANewReaderGets(g))
        }

    @Test
    fun what_a_removed_device_writes_later_is_deleted() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (dbA, dbB) = g.deviceDb() to g.deviceDb()
            val a = Session.open(dbA, g.store, g.a)
            val b = Session.open(dbB, g.store, g.b)
            b.push(history("aaaaaaaaaaa"), link)
            readAll(a)
            val cut = ownSeq(dbB)
            removeDevices(a, g.a, listOf(bDid(g)))
            b.push(history("aaaaaaaaaaa", "bbbbbbbbbbb"), link)

            readAll(Session.open(dbA, g.store, g.a))

            assertTrue(StorePaths.log(bDid(g), cut) in g.store.objects)
            assertTrue(StorePaths.log(bDid(g), cut + 1) !in g.store.objects)
        }

    @Test
    fun a_removal_that_read_nothing_of_a_log_ends_it_at_the_start() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = Session.open(g.deviceDb(), g.store, g.a)
            val b = Session.open(g.deviceDb(), g.store, g.b)
            b.push(history("aaaaaaaaaaa"), link)
            assertTrue(batchesANewReaderGets(g) > 0)
            removeDevices(a, g.a, listOf(bDid(g)))

            assertEquals(0, batchesANewReaderGets(g))
        }

    @Test
    fun an_object_replaced_below_the_cut_is_not_applied() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = Session.open(g.deviceDb(), g.store, g.a)
            val b = Session.open(g.deviceDb(), g.store, g.b)
            b.push(history("aaaaaaaaaaa"), link)
            b.push(history("aaaaaaaaaaa", "bbbbbbbbbbb"), link)
            assertTrue(batchesANewReaderGets(g) > 0)
            readAll(a)
            val other = otherCopy(g, arrayOf("ccccccccccc"), arrayOf("ccccccccccc", "ddddddddddd"))
            removeDevices(a, g.a, listOf(bDid(g)))

            g.store.put(StorePaths.log(bDid(g), 1), other.getValue(1))

            assertEquals(0, batchesANewReaderGets(g))
        }

    @Test
    fun an_object_replaced_at_the_cut_stops_the_log_before_anything_is_applied() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val dbB = g.deviceDb()
            val a = Session.open(g.deviceDb(), g.store, g.a)
            val b = Session.open(dbB, g.store, g.b)
            b.push(history("aaaaaaaaaaa"), link)
            b.push(history("aaaaaaaaaaa", "bbbbbbbbbbb"), link)
            readAll(a)
            val last = ownSeq(dbB)
            val other = otherCopy(g, arrayOf("aaaaaaaaaaa"), arrayOf("aaaaaaaaaaa", "ccccccccccc"))
            removeDevices(a, g.a, listOf(bDid(g)))

            g.store.put(StorePaths.log(bDid(g), last), other.getValue(last))

            assertEquals(0, batchesANewReaderGets(g))
        }

    @Test
    fun a_device_that_read_past_the_cut_keeps_its_place() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (dbA, dbB, dbR) = Triple(g.deviceDb(), g.deviceDb(), g.deviceDb())
            val a = Session.open(dbA, g.store, g.a)
            val b = Session.open(dbB, g.store, g.b)
            val reader = Session.open(dbR, g.store, g.a)
            b.push(history("aaaaaaaaaaa"), link)
            readAll(a)
            b.push(history("aaaaaaaaaaa", "bbbbbbbbbbb"), link)
            readAll(reader)
            val did = bDid(g)
            val reached = ownSeq(dbB)

            suspend fun at(db: LinkedDatabase) =
                db
                    .groups()
                    .streams()
                    .first { it.did == did }
                    .seq
            assertEquals(reached, at(dbR))
            assertTrue(at(dbA) < reached)
            removeDevices(a, g.a, listOf(did))

            readAll(Session.open(dbR, g.store, g.a))

            assertEquals(reached, at(dbR))
        }

    @Test
    fun a_leave_names_the_device_s_own_last_object() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val dbB = g.deviceDb()
            val b = Session.open(dbB, g.store, g.b)
            b.push(history("aaaaaaaaaaa"), link)
            val before = batchesANewReaderGets(g)
            val own = checkNotNull(dbB.groups().group())
            leaveGroup(b, g.b)

            val cut =
                checkNotNull(
                    g.store
                        .head()
                        .senders
                        .getValue(bDid(g))
                        .cut,
                )
            assertEquals(own.ownSeq, cut.seq)
            assertEquals(own.ownPrev, cut.hash)

            b.push(history("aaaaaaaaaaa", "bbbbbbbbbbb"), link)
            assertEquals(before, batchesANewReaderGets(g))
        }

    @Test
    fun a_peer_can_t_hand_over_an_object_above_the_cut() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val dbB = g.deviceDb()
            val a = Session.open(g.deviceDb(), g.store, g.a)
            val b = Session.open(dbB, g.store, g.b)
            b.push(history("aaaaaaaaaaa"), link)
            readAll(a)
            val did = bDid(g)
            val cut = ownSeq(dbB)
            removeDevices(a, g.a, listOf(did))
            b.push(history("aaaaaaaaaaa", "bbbbbbbbbbb"), link)

            val reader = Session.open(g.deviceDb(), g.store, g.a)
            val at = g.store.take(StorePaths.log(did, cut))
            val above = g.store.take(StorePaths.log(did, cut + 1))
            assertTrue(runCatching { reader.verified(at, did, cut) }.isSuccess)
            assertTrue(runCatching { reader.verified(above, did, cut + 1) }.isFailure)
        }

    @Test
    fun a_snapshot_of_a_removed_device_counts_only_if_its_chain_reaches_the_cut() =
        runTest {
            for (replaced in listOf(false, true)) {
                val g = LinkedGroup(this).apply { create() }
                val dbB = g.deviceDb()
                val a = Session.open(g.deviceDb(), g.store, g.a)
                val b = Session.open(dbB, g.store, g.b)
                b.push(history("aaaaaaaaaaa"), link)
                b.writeSnapshot(history("aaaaaaaaaaa"), link)
                readAll(a)
                val manifest = ownSeq(dbB)
                val did = bDid(g)

                // Only the manifest goes back: bootstrapping needs a log that doesn't start at its first object.
                val genuine = takeLog(g)
                val dbCopy = g.deviceDb()
                val copy = Session.open(dbCopy, g.store, g.b)
                copy.push(history("ccccccccccc"), link)
                copy.writeSnapshot(history("ccccccccccc"), link)
                val other = takeLog(g)
                assertEquals(manifest, ownSeq(dbCopy))
                g.store.put(StorePaths.log(did, manifest), genuine.getValue(manifest))

                removeDevices(a, g.a, listOf(did))
                if (replaced) g.store.put(StorePaths.log(did, manifest), other.getValue(manifest))

                assertEquals("replaced: $replaced", !replaced, batchesANewReaderGets(g) > 0)
            }
        }
}
