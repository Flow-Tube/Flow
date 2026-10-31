package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.changes.applyBatches
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.sealChain
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.epochDraft
import io.github.aedev.flow.sync2.roster.memberSubject
import io.github.aedev.flow.sync2.store.Appended
import io.github.aedev.flow.sync2.store.ChangeList
import io.github.aedev.flow.sync2.store.StorePaths
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The engine between two devices through an in-memory store with the server's rules: push and pull,
 * acknowledgements, a hostile store, missing objects, removal, and a new device reading an earlier epoch.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SessionTest {
    /** A device's watch history, standing in for the app's data: video id to record. */
    private class App {
        val watched = sortedMapOf<String, String>()

        fun watch(id: String) {
            watched[id] = """{"title":"t","videoId":"$id"}"""
        }

        fun exported() = mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(watched.values.toList(), watched.size, ""))
    }

    /** One sync: push, pull, apply, finish. Returns how many records arrived. */
    private suspend fun sync(
        db: LinkedDatabase,
        store: ChangeList,
        device: MemberSecrets,
        app: App,
    ): Int {
        val session = Session.open(db, store, device)
        session.push(app.exported(), LINK)
        val pulled = session.pull(setOf(LINK))
        var arrived = 0
        for (batches in pulled.batches.values) {
            applyBatches(db.sent(), LINK, batches, 0, { app.exported() }) { received ->
                for (line in received[SyncCollection.WATCH_HISTORY]?.lines.orEmpty()) {
                    app.watched[
                        Json
                            .parseToJsonElement(line)
                            .jsonObject
                            .getValue("videoId")
                            .jsonPrimitive.content,
                    ] = line
                    arrived++
                }
            }
        }
        session.finish(pulled)
        return arrived
    }

    @Test
    fun two_devices_exchange_changes_and_acknowledgements() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (dbA, dbB) = g.deviceDb() to g.deviceDb()
            val (appA, appB) = App().apply { watch("aaaaaaaaaaa") } to App().apply { watch("bbbbbbbbbbb") }
            assertEquals(0, sync(dbA, g.store, g.a, appA))
            assertEquals(1, sync(dbB, g.store, g.b, appB))
            assertEquals(1, sync(dbA, g.store, g.a, appA))
            assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), appA.watched.keys.toList())
            assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), appB.watched.keys.toList())
            assertEquals(0, sync(dbA, g.store, g.a, appA))
            assertEquals(0, sync(dbB, g.store, g.b, appB))
            assertEquals("one batch and one ack each, and no acks of acks", 2L, dbA.groups().group()!!.ownSeq)
            assertEquals(2L, dbB.groups().group()!!.ownSeq)
        }

    @Test
    fun a_tampered_object_is_refused() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (dbA, dbB) = g.deviceDb() to g.deviceDb()
            sync(dbA, g.store, g.a, App().apply { watch("aaaaaaaaaaa") })
            val path = StorePaths.log(SyncBytes.toHex(g.a.did), 1)
            val tampered = g.take(path).also { it[it.size - 70] = (it[it.size - 70] + 1).toByte() }
            g.store.put(path, tampered)
            val appB = App()
            val error = runCatching { sync(dbB, g.store, g.b, appB) }.exceptionOrNull()
            assertEquals("signature", (error as ProtocolException).error.code)
            assertTrue(appB.watched.isEmpty())
        }

    @Test
    fun a_missing_object_stalls_reading_until_it_arrives() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (dbA, dbB) = g.deviceDb() to g.deviceDb()
            val appA = App().apply { watch("aaaaaaaaaaa") }
            sync(dbA, g.store, g.a, appA)
            appA.watch("ccccccccccc")
            sync(dbA, g.store, g.a, appA)
            val first = StorePaths.log(SyncBytes.toHex(g.a.did), 1)
            val held = g.take(first)
            val appB = App()
            assertEquals(0, sync(dbB, g.store, g.b, appB))
            g.store.put(first, held)
            sync(dbB, g.store, g.b, appB)
            assertEquals(listOf("aaaaaaaaaaa", "ccccccccccc"), appB.watched.keys.toList())
        }

    @Test
    fun a_removed_device_stops_and_a_new_device_reads_the_old_epoch() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val dbA = g.deviceDb()
            sync(dbA, g.store, g.a, App().apply { watch("aaaaaaaaaaa") })
            val dbB = g.deviceDb()

            val nextKey = SyncCrypto.randomBytes(32)
            val remaining = listOf(g.a, g.recovery)
            var state = g.store.head()
            val remove =
                draft(Op.REMOVE, g.a).copy(
                    remove = listOf(SyncBytes.toHex(g.b.did)),
                    epoch = epochDraft(nextKey, g.group, 1, remaining.map { SyncBytes.toHex(it.did) }),
                )
            val chain = SyncBytes.b64urlEncode(sealChain(nextKey, g.group, 1, g.epochKey))
            assertEquals(
                Appended.Done,
                g.store.appendRoster(
                    state.head,
                    buildEntry(g.group, state, remove, listOf(g.a)),
                    wraps(g.group, 1, nextKey, remaining),
                    chain,
                ),
            )
            assertTrue(runCatching { Session.open(dbB, g.store, g.b) }.exceptionOrNull() is LinkedException.Removed)

            val c = newMember()
            state = g.store.head()
            val add =
                buildEntry(
                    g.group,
                    state,
                    draft(Op.ADD, g.a).copy(add = listOf(memberSubject(c.sigPublic, c.kemPublic, Role.DEVICE))),
                    listOf(g.a, c),
                )
            assertEquals(Appended.Done, g.store.appendRoster(state.head, add, wraps(g.group, 1, nextKey, listOf(c)), null))
            val appC = App()
            sync(g.deviceDb(), g.store, c, appC)
            assertEquals(listOf("aaaaaaaaaaa"), appC.watched.keys.toList())
        }

    private fun LinkedGroup.take(path: String) = store.take(path)
}
