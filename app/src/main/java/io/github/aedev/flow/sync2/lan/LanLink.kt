package io.github.aedev.flow.sync2.lan

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync2.crypto.LanRole
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.Purpose
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.encodePayload
import io.github.aedev.flow.sync2.crypto.lanFrameAad
import io.github.aedev.flow.sync2.crypto.lanHelloAad
import io.github.aedev.flow.sync2.crypto.lanSessionKeys
import io.github.aedev.flow.sync2.crypto.lanTranscript
import io.github.aedev.flow.sync2.crypto.open
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.crypto.seal
import io.github.aedev.flow.sync2.crypto.sign
import io.github.aedev.flow.sync2.crypto.u32
import io.github.aedev.flow.sync2.crypto.u64
import io.github.aedev.flow.sync2.crypto.verify
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.b64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer

private const val NONCE_SIZE = 32
private const val SIGNATURE_SIZE = 64

/** The most a LAN frame's payload may hold (spec/live.md, Session). */
const val MAX_LAN_PAYLOAD = 4 * 1024 * 1024

/** Room for a payload's gzip, padding (at most 1/32 at this size), box and `seq`. */
const val MAX_LAN_FRAME = MAX_LAN_PAYLOAD + MAX_LAN_PAYLOAD / 16L

/**
 * A LAN session between two members (spec/live.md, Session), once the hello has checked both: each
 * frame is `u64(seq) ‖ Box(K, "FLOW-SYNC/2 lan" ‖ u64(seq), payload)`, counted strictly in each direction.
 */
class LanLink internal constructor(
    private val connection: SyncConnection,
    /** The other member's device id, hex. */
    val peer: String,
    /** The port the connecting member listens on, as its hello says. */
    val peerPort: Int?,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
) {
    private val sending = Mutex()
    private var sent = 0L
    private var received = 0L

    suspend fun send(content: ByteArray) =
        sending.withLock {
            connection.send(u64(sent) + seal(sendKey, lanFrameAad(sent), encodePayload(content)))
            sent++
        }

    /** The next frame's content, or null once the other side closed. */
    suspend fun receive(): ByteArray? {
        val frame = connection.receive() ?: return null
        if (frame.size < 8 || ByteBuffer.wrap(frame, 0, 8).long != received) reject(ProtocolError.REPLAY)
        val content = decodePayload(open(receiveKey, lanFrameAad(received), frame.copyOfRange(8, frame.size)))
        if (content.size > MAX_LAN_PAYLOAD) reject(ProtocolError.LENGTH)
        received++
        return content
    }

    fun close() = connection.close()
}

private fun hello(
    epoch: Int,
    lanKey: ByteArray,
    message: Int,
    content: JsonObject,
): ByteArray = u32(epoch) + seal(lanKey, lanHelloAad(message), encodePayload(SyncSerialization.canonicalJson(content).toByteArray()))

private fun opened(
    frame: ByteArray?,
    lanKey: ByteArray,
    message: Int,
): JsonObject {
    if (frame == null || frame.size < 4) throw LinkedException.Unexpected()
    val content = decodePayload(open(lanKey, lanHelloAad(message), frame.copyOfRange(4, frame.size)))
    return runCatching { Json.parseToJsonElement(content.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
        ?: throw LinkedException.Unexpected()
}

private fun JsonObject.field(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull ?: throw LinkedException.Unexpected()

/** The 32 random bytes each side's hello carries. */
private fun JsonObject.nonce(): ByteArray = b64(field("n"), NONCE_SIZE) ?: throw LinkedException.Unexpected()

private fun JsonObject.signature(): ByteArray = b64(field("sig"), SIGNATURE_SIZE) ?: reject(ProtocolError.SIGNATURE)

/** The signing key of a current device other than this one, or a rejection. */
private fun Session.memberKey(did: String): ByteArray =
    state.members[did]?.takeIf { it.role == Role.DEVICE && did != me }?.sig ?: reject(ProtocolError.SENDER)

/** The connecting side: hello sealed under this device's current epoch, naming the [port] this device listens on. */
suspend fun connectLink(
    connection: SyncConnection,
    session: Session,
    port: Int? = null,
): LanLink {
    val epoch = session.state.epoch
    val lanKey = purposeKey(session.currentKey(), session.group, epoch, Purpose.LAN)
    val clientNonce = SyncCrypto.randomBytes(NONCE_SIZE)
    val client = session.device.did
    connection.send(
        hello(
            epoch,
            lanKey,
            1,
            JsonObject(
                listOfNotNull(
                    "did" to JsonPrimitive(session.me),
                    "n" to JsonPrimitive(SyncBytes.b64urlEncode(clientNonce)),
                    port?.let { "p" to JsonPrimitive(it) },
                ).toMap(),
            ),
        ),
    )

    val second = opened(connection.receive(), lanKey, 2)
    val serverDid = second.field("did")
    val serverKey = session.memberKey(serverDid)
    val server = SyncBytes.fromHex(serverDid)
    val serverNonce = second.nonce()
    val transcript = lanTranscript(LanRole.SERVER, clientNonce, serverNonce, client, server)
    if (!verify(serverKey, transcript, second.signature())) reject(ProtocolError.SIGNATURE)

    val proof = sign(session.device.sigSeed, lanTranscript(LanRole.CLIENT, clientNonce, serverNonce, client, server))
    connection.send(hello(epoch, lanKey, 3, JsonObject(mapOf("sig" to JsonPrimitive(SyncBytes.b64urlEncode(proof))))))
    val keys = lanSessionKeys(lanKey, clientNonce, serverNonce, client, server)
    return LanLink(connection, serverDid, null, keys.clientToServer, keys.serverToClient)
}

/** The accepting side: answers under the epoch the client named, if this device holds its key. */
suspend fun acceptLink(
    connection: SyncConnection,
    session: Session,
): LanLink {
    val first = connection.receive() ?: throw LinkedException.Unexpected()
    if (first.size < 4) throw LinkedException.Unexpected()
    val epoch = ByteBuffer.wrap(first, 0, 4).int
    val lanKey = purposeKey(session.epochKey(epoch), session.group, epoch, Purpose.LAN)
    val hello = opened(first, lanKey, 1)
    val clientDid = hello.field("did")
    val clientKey = session.memberKey(clientDid)
    val client = SyncBytes.fromHex(clientDid)
    val clientNonce = hello.nonce()
    val serverNonce = SyncCrypto.randomBytes(NONCE_SIZE)
    val server = session.device.did
    val proof = sign(session.device.sigSeed, lanTranscript(LanRole.SERVER, clientNonce, serverNonce, client, server))
    val second =
        JsonObject(
            mapOf(
                "did" to JsonPrimitive(session.me),
                "n" to JsonPrimitive(SyncBytes.b64urlEncode(serverNonce)),
                "sig" to JsonPrimitive(SyncBytes.b64urlEncode(proof)),
            ),
        )
    connection.send(hello(epoch, lanKey, 2, second))

    val third = opened(connection.receive(), lanKey, 3)
    val transcript = lanTranscript(LanRole.CLIENT, clientNonce, serverNonce, client, server)
    if (!verify(clientKey, transcript, third.signature())) reject(ProtocolError.SIGNATURE)
    val keys = lanSessionKeys(lanKey, clientNonce, serverNonce, client, server)
    return LanLink(connection, clientDid, (hello["p"] as? JsonPrimitive)?.intOrNull, keys.serverToClient, keys.clientToServer)
}
