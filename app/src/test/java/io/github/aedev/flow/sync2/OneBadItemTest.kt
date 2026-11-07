package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.engine.writeSnapshot
import io.github.aedev.flow.sync2.roster.canonicalEntry
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.store.ChangeList
import io.github.aedev.flow.sync2.store.FileLayout
import io.github.aedev.flow.sync2.store.FileStore
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.MAX_OBJECT_SIZE
import io.github.aedev.flow.sync2.store.Mailbox
import io.github.aedev.flow.sync2.store.Remote
import io.github.aedev.flow.sync2.store.StoreException
import io.github.aedev.flow.sync2.store.StorePaths
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** One item a store can't hand over stops only that item (spec/store.md, Untrusted input). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class OneBadItemTest {
    private val watched = mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"aaaaaaaaaaa"}"""), 1, ""))

    /** Files in memory, where a read of a path in [failing] throws what it names. */
    private class Files(
        val held: MutableMap<String, ByteArray> = HashMap(),
        val failing: MutableMap<String, StoreException> = HashMap(),
    ) : FileStore {
        var served = 0L
        var tagged: String? = null
        override val location = "memory"

        override suspend fun get(path: String): ByteArray? {
            failing[path]?.let { throw it }
            return held[path]?.also { served += it.size }
        }

        override suspend fun put(
            path: String,
            bytes: ByteArray,
        ) {
            held[path] = bytes
        }

        override suspend fun delete(path: String) {
            held.remove(path)
        }

        override suspend fun list(folder: String) =
            held.keys
                .filter { it.startsWith("$folder/") }
                .map { it.removePrefix("$folder/").substringBefore('/') }
                .distinct()

        override suspend fun tag(folder: String) = tagged
    }

    private fun me(g: LinkedGroup) = SyncBytes.toHex(g.b.did)

    private fun Files.publish(g: LinkedGroup) {
        g.store.roster.forEach { held[StorePaths.roster(it.seq, entryHash(it))] = canonicalEntry(it).toByteArray() }
    }

    private suspend fun TestScope.batchFromA(g: LinkedGroup): String {
        Session.open(g.deviceDb(), g.store, g.a).push(watched, LINK)
        return StorePaths.log(SyncBytes.toHex(g.a.did), 1)
    }

    @Test
    fun a_roster_file_that_can_t_be_read_leaves_the_others_read() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val files = Files().apply { publish(g) }
            val bad = "roster/0000000000000000-0000000000000000.r"
            files.held[bad] = ByteArray(8)
            files.failing[bad] = StoreException.Malformed()
            val local = heldStore(g.deviceDb())

            GroupStore(local, FileLayout(files, me(g))).download(me(g), 0, objects = false)

            assertEquals(g.store.roster.size, local.entries().size)
        }

    @Test
    fun a_log_file_that_can_t_be_read_stops_only_its_sender_and_is_tried_again() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val good = batchFromA(g)
            val first = "00".repeat(16)
            val bad = StorePaths.log(first, 1)
            val files = Files().apply { publish(g) }
            files.tagged = "same"
            files.held[good] = g.store.take(good)
            files.held[bad] = ByteArray(8)
            files.failing[bad] = StoreException.Refused(403, "webdav")
            val local = heldStore(g.deviceDb())

            GroupStore(local, FileLayout(files, me(g))).download(me(g), 0, objects = true)
            assertNotNull("the sender after it was read", local.getObject(good))

            files.failing.clear()
            val asked = ArrayList<String>()
            val spy =
                object : FileStore by files {
                    override suspend fun get(path: String): ByteArray? {
                        asked += path
                        return files.get(path)
                    }
                }
            GroupStore(local, FileLayout(spy, me(g))).download(me(g), 0, objects = true)
            assertTrue("an unread file keeps the folder's tag from being trusted", bad in asked)
        }

    @Test
    fun being_rate_limited_or_refused_a_login_still_ends_the_sync() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val bad =
                StorePaths.roster(
                    g.store.roster
                        .last()
                        .seq,
                    entryHash(g.store.roster.last()),
                )
            for (stop in listOf(StoreException.Refused(429, "webdav"), StoreException.LoginRefused())) {
                val files = Files().apply { publish(g) }
                files.failing[bad] = stop
                val store = GroupStore(heldStore(g.deviceDb()), FileLayout(files, me(g)))
                assertEquals(stop, runCatching { store.download(me(g), 0, objects = false) }.exceptionOrNull())
            }
        }

    @Test
    fun roster_files_that_don_t_chain_are_not_held() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val files = Files().apply { publish(g) }
            val last = g.store.roster.last()
            val orphan = last.copy(prev = "ab".repeat(32), meta = mapOf("ab".repeat(16) to "x".repeat(1024)))
            files.held[StorePaths.roster(orphan.seq, entryHash(orphan))] = canonicalEntry(orphan).toByteArray()
            val forged = last.copy(meta = mapOf("ab".repeat(16) to "x".repeat(1024)))
            files.held[StorePaths.roster(forged.seq, entryHash(forged))] = canonicalEntry(forged).toByteArray()
            val local = heldStore(g.deviceDb())

            GroupStore(local, FileLayout(files, me(g))).download(me(g), 0, objects = false)

            assertEquals(
                g.store.roster
                    .map(::entryHash)
                    .toSet(),
                local.entries().map(::entryHash).toSet(),
            )
        }

    @Test
    fun one_pull_stops_reading_the_roster_folder_past_its_limit() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val files = Files().apply { publish(g) }
            val junk = ByteArray(MAX_OBJECT_SIZE)
            repeat(40) { files.held["roster/ffffffffffffff%02x-0000000000000000.r".format(it)] = junk }

            val local = heldStore(g.deviceDb())
            GroupStore(local, FileLayout(files, me(g))).download(me(g), 0, objects = false)

            assertEquals("what comes first is read", g.store.roster.size, local.entries().size)
            assertTrue("read ${files.served} bytes", files.served < 70L * 1024 * 1024)
        }

    /** The Flow server, refusing [refused]. */
    private class PickyServer(
        private val real: MemoryStore,
        private val refused: String,
    ) : Remote,
        ChangeList by real,
        Mailbox by real {
        override suspend fun getObject(path: String): ByteArray? {
            if (path == refused) throw StoreException.Malformed()
            return real.getObject(path)
        }
    }

    @Test
    fun an_object_a_server_can_t_hand_over_leaves_the_others_held_and_its_page_is_read_again() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = Session.open(g.deviceDb(), g.store, g.a)
            a.push(watched, LINK)
            a.push(watched, "other-link")
            val aHex = SyncBytes.toHex(g.a.did)
            val (bad, good) = StorePaths.log(aHex, 1) to StorePaths.log(aHex, 2)
            val local = heldStore(g.deviceDb())

            val next = GroupStore(local, PickyServer(g.store, bad)).download(me(g), 0, objects = true)

            assertNull(local.getObject(bad))
            assertNotNull(local.getObject(good))
            assertEquals(0, next)
        }

    @Test
    fun a_snapshot_that_can_t_be_read_yet_leaves_the_link_for_a_later_sync() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = Session.open(g.deviceDb(), g.store, g.a)
            a.writeSnapshot(watched, "other-link")
            val db = g.deviceDb()
            val b = Session.open(db, g.store, g.b)
            b.pull(setOf(LINK)).also { b.finish(it) }
            val blobs =
                g.store.objects.keys
                    .filter { StorePaths.isBlob(it) }
            assertTrue(blobs.isNotEmpty())
            val taken = blobs.associateWith { g.store.take(it) }

            assertNull(Session.open(db, g.store, g.b).startReading("other-link"))

            taken.forEach { (path, bytes) -> g.store.put(path, bytes) }
            assertNotNull(Session.open(db, g.store, g.b).startReading("other-link"))
        }
}
