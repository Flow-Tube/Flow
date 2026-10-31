package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.Code
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.StoreLocation
import io.github.aedev.flow.sync2.linking.addDevice
import io.github.aedev.flow.sync2.linking.join
import io.github.aedev.flow.sync2.linking.newOffer
import io.github.aedev.flow.sync2.linking.readCode
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.canonicalEntry
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.roster.forkWinner
import io.github.aedev.flow.sync2.store.FileLayout
import io.github.aedev.flow.sync2.store.FileStore
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.StorePaths
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A group on WebDAV: the store layout on plain files, without a change list or linear roster appends. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FileLayoutTest {
    private val watched = mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"aaaaaaaaaaa"}"""), 1, ""))
    private var dav: WebDavStandIn? = null

    @After
    fun stop() {
        dav?.close()
    }

    /** A's group as it would sit on WebDAV: its roster entries and wraps. */
    private suspend fun publish(
        g: LinkedGroup,
        files: FileStore,
    ) {
        g.store.roster.forEach { files.put(StorePaths.roster(it.seq, entryHash(it)), canonicalEntry(it).toByteArray()) }
        g.store.keys.forEach { (key, wrap) -> files.put(StorePaths.key(key.first, key.second), wrap) }
    }

    private suspend fun TestScope.device(
        g: LinkedGroup,
        files: FileStore,
        secrets: MemberSecrets,
    ): Pair<LinkedDatabase, GroupStore> {
        val db = g.deviceDb()
        val me = SyncBytes.toHex(secrets.did)
        return db to GroupStore(heldStore(db), FileLayout(files, me)).also { it.download(me, 0, objects = true) }
    }

    @Test
    fun devices_sync_through_webdav_by_reading_each_sender_s_next_object() =
        runTest {
            val server = WebDavStandIn(nextcloud = true).also { dav = it }
            val g = LinkedGroup(this).apply { create() }
            publish(g, server.store())
            val (dbA, storeA) = device(g, server.store(), g.a)
            Session.open(dbA, storeA, g.a).push(watched, LINK)
            storeA.upload(SyncBytes.toHex(g.a.did), 0)

            val (dbB, storeB) = device(g, server.store(), g.b)
            val pulled = Session.open(dbB, storeB, g.b).pull(setOf(LINK))
            assertEquals(listOf(SyncBytes.toHex(g.a.did)), pulled.batches.keys.toList())

            server.requests.clear()
            storeB.download(SyncBytes.toHex(g.b.did), 0, objects = true)
            assertTrue("an unchanged Nextcloud folder isn't read again", server.requests.none { it.startsWith("GET flow/dev/") })
        }

    @Test
    fun competing_roster_entries_both_stay_and_every_device_picks_the_same_winner() =
        runTest {
            val server = WebDavStandIn().also { dav = it }
            val g = LinkedGroup(this).apply { create() }
            val files = server.store()
            publish(g, files)
            val renames =
                listOf(g.a, g.b).map { signer ->
                    buildEntry(
                        g.group,
                        g.store.head(),
                        draft(Op.RENAME, signer).copy(meta = mapOf(SyncBytes.toHex(signer.did) to "bmFtZQ")),
                        listOf(signer),
                    )
                }
            renames.forEach { files.put(StorePaths.roster(it.seq, entryHash(it)), canonicalEntry(it).toByteArray()) }

            val winner = entryHash(checkNotNull(forkWinner(renames)))
            for (secrets in listOf(g.a, g.b)) {
                val (_, store) = device(g, files, secrets)
                assertEquals(2, store.local.entries().count { it.seq == renames[0].seq })
                assertEquals(winner, entryHash(store.local.rosterAfter(-1).last()))
            }
            assertEquals(winner, entryHash(FileLayout(files, "").rosterAfter(-1).last()))
        }

    @Test
    fun a_new_device_pairs_through_a_webdav_mailbox() =
        runTest {
            val server = WebDavStandIn().also { dav = it }
            val g = LinkedGroup(this).apply { create() }
            publish(g, server.store())
            val (dbA, storeA) = device(g, server.store(), g.a)
            val session = Session.open(dbA, storeA, g.a)
            val newcomer = newMember()
            val dbN = emptyDb()
            val layout = FileLayout(server.store(), SyncBytes.toHex(newcomer.did))
            val offer = newOffer(StoreLocation("server", "memory"), "Desktop A", null)
            val did =
                coroutineScope {
                    val joined =
                        async {
                            join(
                                dbN,
                                heldStore(dbN),
                                { layout },
                                layout,
                                readCode(offer.qr) as Code.Member,
                                newcomer,
                                DeviceMeta("app-n", "Phone", "android"),
                                JOINER,
                            ) { true }
                        }
                    val added = addDevice(session, FileLayout(server.store(), SyncBytes.toHex(g.a.did)), offer, g.a, LINK) { true }
                    joined.await()
                    added
                }
            assertEquals(SyncBytes.toHex(newcomer.did), did)
            assertTrue(did in FileLayout(server.store(), did).rosterAfter(-1).flatMap { it.add.orEmpty() }.map { it.did })
            assertTrue("the slot is gone once pairing ends", server.files.keys.none { it.startsWith("flow/pair/") })
        }

    @Test
    fun a_device_deletes_only_its_own_paths() =
        runTest {
            val server = WebDavStandIn().also { dav = it }
            val files = server.store()
            val mine = StorePaths.log("a".repeat(32), 1)
            val theirs = StorePaths.log("b".repeat(32), 1)
            files.put(mine, byteArrayOf(1))
            files.put(theirs, byteArrayOf(2))
            val layout = FileLayout(files, "a".repeat(32))
            layout.deleteObject(mine)
            layout.deleteObject(theirs)
            assertEquals(null, server.file(mine))
            assertTrue(server.file(theirs) != null)
        }
}
