package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.changes.batchContent
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.sha256
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.rotateEpoch
import io.github.aedev.flow.sync2.roster.canonicalEntry
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.store.FileLayout
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.HEADER_SIZE
import io.github.aedev.flow.sync2.store.ObjectType
import io.github.aedev.flow.sync2.store.StorePaths
import io.github.aedev.flow.sync2.store.parseObject
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A device keeps only what a path names, so one bad answer doesn't stick (spec/store.md, Untrusted input). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HeldObjectsTest {
    private var dav: WebDavStandIn? = null
    private val watched = mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"aaaaaaaaaaa"}"""), 1, ""))

    @After
    fun stop() {
        dav?.close()
    }

    private fun hex(did: ByteArray) = SyncBytes.toHex(did)

    private suspend fun TestScope.batchFromA(g: LinkedGroup): String {
        Session.open(g.deviceDb(), g.store, g.a).push(watched, LINK)
        return StorePaths.log(hex(g.a.did), 1)
    }

    private fun damaged(bytes: ByteArray) = bytes.copyOf(bytes.size - 1) + (bytes.last() + 1).toByte()

    private suspend fun batches(
        g: LinkedGroup,
        store: GroupStore,
        db: LinkedDatabase,
    ) = Session
        .open(db, store, g.b)
        .pull(setOf(LINK))
        .batches.values
        .sumOf { it.size }

    @Test
    fun a_bad_wrap_is_fetched_again_once_the_store_has_it_right() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val db = g.deviceDb()
            val store = GroupStore(heldStore(db), g.store)
            val wrap =
                g.store.keys.keys
                    .single { it.epoch == 0 && it.name == hex(g.b.did) }
            val genuine = checkNotNull(g.store.keys[wrap])
            g.store.keys[wrap] = ByteArray(genuine.size)
            store.download(hex(g.b.did), 0, objects = false)
            assertTrue(runCatching { Session.open(db, store, g.b) }.exceptionOrNull() is ProtocolException)

            g.store.keys[wrap] = genuine
            store.download(hex(g.b.did), 0, objects = false)
            Session.open(db, store, g.b)
        }

    @Test
    fun a_bad_object_is_fetched_again_from_the_saved_cursor_and_the_rest_is_kept() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val sender = g.deviceDb()
            Session.open(sender, g.store, g.a).push(watched, LINK)
            val first = StorePaths.log(hex(g.a.did), 1)
            val session = Session.open(sender, g.store, g.a)
            session.write(ObjectType.CHANGES, batchContent(SyncCollection.WATCH_HISTORY, "00".repeat(32), LINK, listOf("{}")))
            val second = StorePaths.log(hex(g.a.did), session.record.ownSeq)
            val genuine = g.store.take(first)
            val other = g.store.take(second)
            g.store.put(first, damaged(genuine))
            g.store.put(second, other)
            val db = g.deviceDb()
            val store = GroupStore(heldStore(db), g.store)

            val cursor = store.download(hex(g.b.did), 0, objects = true)
            assertEquals("the cursor stays before the object it couldn't keep", 0, cursor)
            assertNull(store.local.getObject(first))
            assertNotNull(store.local.getObject(second))

            g.store.take(first)
            g.store.put(first, genuine)
            store.download(hex(g.b.did), cursor, objects = true)
            assertNotNull(store.local.getObject(first))
        }

    @Test
    fun a_torn_file_is_read_again_once_it_is_whole() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val path = batchFromA(g)
            val genuine = g.store.take(path)
            val server = WebDavStandIn().also { dav = it }
            val files = server.store()
            g.store.roster.forEach { files.put(StorePaths.roster(it.seq, entryHash(it)), canonicalEntry(it).toByteArray()) }
            g.store.keys.forEach { (key, wrap) -> files.put(StorePaths.key(key.epoch, key.kc, key.name), wrap) }
            files.put(path, genuine.copyOf(HEADER_SIZE + 10))
            val db = g.deviceDb()
            val store = GroupStore(heldStore(db), FileLayout(files, hex(g.b.did)))
            store.download(hex(g.b.did), 0, objects = true)
            assertEquals(0, batches(g, store, db))

            server.files["flow/$path"] = genuine
            store.download(hex(g.b.did), 0, objects = true)
            assertEquals(1, batches(g, store, db))
        }

    @Test
    fun a_device_takes_nothing_at_its_own_paths_from_a_store() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val own = StorePaths.log(hex(g.b.did), 1)
            g.store.put(own, ByteArray(600))
            val db = g.deviceDb()
            val store = GroupStore(heldStore(db), g.store)
            val cursor = store.download(hex(g.b.did), 0, objects = true)
            assertNull(store.local.getObject(own))
            assertTrue("its own paths don't hold the cursor back", cursor > 0)

            g.store.take(own)
            Session.open(db, store, g.b).push(watched, LINK)
            store.upload(hex(g.b.did), 0)
            parseObject(checkNotNull(g.store.getObject(own)), g.group).verify(g.b.sigPublic)
        }

    @Test
    fun an_object_from_outside_the_roster_is_not_kept() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val stranger = newMember()
            val path = StorePaths.log(hex(stranger.did), 1)
            g.store.put(path, ByteArray(600))
            val db = g.deviceDb()
            val store = GroupStore(heldStore(db), g.store)
            store.download(hex(g.b.did), 0, objects = true)
            assertNull(store.local.getObject(path))
        }

    @Test
    fun a_blob_is_kept_only_under_its_own_hash() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val blob = ByteArray(64) { 1 }
            val (right, wrong) = hex(sha256(blob)) to hex(sha256(ByteArray(64)))
            val a = hex(g.a.did)
            g.store.put(StorePaths.blob(a, right), blob)
            g.store.put(StorePaths.blob(a, wrong), blob)
            val db = g.deviceDb()
            val store = GroupStore(heldStore(db), g.store)
            store.download(hex(g.b.did), 0, objects = true)
            assertNotNull(store.local.getObject(StorePaths.blob(a, right)))
            assertNull(store.local.getObject(StorePaths.blob(a, wrong)))
        }

    @Test
    fun a_bad_chain_box_is_fetched_again_once_the_store_has_it_right() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val path = batchFromA(g)
            check(rotateEpoch(Session.open(g.deviceDb(), g.store, g.a), g.a))
            val db = g.deviceDb()
            val box =
                g.store.keys.keys
                    .single { it.epoch == 1 && it.name == "chain" }
            val genuine = checkNotNull(g.store.keys[box])
            g.store.keys[box] = damaged(genuine)
            val store = GroupStore(heldStore(db), g.store)
            store.download(hex(g.b.did), 0, objects = true)
            assertNotNull(store.local.getObject(path))
            assertEquals(0, batches(g, store, db))

            g.store.keys[box] = genuine
            store.download(hex(g.b.did), 0, objects = true)
            assertEquals(1, batches(g, store, db))
        }
}
