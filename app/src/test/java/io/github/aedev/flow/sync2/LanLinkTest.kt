package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync2.crypto.LanRole
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.Purpose
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.encodePayload
import io.github.aedev.flow.sync2.crypto.lanFrameAad
import io.github.aedev.flow.sync2.crypto.lanHelloAad
import io.github.aedev.flow.sync2.crypto.lanTranscript
import io.github.aedev.flow.sync2.crypto.open
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.crypto.seal
import io.github.aedev.flow.sync2.crypto.sign
import io.github.aedev.flow.sync2.crypto.u32
import io.github.aedev.flow.sync2.crypto.u64
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.lan.LanLink
import io.github.aedev.flow.sync2.lan.MAX_LAN_CONTENT
import io.github.aedev.flow.sync2.lan.MAX_LAN_FRAME
import io.github.aedev.flow.sync2.lan.acceptLink
import io.github.aedev.flow.sync2.lan.connectLink
import io.github.aedev.flow.sync2.store.MAX_OBJECT_SIZE
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The hello of a LAN session (spec/live.md, Session): fields that aren't what the spec says end it before anything is signed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LanLinkTest {
    /** Hands over [frames] in order and keeps what this side sends. */
    private class Frames(
        vararg frames: ByteArray,
    ) : SyncConnection {
        private val left = ArrayDeque(frames.toList())
        val sent = ArrayList<ByteArray>()

        override suspend fun send(bytes: ByteArray) {
            sent += bytes
        }

        override suspend fun receive() = left.removeFirstOrNull()

        override fun close() = Unit
    }

    private fun LinkedGroup.hello(
        message: Int,
        content: String,
    ) = u32(0) + seal(purposeKey(epochKey, group, 0, Purpose.LAN), lanHelloAad(message), encodePayload(content.toByteArray()))

    @Test
    fun a_client_hello_without_a_32_byte_nonce_is_refused_before_the_answer() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            val b = SyncBytes.toHex(g.b.did)
            val bodies =
                listOf(
                    """{"did":"$b","n":"not base64!"}""",
                    """{"did":"$b","n":"${SyncBytes.b64urlEncode(ByteArray(16))}"}""",
                    """{"did":{}}""",
                )
            val outcomes =
                bodies.map { body ->
                    val connection = Frames(g.hello(1, body))
                    val error = runCatching { acceptLink(connection, session) }.exceptionOrNull()
                    "${error?.javaClass?.simpleName}, ${connection.sent.size} sent"
                }
            assertEquals(List(bodies.size) { "Unexpected, 0 sent" }, outcomes)
        }

    @Test
    fun a_server_that_is_not_a_member_is_refused_by_its_id() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.a)
            val n = SyncBytes.b64urlEncode(ByteArray(32))
            val error =
                runCatching {
                    connectLink(
                        Frames(g.hello(2, """{"did":"not hex","n":"$n","sig":""}""")),
                        session,
                    )
                }.exceptionOrNull()
            assertEquals(ProtocolError.SENDER, (error as ProtocolException).error)
        }

    @Test
    fun a_port_that_is_not_a_number_is_left_out() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val lanKey = purposeKey(g.epochKey, g.group, 0, Purpose.LAN)
            val clientNonce = ByteArray(32) { 1 }
            val first = g.hello(1, """{"did":"${SyncBytes.toHex(g.b.did)}","n":"${SyncBytes.b64urlEncode(clientNonce)}","p":{}}""")
            // Answers the server's hello as the client would, signing over the nonce it sent.
            val connection =
                object : SyncConnection {
                    var answered = false
                    var proof = ByteArray(0)

                    override suspend fun send(bytes: ByteArray) {
                        val second =
                            Json.parseToJsonElement(
                                decodePayload(open(lanKey, lanHelloAad(2), bytes.copyOfRange(4, bytes.size))).decodeToString(),
                            )
                        val serverNonce =
                            SyncBytes.b64urlDecode(
                                second.jsonObject
                                    .getValue("n")
                                    .jsonPrimitive.content,
                            )
                        proof = sign(g.b.sigSeed, lanTranscript(LanRole.CLIENT, clientNonce, serverNonce, g.b.did, g.a.did))
                    }

                    override suspend fun receive(): ByteArray =
                        if (answered) {
                            g.hello(3, """{"sig":"${SyncBytes.b64urlEncode(proof)}"}""")
                        } else {
                            first.also { answered = true }
                        }

                    override fun close() = Unit
                }
            assertNull(acceptLink(connection, Session.open(g.deviceDb(), g.store, g.a)).peerPort)
        }

    @Test
    fun `a full object with its header fits a frame and anything larger is refused both ways`() =
        runTest {
            val key = ByteArray(32) { 1 }
            val link = { frames: Frames -> LanLink(frames, peer = "peer", peerPort = null, sendKey = key, receiveKey = key) }

            suspend fun sending(content: ByteArray) = Frames().also { link(it).send(content) }.sent.single()

            val answer = """{"ok":true,"re":0}""".toByteArray() + '\n'.code.toByte() + SyncCrypto.randomBytes(MAX_OBJECT_SIZE)
            val frame = sending(answer)
            assertTrue(frame.size <= MAX_LAN_FRAME)
            assertArrayEquals(answer, link(Frames(frame)).receive())
            assertTrue(sending(SyncCrypto.randomBytes(MAX_LAN_CONTENT)).size <= MAX_LAN_FRAME)

            val tooLarge = ByteArray(MAX_LAN_CONTENT + 1)
            val sent = runCatching { link(Frames()).send(tooLarge) }.exceptionOrNull()
            assertEquals("length", (sent as ProtocolException).error.code)
            val received =
                runCatching {
                    link(
                        Frames(u64(0) + seal(key, lanFrameAad(0), encodePayload(tooLarge))),
                    ).receive()
                }.exceptionOrNull()
            assertEquals("length", (received as ProtocolException).error.code)
        }
}
