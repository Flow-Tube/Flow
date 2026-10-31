package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.changes.IncomingBatch
import io.github.aedev.flow.sync2.changes.PROFILES
import io.github.aedev.flow.sync2.changes.parseBatch
import io.github.aedev.flow.sync2.data.KeptBatches
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.ProfileNameRow
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.engine.collectGarbage
import io.github.aedev.flow.sync2.engine.writeSnapshot
import io.github.aedev.flow.sync2.store.StorePaths
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.io.path.createTempDirectory

/** Several profiles synced under their own links (spec/protocol.md, Profiles and Reading a link from its snapshot). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ProfileLinksTest {
    private val other = "other-link"

    private fun watched(vararg ids: String) =
        mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(ids.map { """{"title":"t","videoId":"$it"}""" }, ids.size, ""))

    /** The video ids of the watch history records [batches] carry for [link]. */
    private fun ids(
        batches: List<IncomingBatch>?,
        link: String,
    ) = batches
        .orEmpty()
        .filter { it.link == link }
        .flatMap { it.lines }
        .map {
            Json
                .parseToJsonElement(it)
                .jsonObject
                .getValue("videoId")
                .jsonPrimitive.content
        }

    private suspend fun readAll(
        db: LinkedDatabase,
        session: Session,
        links: Set<String?>,
    ) = session.pull(links).also { session.finish(it) }

    @Test
    fun a_pull_keeps_the_linked_links_and_still_acknowledges_the_rest() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            Session.open(g.deviceDb(), g.store, g.a).apply {
                push(watched("aaaaaaaaaaa"), LINK)
                push(watched("bbbbbbbbbbb"), other)
                push(watched("ccccccccccc"), "unlinked")
            }
            val dbB = g.deviceDb()
            val pulled = readAll(dbB, Session.open(dbB, g.store, g.b), setOf(LINK, other))
            val fromA = pulled.batches[SyncBytes.toHex(g.a.did)]
            assertEquals(listOf("aaaaaaaaaaa"), ids(fromA, LINK))
            assertEquals(listOf("bbbbbbbbbbb"), ids(fromA, other))
            assertEquals(listOf<String>(), ids(fromA, "unlinked"))
            assertEquals(
                "all three were consumed",
                3L,
                dbB
                    .groups()
                    .streams()
                    .single()
                    .seq,
            )
        }

    @Test
    fun profile_names_travel_with_no_link_to_every_device() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val record = """{"deleted":false,"hlc":"1:0:aaaaaaaa","link":"$other","name":"Kids"}"""
            Session.open(g.deviceDb(), g.store, g.a).push(mapOf(PROFILES to CollectionWire(listOf(record), 1, "")), null)
            val dbB = g.deviceDb()
            val names = readAll(dbB, Session.open(dbB, g.store, g.b), setOf(null)).batches.getValue(SyncBytes.toHex(g.a.did)).single()
            assertEquals(PROFILES to null, names.collection to names.link)
            assertEquals(listOf(record), names.lines)
        }

    @Test
    fun kept_batches_come_back_in_the_order_they_were_read() =
        runTest {
            val db = emptyDb()
            val kept = KeptBatches(createTempDirectory("kept").toFile(), db.profiles())
            val g = LinkedGroup(this).apply { create() }
            val a = Session.open(g.deviceDb(), g.store, g.a)
            for (id in listOf("aaaaaaaaaaa", "bbbbbbbbbbb")) a.push(watched(id), other)
            a.push(watched("ccccccccccc"), LINK)
            val dbB = g.deviceDb()
            for ((sender, batches) in readAll(dbB, Session.open(dbB, g.store, g.b), setOf(LINK, other)).batches) {
                batches.forEach { kept.keep(checkNotNull(it.link), sender, it) }
            }
            assertEquals(2, kept.count(other))
            val first = checkNotNull(kept.first(other))
            assertEquals(listOf("aaaaaaaaaaa"), ids(listOf(first.batch), other))
            kept.applied(first)
            assertEquals(listOf("bbbbbbbbbbb"), ids(listOf(checkNotNull(kept.first(other)).batch), other))
            assertEquals(1, kept.count(LINK))
        }

    @Test
    fun a_new_device_starts_each_log_at_the_lowest_snapshot_of_its_links() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = Session.open(g.deviceDb(), g.store, g.a)
            a.push(watched("aaaaaaaaaaa"), LINK)
            a.push(watched("bbbbbbbbbbb"), other)
            a.writeSnapshot(watched("aaaaaaaaaaa"), LINK)
            a.push(watched("ccccccccccc"), other)
            a.writeSnapshot(watched("bbbbbbbbbbb", "ccccccccccc"), other)
            a.push(watched("ddddddddddd"), LINK)
            g.store.take(StorePaths.log(SyncBytes.toHex(g.a.did), 1))

            val dbB = g.deviceDb()
            val fromA = readAll(dbB, Session.open(dbB, g.store, g.b), setOf(LINK, other)).batches[SyncBytes.toHex(g.a.did)]
            assertEquals(listOf("aaaaaaaaaaa", "ddddddddddd"), ids(fromA, LINK))
            assertEquals("a batch the link's snapshot includes isn't read again", listOf("bbbbbbbbbbb", "ccccccccccc"), ids(fromA, other))
        }

    @Test
    fun a_link_linked_later_is_read_from_its_snapshot_then_its_earlier_batches() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = Session.open(g.deviceDb(), g.store, g.a)
            a.push(watched("aaaaaaaaaaa"), other)
            a.writeSnapshot(watched("aaaaaaaaaaa"), other)
            a.push(watched("bbbbbbbbbbb"), other)
            a.push(watched("ccccccccccc"), LINK)

            val dbB = g.deviceDb()
            readAll(dbB, Session.open(dbB, g.store, g.b), setOf(LINK))
            val b = Session.open(dbB, g.store, g.b)
            val snapshot = checkNotNull(b.startReading(other))
            assertEquals(listOf("aaaaaaaaaaa"), ids(snapshot[SyncBytes.toHex(g.a.did)], other))
            val caught = readAll(dbB, b, setOf(LINK, other)).batches[SyncBytes.toHex(g.a.did)]
            assertEquals(listOf("bbbbbbbbbbb"), ids(caught, other))
            assertEquals("nothing new of the link already read", listOf<String>(), ids(caught, LINK))
            assertTrue("caught up", dbB.profiles().reads().isEmpty())
        }

    @Test
    fun a_deleted_link_no_longer_holds_back_garbage_collection() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (dbA, dbB) = g.deviceDb() to g.deviceDb()
            val first = StorePaths.log(SyncBytes.toHex(g.a.did), 1)
            Session.open(dbA, g.store, g.a).apply {
                push(watched("aaaaaaaaaaa"), other)
                push(watched("bbbbbbbbbbb"), LINK)
                writeSnapshot(watched("bbbbbbbbbbb"), LINK)
            }
            readAll(dbB, Session.open(dbB, g.store, g.b), setOf(LINK))
            readAll(dbA, Session.open(dbA, g.store, g.a), setOf(LINK))
            Session.open(dbA, g.store, g.a).collectGarbage(setOf(LINK, other))
            assertTrue("the other link has no snapshot", first in g.store.objects)

            dbA.profiles().saveNames(listOf(ProfileNameRow(other, "Kids", "1:0:aaaaaaaa", deleted = true)))
            Session.open(dbA, g.store, g.a).collectGarbage(setOf(LINK, other))
            assertFalse(first in g.store.objects)
        }

    @Test
    fun nothing_is_kept_without_a_file() =
        runTest {
            val db = emptyDb()
            val root = createTempDirectory("kept").toFile()
            val kept = KeptBatches(root, db.profiles())
            kept.keep(other, "a", parseBatch("""{"collection":"likes","head":"","link":"$other"}""".toByteArray()))
            root.listFiles().orEmpty().forEach { it.delete() }
            assertNull(kept.first(other))
            assertEquals(0, kept.count(other))
        }
}
