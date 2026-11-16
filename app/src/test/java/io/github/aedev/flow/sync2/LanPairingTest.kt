package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync2.crypto.agreementPublicKey
import io.github.aedev.flow.sync2.crypto.pairingProof
import io.github.aedev.flow.sync2.crypto.pairingSlot
import io.github.aedev.flow.sync2.data.LanAddressRow
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.Code
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.LanPipe
import io.github.aedev.flow.sync2.linking.Listening
import io.github.aedev.flow.sync2.linking.MailboxPipe
import io.github.aedev.flow.sync2.linking.Offer
import io.github.aedev.flow.sync2.linking.StoreLocation
import io.github.aedev.flow.sync2.linking.addDevice
import io.github.aedev.flow.sync2.linking.addScanned
import io.github.aedev.flow.sync2.linking.answerShown
import io.github.aedev.flow.sync2.linking.dial
import io.github.aedev.flow.sync2.linking.join
import io.github.aedev.flow.sync2.linking.listenOn
import io.github.aedev.flow.sync2.linking.newOffer
import io.github.aedev.flow.sync2.linking.readCode
import io.github.aedev.flow.sync2.store.Mailbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

/** Pairing over a loopback WebSocket (spec/protocol.md, Channel), on real time: sockets don't follow a test clock. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LanPairingTest {
    private val meta = DeviceMeta("app-tv", "Living room TV", "android")

    private suspend fun loopback(): Listening = listenOn(listOf("127.0.0.1"))

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

    /** The store's mailbox, handing each message out once, so a shower that started over could only serve someone else. */
    private class OnceMailbox(
        private val store: MemoryStore,
    ) : Mailbox by store {
        private val handedOut = ConcurrentHashMap.newKeySet<String>()

        override suspend fun getMessage(
            slot: String,
            name: String,
        ): ByteArray? = store.getMessage(slot, name)?.takeIf { handedOut.add(name) }
    }

    private fun firstMessage(
        offer: Offer,
        seed: Byte,
    ): ByteArray = agreementPublicKey(ByteArray(32) { seed }).let { it + pairingProof(offer.token, it) }

    private fun memberOffer(listening: Listening) = newOffer(StoreLocation("server", "memory"), "Desktop A", listening)

    /** What arrives on a connection next: null when it's closed or stays quiet. */
    private suspend fun LanPipe.next(): ByteArray? = runCatching { withTimeoutOrNull(1_000) { receive() } }.getOrNull()

    /** A connection that has sent [first], and stays open so it would see a late answer. */
    private suspend fun connected(
        offer: Offer,
        first: ByteArray,
    ): LanPipe? = dial(readCode(offer.qr).lan)?.also { pipe -> runCatching { pipe.send(first) } }

    /** What a connection gets for [first]: null when it's closed or stays quiet. */
    private suspend fun answerTo(
        offer: Offer,
        first: ByteArray,
    ): ByteArray? = connected(offer, first)?.let { pipe -> pipe.next().also { pipe.close() } }

    @Test
    fun a_second_scanner_on_the_lan_gets_nothing_while_the_first_pairs() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            val offer = memberOffer(loopback())
            withContext(Dispatchers.Default) {
                coroutineScope {
                    val added = async { runCatching { addDevice(session, g.store, offer, g.a, LINK) { true } } }
                    val first = dial(readCode(offer.qr).lan)!!
                    first.send(firstMessage(offer, 8))
                    assertArrayEquals(offer.public, first.receive())
                    val second = connected(offer, firstMessage(offer, 9))
                    assertNull(second?.next())
                    first.send(ByteArray(100))
                    assertNull("the code is finished", second?.next())
                    assertTrue(added.await().isFailure)
                    second?.close()
                    first.close()
                }
            }
        }

    @Test
    fun a_scanner_on_the_mailbox_gets_nothing_after_the_lan_was_answered() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            val offer = memberOffer(loopback())
            val slot = SyncBytes.toHex(pairingSlot(offer.token))
            withContext(Dispatchers.Default) {
                coroutineScope {
                    val added = async { runCatching { addDevice(session, g.store, offer, g.a, LINK) { true } } }
                    val first = dial(readCode(offer.qr).lan)!!
                    first.send(firstMessage(offer, 8))
                    assertArrayEquals(offer.public, first.receive())
                    MailboxPipe(g.store, slot, shower = false).send(firstMessage(offer, 9))
                    delay(2_500)
                    assertNull(g.store.getMessage(slot, "a-0000000000000000.p"))
                    first.send(ByteArray(100))
                    assertTrue(added.await().isFailure)
                    first.close()
                }
            }
        }

    @Test
    fun a_scanner_on_the_lan_gets_nothing_after_the_mailbox_was_answered() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            val offer = memberOffer(loopback())
            val slot = SyncBytes.toHex(pairingSlot(offer.token))
            withContext(Dispatchers.Default) {
                coroutineScope {
                    val added = async { runCatching { addDevice(session, OnceMailbox(g.store), offer, g.a, LINK) { true } } }
                    val first = MailboxPipe(g.store, slot, shower = false)
                    while (runCatching { first.send(firstMessage(offer, 8)) }.isFailure) delay(50)
                    assertArrayEquals(offer.public, first.receive())
                    val second = checkNotNull(connected(offer, firstMessage(offer, 9)))
                    assertNull(second.next())
                    first.send(ByteArray(100))
                    assertNull("the code is finished", second.next())
                    assertTrue(added.await().isFailure)
                    second.close()
                }
            }
        }

    @Test
    fun a_wrong_proof_on_the_lan_gets_no_answer_and_the_real_scanner_still_pairs() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            val phone = newMember()
            val db = emptyDb()
            val offer = memberOffer(loopback())
            val added =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val added = async { addDevice(session, g.store, offer, g.a, LINK) { true } }
                        val key = agreementPublicKey(ByteArray(32) { 8 })
                        assertNull(answerTo(offer, key + pairingProof(ByteArray(16) { 1 }, key)))
                        assertNull(answerTo(offer, ByteArray(32) { 2 }))
                        join(db, heldStore(db), { g.store }, g.store, readCode(offer.qr) as Code.Member, phone, meta, JOINER) { true }
                        added.await()
                    }
                }
            assertEquals(SyncBytes.toHex(phone.did), added)
        }

    @Test
    fun a_wrong_proof_on_the_mailbox_leaves_the_lan_open_for_the_real_scanner() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            val phone = newMember()
            val db = emptyDb()
            val offer = memberOffer(loopback())
            val slot = SyncBytes.toHex(pairingSlot(offer.token))
            val added =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val added = async { addDevice(session, g.store, offer, g.a, LINK) { true } }
                        val stranger = MailboxPipe(g.store, slot, shower = false)
                        val key = agreementPublicKey(ByteArray(32) { 8 })
                        while (runCatching { stranger.send(key + pairingProof(ByteArray(16) { 1 }, key)) }.isFailure) delay(50)
                        delay(2_500)
                        assertNull(g.store.getMessage(slot, "a-0000000000000000.p"))
                        join(db, heldStore(db), { g.store }, g.store, readCode(offer.qr) as Code.Member, phone, meta, JOINER) { true }
                        added.await()
                    }
                }
            assertEquals(SyncBytes.toHex(phone.did), added)
        }

    @Test
    fun the_real_scanner_pairs_while_a_silent_connection_is_still_open() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            val tv = newMember()
            val db = emptyDb()
            val offer = newOffer(null, "Living room TV", loopback())
            val code = readCode(offer.qr) as Code.New
            val added =
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val shown = async { answerShown(offer, tv, meta) { true }.finish(db, heldStore(db), g.store, JOINER) }
                        val silent = dial(code.lan)!!
                        addScanned(session, code, g.a, LINK) { true }.also {
                            shown.await()
                            silent.close()
                        }
                    }
                }
            assertEquals(SyncBytes.toHex(tv.did), added)
        }

    @Test
    fun a_silent_connection_does_not_hold_up_the_answer_to_a_real_scanner() =
        runTest {
            val offer = memberOffer(loopback())
            val lan = readCode(offer.qr).lan
            withContext(Dispatchers.Default) {
                val silent = dial(lan)!!
                val listening = offer.listening!!
                val accepted = async { listening.accept(offer.token) }
                delay(200)
                val real = dial(lan)!!
                real.send(firstMessage(offer, 8))
                val (pipe, key) = withTimeout(3_000) { accepted.await() }
                assertArrayEquals(agreementPublicKey(ByteArray(32) { 8 }), key)
                pipe.send(offer.reveal())
                assertArrayEquals(offer.public, real.receive())
                listOf(silent, real, pipe).forEach { it.close() }
                listening.stop()
            }
        }

    @Test
    fun a_scanner_behind_a_full_set_of_silent_connections_still_pairs() =
        runTest {
            val offer = memberOffer(loopback())
            val lan = readCode(offer.qr).lan
            withContext(Dispatchers.Default) {
                val silent = List(8) { dial(lan)!! }
                val listening = offer.listening!!
                val accepted = async { listening.accept(offer.token) }
                delay(200)
                val real = dial(lan)!!
                real.send(firstMessage(offer, 8))
                val (pipe, key) = accepted.await()
                assertArrayEquals(agreementPublicKey(ByteArray(32) { 8 }), key)
                (silent + real + pipe).forEach { it.close() }
                listening.stop()
            }
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
