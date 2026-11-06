package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.changes.applyBatches
import io.github.aedev.flow.sync2.crypto.agreementPublicKey
import io.github.aedev.flow.sync2.crypto.pairingProof
import io.github.aedev.flow.sync2.crypto.pairingSlot
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.Channel
import io.github.aedev.flow.sync2.linking.Code
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.MailboxPipe
import io.github.aedev.flow.sync2.linking.Offer
import io.github.aedev.flow.sync2.linking.Pipe
import io.github.aedev.flow.sync2.linking.StoreLocation
import io.github.aedev.flow.sync2.linking.addDevice
import io.github.aedev.flow.sync2.linking.join
import io.github.aedev.flow.sync2.linking.newOffer
import io.github.aedev.flow.sync2.linking.readCode
import io.github.aedev.flow.sync2.linking.scannerKey
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val SHOWER_FILE_0 = "a-0000000000000000.p"
private const val SHOWER_FILE_1 = "a-0000000000000001.p"

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

    /** Stops at the shower's key, and records what the scanner sent until then. */
    private class AnswersOnce(
        private val answer: ByteArray,
    ) : Pipe {
        val sent = ArrayList<ByteArray>()

        override suspend fun send(bytes: ByteArray) {
            sent += bytes
        }

        override suspend fun receive() = answer
    }

    private fun memberCode(offer: Offer = newOffer(StoreLocation("server", "memory"), "Desktop A", null)) =
        readCode(offer.qr) as Code.Member

    @Test
    fun a_shower_key_that_does_not_match_the_code_stops_the_scanner_before_any_box() =
        runTest {
            val otherKey = agreementPublicKey(ByteArray(32) { 7 })
            for (answer in listOf(otherKey, otherKey.copyOf(31), otherKey + 1)) {
                val pipe = AnswersOnce(answer)
                val error = runCatching { Channel.scanner(pipe, memberCode()) }.exceptionOrNull()
                assertTrue("$error", error is LinkedException.Declined)
                assertEquals(1, pipe.sent.size)
            }
        }

    @Test
    fun the_scanner_goes_on_when_the_shower_key_matches_the_code() =
        runTest {
            val offer = newOffer(StoreLocation("server", "memory"), "Desktop A", null)
            val pipe = AnswersOnce(offer.public)
            val code = memberCode(offer)
            Channel.scanner(pipe, code)
            val key = scannerKey(pipe.sent.single(), offer.token)
            assertEquals(32, key?.size)
        }

    @Test
    fun a_code_reveals_its_key_once() {
        val offer = newOffer(StoreLocation("server", "memory"), "Desktop A", null)
        assertArrayEquals(offer.public, offer.reveal())
        assertTrue(runCatching { offer.reveal() }.exceptionOrNull() is LinkedException.Unexpected)
    }

    @Test
    fun a_second_scanner_on_the_mailbox_gets_nothing_and_a_failed_attempt_ends_the_code() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            val offer = newOffer(StoreLocation("server", "memory"), "Desktop A", null)
            val slot = SyncBytes.toHex(pairingSlot(offer.token))
            val added = async { runCatching { addDevice(session, g.store, offer, g.a, LINK) { true } } }
            testScheduler.runCurrent()

            val scanner = MailboxPipe(g.store, slot, shower = false)
            val first = agreementPublicKey(ByteArray(32) { 8 })
            scanner.send(first + pairingProof(offer.token, first))
            assertArrayEquals(offer.public, scanner.receive())

            val second = agreementPublicKey(ByteArray(32) { 9 })
            MailboxPipe(g.store, slot, shower = false).send(second + pairingProof(offer.token, second))
            delay(10_000)
            assertNull(g.store.getMessage(slot, SHOWER_FILE_1))

            scanner.send(ByteArray(100))
            assertTrue(added.await().isFailure)
            assertNull("the code is finished", g.store.getMessage(slot, SHOWER_FILE_0))
            assertTrue(runCatching { offer.reveal() }.isFailure)
        }

    @Test
    fun a_wrong_proof_on_the_mailbox_gets_no_answer() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            val offer = newOffer(StoreLocation("server", "memory"), "Desktop A", null)
            val slot = SyncBytes.toHex(pairingSlot(offer.token))
            val added = async { runCatching { addDevice(session, g.store, offer, g.a, LINK) { true } } }
            testScheduler.runCurrent()

            val key = agreementPublicKey(ByteArray(32) { 8 })
            MailboxPipe(g.store, slot, shower = false).send(key + pairingProof(ByteArray(16) { 1 }, key))
            delay(10_000)
            assertNull(g.store.getMessage(slot, SHOWER_FILE_0))
            assertTrue("${added.await()}", added.await().exceptionOrNull() is LinkedException.TimedOut)
        }

    @Test
    fun first_messages_of_any_other_size_prove_nothing() {
        val token = ByteArray(16) { 1 }
        val key = agreementPublicKey(ByteArray(32) { 8 })
        val valid = key + pairingProof(token, key)
        assertArrayEquals(key, scannerKey(valid, token))
        assertNull(scannerKey(valid + 0, token))
        assertNull(scannerKey(valid.copyOf(47), token))
        assertNull(scannerKey(key, token))
    }

    @Test
    fun a_code_with_the_key_itself_is_refused() {
        val qr = newOffer(StoreLocation("server", "memory"), "Desktop A", null).qr
        val named = qr.replace("\"c\":", "\"e\":")
        val both = qr.replace("\"c\":", "\"e\":\"AAAA\",\"c\":")
        for (code in listOf(named, both)) {
            assertTrue(code, runCatching { readCode(code) }.exceptionOrNull() is LinkedException.Unexpected)
        }
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
