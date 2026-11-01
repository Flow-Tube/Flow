package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync.transport.WsServer
import io.github.aedev.flow.sync2.data.LanAddressRow
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.Code
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.LanEndpoint
import io.github.aedev.flow.sync2.linking.LanPipe
import io.github.aedev.flow.sync2.linking.Listening
import io.github.aedev.flow.sync2.linking.StoreLocation
import io.github.aedev.flow.sync2.linking.addDevice
import io.github.aedev.flow.sync2.linking.addScanned
import io.github.aedev.flow.sync2.linking.answerShown
import io.github.aedev.flow.sync2.linking.join
import io.github.aedev.flow.sync2.linking.newOffer
import io.github.aedev.flow.sync2.linking.readCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Pairing over a loopback WebSocket (spec/protocol.md, Channel), on real time: sockets don't follow a test clock. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LanPairingTest {
    private val meta = DeviceMeta("app-tv", "Living room TV", "android")

    private suspend fun loopback(): Listening {
        val server = WsServer("/flow-sync/2/pair")
        return Listening(server, listOf(LanEndpoint("127.0.0.1", server.start())))
    }

    @Test
    fun a_new_device_shows_a_code_and_a_member_adds_it_over_the_lan() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            session.push(
                mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"aaaaaaaaaaa"}"""), 1, "")),
                LINK,
            )
            val tv = newMember()
            val db = emptyDb()
            val offer = newOffer(null, "Living room TV", loopback())
            val code = readCode(offer.qr)
            assertTrue(code is Code.New)
            val added =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val shown = async { answerShown(offer, tv, meta) { true }.finish(db, heldStore(db), g.store, JOINER) }
                        addScanned(session, code as Code.New, g.a, LINK) { true }.also { shown.await() }
                    }
                }
            assertEquals(SyncBytes.toHex(tv.did), added)
            assertEquals(LINK, db.groups().group()?.link)
            assertTrue(added in Session.open(db, g.store, tv).state.members)
            assertTrue(SyncBytes.toHex(g.a.did) in Session.open(db, g.store, tv).pull(setOf(LINK)).batches)
        }

    @Test
    fun a_new_device_reaches_a_member_code_over_the_lan_first() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val memberDb = g.deviceDb()
            val session = Session.open(memberDb, g.store, g.a)
            val phone = newMember()
            val db = emptyDb()
            val offer = newOffer(StoreLocation("server", "memory"), "Desktop A", loopback())
            val code = readCode(offer.qr) as Code.Member
            assertEquals(1, code.lan.size)
            val added =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val added = async { addDevice(session, g.store, offer, g.a, LINK, port = 4001) { true } }
                        join(db, heldStore(db), { g.store }, g.store, code, phone, meta, JOINER, port = 4002) { true }
                        added.await()
                    }
                }
            assertEquals(SyncBytes.toHex(phone.did), added)
            assertTrue(added in g.store.head().members)
            assertEquals(
                "each remembers the other where its LAN service listens",
                listOf(LanAddressRow(SyncBytes.toHex(g.a.did), "127.0.0.1", 4001)),
                db.lan().addresses(),
            )
            assertEquals(listOf(LanAddressRow(added, "127.0.0.1", 4002)), memberDb.lan().addresses())
        }

    @Test
    fun a_peer_that_closes_the_connection_stopped_rather_than_sent_something_wrong() =
        runTest {
            val closed =
                object : SyncConnection {
                    override suspend fun send(bytes: ByteArray) = Unit

                    override suspend fun receive(): ByteArray? = null

                    override fun close() = Unit
                }
            val error = runCatching { LanPipe(closed, null).receive() }.exceptionOrNull()
            assertTrue("$error", error is LinkedException.Stopped)
        }
}
