package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.changes.applyBatches
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.Code
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.StoreLocation
import io.github.aedev.flow.sync2.linking.addDevice
import io.github.aedev.flow.sync2.linking.join
import io.github.aedev.flow.sync2.linking.newOffer
import io.github.aedev.flow.sync2.linking.readCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Pairing through the mailbox: a member shows a code, a new device answers it, both confirm, and it syncs. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PairingTest {
    private val meta = DeviceMeta("app-new", "New phone", "android")

    @Test
    fun a_new_device_pairs_and_then_syncs() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val dbA = g.deviceDb()
            val session = Session.open(dbA, g.store, g.a)
            session.push(
                mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"aaaaaaaaaaa"}"""), 1, "")),
                LINK,
            )

            val newcomer = newMember()
            val dbN = emptyDb()
            val offer = newOffer(StoreLocation("server", "memory"), "Desktop A", null)
            val codes = ArrayList<String>()
            val shown = { sas: String ->
                codes += sas
                true
            }
            val did =
                coroutineScope {
                    val added = async { addDevice(session, g.store, offer, g.a, LINK) { shown(it) } }
                    val joined =
                        async {
                            join(
                                dbN,
                                heldStore(dbN),
                                { g.store },
                                g.store,
                                readCode(offer.qr) as Code.Member,
                                newcomer,
                                meta,
                                JOINER,
                            ) { shown(it) }
                        }
                    joined.await()
                    added.await()
                }
            assertEquals(2, codes.size)
            assertEquals(codes[0], codes[1])
            assertEquals(SyncBytes.toHex(newcomer.did), did)
            assertTrue(did in g.store.head().members)
            assertTrue("a joined device has no words to confirm", dbN.groups().group()!!.recoverySaved)

            val joinedSession = Session.open(dbN, g.store, newcomer)
            val pulled = joinedSession.pull(setOf(LINK))
            var arrived = 0
            pulled.batches.values.forEach { batches ->
                applyBatches(dbN.sent(), LINK, batches, 0, { emptyMap() }) {
                    arrived +=
                        it.getValue(SyncCollection.WATCH_HISTORY).lines.size
                }
            }
            joinedSession.finish(pulled)
            assertEquals(1, arrived)
        }

    @Test
    fun declining_the_code_adds_nobody() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            val before = g.store.head().seq
            val offer = newOffer(StoreLocation("server", "memory"), "Desktop A", null)
            val results =
                coroutineScope {
                    val added = async { runCatching { addDevice(session, g.store, offer, g.a, LINK) { false } } }
                    val joined =
                        async {
                            runCatching {
                                val db = emptyDb()
                                join(
                                    db,
                                    heldStore(db),
                                    { g.store },
                                    g.store,
                                    readCode(offer.qr) as Code.Member,
                                    newMember(),
                                    meta,
                                    JOINER,
                                ) { false }
                            }
                        }
                    listOf(added.await(), joined.await())
                }
            results.forEach { assertTrue("${it.exceptionOrNull()}", it.exceptionOrNull() is LinkedException.Declined) }
            assertEquals(before, g.store.head().seq)
        }

    @Test
    fun codes_that_are_not_flow_sync_2_are_refused() {
        val newWithStore = newOffer(StoreLocation("server", "memory"), "Desktop A", null).qr.replace("\"r\":\"m\"", "\"r\":\"n\"")
        val flowSync1 = """{"v":1,"sid":"AAAAAAAAAAAAAAAAAAAAAA","k":"AAAA","ip":"192.168.1.2","p":1,"d":"x","exp":0}"""
        for (qr in listOf(newWithStore, flowSync1, "not json")) {
            assertTrue(qr, runCatching { readCode(qr) }.exceptionOrNull() is LinkedException.Unexpected)
        }
    }
}
