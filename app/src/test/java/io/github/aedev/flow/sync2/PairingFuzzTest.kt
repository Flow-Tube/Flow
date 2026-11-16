package io.github.aedev.flow.sync2

import cash.z.ecc.android.bip39.Mnemonics
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.encodePayload
import io.github.aedev.flow.sync2.crypto.pairingAad
import io.github.aedev.flow.sync2.crypto.pairingCommit
import io.github.aedev.flow.sync2.crypto.recoverySecret
import io.github.aedev.flow.sync2.crypto.recoveryWords
import io.github.aedev.flow.sync2.crypto.seal
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.linking.Channel
import io.github.aedev.flow.sync2.linking.Code
import io.github.aedev.flow.sync2.linking.LanEndpoint
import io.github.aedev.flow.sync2.linking.Pipe
import io.github.aedev.flow.sync2.linking.QrCode
import io.github.aedev.flow.sync2.linking.StoreLocation
import io.github.aedev.flow.sync2.linking.canonical
import io.github.aedev.flow.sync2.linking.newOffer
import io.github.aedev.flow.sync2.linking.readCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** What pairing reads from another device: its code, its recovery words, and its sealed messages (spec/protocol.md, Pairing). */
class PairingFuzzTest {
    private val words = Mnemonics.getCachedWords("en")

    private fun Random.base64OrText(): String = if (nextBoolean()) SyncBytes.b64urlEncode(bytes(40)) else text()

    private fun Random.code(): QrCode =
        QrCode(
            v = if (nextBoolean()) 2 else nextInt(),
            t = base64OrText(),
            c = base64OrText(),
            r = listOf("m", "n", text(2)).random(this),
            d = text(),
            s =
                StoreLocation(
                    listOf("server", "webdav", "lan", text(4)).random(this),
                    if (nextBoolean()) text() else null,
                ).takeIf { nextBoolean() },
            l = List(nextInt(3)) { LanEndpoint(text(), nextInt()) }.takeIf { nextBoolean() },
        )

    @Test
    fun arbitrary_text_is_read_cleanly() = forAll(generate = { text(64) }) { refusedCleanly<LinkedException.Unexpected> { readCode(it) } }

    @Test
    fun a_code_read_is_the_code_written() =
        forAll(generate = { code() }) { qr ->
            val code = runCatching { readCode(canonical(QrCode.serializer(), qr)) }.getOrNull() ?: return@forAll
            assertEquals(qr, code.qr)
            val token = runCatching { code.token }.getOrNull()
            assertArrayEquals(runCatching { SyncBytes.b64urlDecode(qr.t) }.getOrNull()?.takeIf { it.size == 16 }, token)
            val commitment = runCatching { code.commitment }.getOrNull()
            assertArrayEquals(runCatching { SyncBytes.b64urlDecode(qr.c) }.getOrNull()?.takeIf { it.size == 32 }, commitment)
        }

    @Test
    fun a_changed_member_code_is_read_cleanly() {
        val valid = newOffer(StoreLocation("lan"), "Desk", null).qr.toByteArray()
        forAll(generate = { mutation(valid).decodeToString() }) { text ->
            refusedCleanly<LinkedException.Unexpected> {
                val code = readCode(text)
                refusedCleanly<LinkedException.Unexpected> { code.token }
                refusedCleanly<LinkedException.Unexpected> { code.commitment }
            }
        }
    }

    @Test
    fun a_member_code_reads_back() {
        val offer = newOffer(StoreLocation("webdav", "https://dav.example/flow"), "Desk", null)
        val code = readCode(offer.qr) as Code.Member
        assertArrayEquals(offer.token, code.token)
        assertArrayEquals(pairingCommit(offer.public), code.commitment)
        assertEquals("https://dav.example/flow", code.storeUrl)
    }

    @Test
    fun recovery_words_round_trip_in_any_case_and_spacing() =
        forAll(generate = { nextBytes(32) }) { secret ->
            val words = recoveryWords(secret).uppercase().replace(" ", " \n\t ")
            assertArrayEquals(secret, recoverySecret("  $words "))
        }

    @Test
    fun valid_shorter_phrases_are_refused() =
        forAll(generate = { nextBytes(listOf(16, 20, 24, 28).random(this)) }) { assertNull(recoverySecret(recoveryWords(it))) }

    @Test
    fun any_list_of_words_is_refused_or_gives_a_secret() =
        forAll(generate = { List(nextInt(1, 30)) { words.random(this) }.joinToString(" ") }) { phrase ->
            recoverySecret(phrase)?.let { assertEquals(32, it.size) }
        }

    @Test
    fun arbitrary_text_is_not_a_recovery_secret() = forAll(generate = { text(200) }) { assertNull(recoverySecret(it)) }

    private class OnePipe(
        private val bytes: ByteArray,
    ) : Pipe {
        override suspend fun send(bytes: ByteArray) = Unit

        override suspend fun receive() = bytes
    }

    private val token = ByteArray(16) { 4 }
    private val key = ByteArray(32) { 5 }

    private suspend fun receive(bytes: ByteArray) = Channel(OnePipe(bytes), token, key, key).receive()

    @Test
    fun any_change_to_a_pairing_message_is_refused() =
        runTest {
            val valid = seal(key, pairingAad(token, 0), encodePayload("""{"type":"done"}""".toByteArray()))
            forAll(generate = { mutation(valid) }) { refused<ProtocolException> { receive(it) } }
        }

    @Test
    fun arbitrary_messages_from_the_other_device_are_refused_cleanly() =
        runTest {
            val fields = listOf("type", "sig", "kem", "meta", "p", "entry", "group", "head", "seq", "link", "store", "entries", "wrap")
            val types = listOf("join", "entry", "accept", "welcome", "done")
            forAll(generate = { shaped(fields, types) }) { content ->
                val sealed = seal(key, pairingAad(token, 0), encodePayload(content.toByteArray()))
                refusedCleanly<LinkedException.Unexpected> { receive(sealed) }
            }
        }
}
