package io.github.aedev.flow.sync.protocol

import io.github.aedev.flow.sync.apply.PeerInfo
import io.github.aedev.flow.sync.apply.ReceivedCollection
import io.github.aedev.flow.sync.crypto.DirectionalKeys
import io.github.aedev.flow.sync.transport.SyncConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.crypto.AEADBadTagException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

enum class SyncRole { SENDER, RECEIVER }

/** Summary shown to the receiver before it consents to merge. */
data class TransferSummary(
    val collections: List<String>,
)

/** Final outcome of a session: per-collection merge stats + the peer that was synced with. */
data class SyncResult(
    val peerName: String,
    val stats: Map<String, ApplyStats>,
)

interface ProtocolCallbacks {
    suspend fun onPeerHello(peer: PeerInfo) {}

    /** Both sides display the 6-digit SAS; return true to proceed (defends against MITM). */
    suspend fun confirmSas(sas: String): Boolean

    /** Receiver-only: "peer wants to send X — merge?" Return true to accept. */
    suspend fun confirmConsent(summary: TransferSummary): Boolean

    fun onProgress(
        collection: String,
        done: Int,
        total: Int,
    ) {}
}

/**
 * A session failure on this side. [code] uses the wire codes shared with the desktop
 * (`timeout`, `hash_mismatch`, `apply_failed`, ...); when [tellPeer] is set the peer is sent an
 * `ERROR` frame with it before the socket closes, so it can show what happened instead of
 * "connection closed".
 */
class SyncProtocolException(
    message: String,
    val code: String,
    val tellPeer: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The peer ended the session with an `ERROR` frame. */
class SyncPeerException(
    val code: String,
    val detail: String,
) : Exception("peer error [$code]: $detail")

/**
 * Drives one FLOW-SYNC/1 session over a [SyncConnection]. Strict lockstep with an
 * independent monotonic `seq` per direction: handshake → capability/selection
 * negotiation → SAS → consent → streamed transfer → atomic apply. v1 is one-way per session.
 *
 * Transport-symmetric: [isHost] only decides which directional key seals/opens and who speaks
 * first in each exchange; either role can be the SENDER or RECEIVER.
 */
class SyncProtocol(
    private val conn: SyncConnection,
    private val isHost: Boolean,
    private val keys: DirectionalKeys,
    private val sessionId: ByteArray,
    private val sasDigits: String,
    private val localHello: Hello,
    private val localCaps: Capabilities,
    private val localSelection: Selection,
    private val callbacks: ProtocolCallbacks,
    private val buildPayload: suspend (collections: List<String>) -> Map<String, CollectionWire>,
    private val applyReceived: suspend (peer: PeerInfo, payload: Map<String, ReceivedCollection>) -> Map<String, ApplyStats>,
) {
    private var sendSeq = 0L
    private var expectedRecvSeq = 0L
    private val json = FrameJson.json

    suspend fun run(role: SyncRole): SyncResult =
        try {
            session(role)
        } catch (e: SyncProtocolException) {
            if (e.tellPeer) sendError(e.code, e.message ?: e.code)
            throw e
        }

    private suspend fun session(role: SyncRole): SyncResult {
        val peer = handshake()
        callbacks.onPeerHello(peer)
        val peerCaps = exchangeCapabilities()
        val peerSelection = exchangeSelection(peerCaps)

        if (!callbacks.confirmSas(sasDigits)) {
            throw SyncProtocolException("SAS not confirmed — possible MITM", CODE_SAS_REJECTED, tellPeer = true)
        }

        return when (role) {
            SyncRole.SENDER -> runSender(peer, peerSelection, peerCaps)
            SyncRole.RECEIVER -> runReceiver(peer)
        }
    }

    // --- phases ---

    private suspend fun handshake(): PeerInfo =
        if (isHost) {
            val hello = decode(expect(FrameType.HELLO, NETWORK_WAIT), Hello.serializer())
            if (hello.protocol != 1) {
                throw SyncProtocolException(
                    "unsupported peer protocol ${hello.protocol}",
                    CODE_UNSUPPORTED_VERSION,
                    tellPeer = true,
                )
            }
            sendFrame(FrameType.HELLO_ACK, HelloAck.serializer(), localHello.toAck())
            PeerInfo(hello.deviceId, hello.deviceName, hello.platform)
        } else {
            sendFrame(FrameType.HELLO, Hello.serializer(), localHello)
            val ack = decode(expect(FrameType.HELLO_ACK, NETWORK_WAIT), HelloAck.serializer())
            PeerInfo(ack.deviceId, ack.deviceName, ack.platform)
        }

    private suspend fun exchangeCapabilities(): Capabilities =
        if (!isHost) {
            sendFrame(FrameType.CAPABILITIES, Capabilities.serializer(), localCaps)
            decode(expect(FrameType.CAPABILITIES, NETWORK_WAIT), Capabilities.serializer())
        } else {
            val peer = decode(expect(FrameType.CAPABILITIES, NETWORK_WAIT), Capabilities.serializer())
            sendFrame(FrameType.CAPABILITIES, Capabilities.serializer(), localCaps)
            peer
        }

    /**
     * Only names the peer advertised go into our SELECTION. Released desktops parse the list into a
     * fixed set of collections and abort the whole session on a name they don't know, which is what
     * `notes` did to every desktop pairing.
     */
    private suspend fun exchangeSelection(peerCaps: Capabilities): Selection {
        val outgoing =
            Selection(
                send = localSelection.send.filter { canConsume(peerCaps, it) },
                accept = localSelection.accept.filter { canProduce(peerCaps, it) },
            )
        return if (!isHost) {
            sendFrame(FrameType.SELECTION, Selection.serializer(), outgoing)
            decode(expect(FrameType.SELECTION, NETWORK_WAIT), Selection.serializer())
        } else {
            val peer = decode(expect(FrameType.SELECTION, NETWORK_WAIT), Selection.serializer())
            sendFrame(FrameType.SELECTION, Selection.serializer(), outgoing)
            peer
        }
    }

    /**
     * Sender side of the canonical sequence (must mirror the desktop authority exactly):
     * `MANIFEST` (one aggregate frame) → `CONSENT` from the receiver → per-collection
     * `CHUNK*`/`CHUNK_ACK`/`COMPLETE` stream → one aggregate `APPLY_RESULT`.
     */
    private suspend fun runSender(
        peer: PeerInfo,
        peerSelection: Selection,
        peerCaps: Capabilities,
    ): SyncResult {
        val toSend = localSelection.send.filter { it in peerSelection.accept && canConsume(peerCaps, it) }
        val payload = buildPayload(toSend)

        // 1. Single aggregate MANIFEST describing everything we are about to stream.
        sendFrame(
            FrameType.MANIFEST,
            Manifest.serializer(),
            Manifest(payload.mapValues { (_, w) -> ManifestEntry(w.recordCount, w.lines.sumOf { it.length.toLong() }, w.hash) }),
        )

        // 2. CONSENT (one-way, receiver→sender): the receiver is the SOLE merge gate. The sender
        // sends no consent of its own — the user authorizes the send by verifying the SAS here.
        val consent = decode(expect(FrameType.CONSENT, USER_WAIT), Consent.serializer())
        if (!consent.accepted) throw SyncProtocolException("peer declined the merge", CODE_PEER_DECLINED, tellPeer = false)

        // 3. Stream each collection: CHUNK* → CHUNK_ACK → COMPLETE.
        for ((collection, wire) in payload) {
            val chunks = wire.lines.chunked(CHUNK_SIZE)
            if (chunks.isEmpty()) {
                sendChunk(collection, 0, true, emptyList())
                expect(FrameType.CHUNK_ACK, NETWORK_WAIT)
            } else {
                chunks.forEachIndexed { i, lines ->
                    sendChunk(collection, i, i == chunks.lastIndex, lines)
                    expect(FrameType.CHUNK_ACK, NETWORK_WAIT)
                    callbacks.onProgress(collection, i + 1, chunks.size)
                }
            }
            sendFrame(FrameType.COMPLETE, Complete.serializer(), Complete(collection, wire.recordCount, wire.hash))
        }

        // 4. One aggregate APPLY_RESULT from the receiver.
        val result = decode(expect(FrameType.APPLY_RESULT, APPLY_WAIT), ApplyResult.serializer())
        return SyncResult(peer.deviceName, result.collections)
    }

    /**
     * Receiver side: read the single aggregate `MANIFEST` first, gate on the user, send `CONSENT`,
     * consume the per-collection stream, apply it, then emit one aggregate `APPLY_RESULT`.
     */
    private suspend fun runReceiver(peer: PeerInfo): SyncResult {
        // An Android sender asks its user to confirm the code before it sends the MANIFEST.
        val manifest = decode(expect(FrameType.MANIFEST, USER_WAIT), Manifest.serializer())
        val incoming = manifest.collections.keys.filter { it in localSelection.accept }

        if (!callbacks.confirmConsent(TransferSummary(incoming))) {
            sendFrame(FrameType.CONSENT, Consent.serializer(), Consent(false))
            throw SyncProtocolException("merge declined by user", CODE_DECLINED, tellPeer = false)
        }
        sendFrame(FrameType.CONSENT, Consent.serializer(), Consent(true))

        val received = receiveStream(manifest)
        val stats =
            try {
                applyReceived(peer, received)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw SyncProtocolException("couldn't save the received data: ${e.message}", CODE_APPLY_FAILED, tellPeer = true, e)
            }
        sendFrame(FrameType.APPLY_RESULT, ApplyResult.serializer(), ApplyResult(stats))
        return SyncResult(peer.deviceName, stats)
    }

    /**
     * Receive every collection the MANIFEST lists, routed by the name each chunk carries. Senders
     * disagree on stream order (released desktops follow their own selection order), so the
     * position of a collection in the stream says nothing about which one it is.
     */
    private suspend fun receiveStream(manifest: Manifest): Map<String, ReceivedCollection> {
        val remaining = LinkedHashMap(manifest.collections)
        val received = LinkedHashMap<String, ReceivedCollection>()
        while (remaining.isNotEmpty()) {
            var (header, body) = parseChunk(expect(FrameType.CHUNK, CHUNK_WAIT))
            val name = header.collection
            val entry =
                remaining.remove(name) ?: throw protocolError("received $name, which the manifest doesn't list or which already arrived")
            val lines = ArrayList<String>()
            while (true) {
                if (header.collection != name) throw protocolError("a chunk for ${header.collection} arrived in the middle of $name")
                lines.addAll(body)
                sendFrame(FrameType.CHUNK_ACK, ChunkAck.serializer(), ChunkAck(name, header.seq))
                callbacks.onProgress(name, lines.size, entry.records)
                if (header.last) break
                val next = parseChunk(expect(FrameType.CHUNK, CHUNK_WAIT))
                header = next.first
                body = next.second
            }
            val complete = decode(expect(FrameType.COMPLETE, NETWORK_WAIT), Complete.serializer())
            if (complete.collection != name) throw protocolError("COMPLETE for ${complete.collection} ended $name")
            val computed = SyncSerialization.collectionHash(lines)
            if (computed != complete.hash || computed != entry.hash) {
                throw SyncProtocolException("payload hash mismatch for $name", CODE_HASH_MISMATCH, tellPeer = true)
            }
            received[name] = ReceivedCollection(lines, complete.hash)
        }
        return received
    }

    // --- framing helpers ---

    /**
     * A collection whose topic keys follow a key spec goes only to a peer that writes them the same
     * way: a reader of another spec would file them as new topics beside its own (#907).
     */
    private fun canConsume(
        caps: Capabilities,
        collection: String,
    ): Boolean {
        val peer = caps.collections[collection] ?: return false
        val local = localCaps.collections[collection]
        if (local != null && local.keySpec > 0 && (peer.keySpec != local.keySpec || peer.keyLanguage != local.keyLanguage)) return false
        return peer.consume
    }

    private fun canProduce(
        caps: Capabilities,
        collection: String,
    ): Boolean = caps.collections[collection]?.produce ?: false

    private fun protocolError(message: String) = SyncProtocolException(message, CODE_PROTOCOL, tellPeer = true)

    private suspend fun <T> sendFrame(
        type: Byte,
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
    ) {
        sendRaw(type, json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
    }

    private suspend fun sendRaw(
        type: Byte,
        plaintext: ByteArray,
    ) {
        val msg = SyncCodec.seal(keys.sealKey(isHost), sessionId, type, sendSeq, plaintext)
        sendSeq++
        conn.send(msg)
    }

    private suspend fun sendChunk(
        collection: String,
        chunkSeq: Int,
        last: Boolean,
        lines: List<String>,
    ) {
        sendRaw(FrameType.CHUNK, ChunkFraming.encode(ChunkHeader(collection, chunkSeq, last), lines))
    }

    private fun parseChunk(plaintext: ByteArray): Pair<ChunkHeader, List<String>> = ChunkFraming.decode(plaintext)

    /** The next frame's plaintext, which must be of [type] and arrive within [wait]. */
    private suspend fun expect(
        type: Byte,
        wait: Duration,
    ): ByteArray {
        val frame =
            withTimeoutOrNull(wait) { recvFrame() }
                ?: throw SyncProtocolException("timed out waiting for ${FrameType.name(type)}", CODE_TIMEOUT, tellPeer = true)
        if (frame.frameType == FrameType.ERROR) {
            val err = decode(frame.plaintext, ErrorFrame.serializer())
            throw SyncPeerException(err.code, err.message)
        }
        if (frame.frameType != type) {
            throw protocolError("expected ${FrameType.name(type)}, got ${FrameType.name(frame.frameType)}")
        }
        return frame.plaintext
    }

    private suspend fun recvFrame(): SyncCodec.Opened {
        val msg =
            conn.receive() ?: throw SyncProtocolException("connection closed by peer", CODE_PEER_CLOSED, tellPeer = false)
        val opened =
            try {
                SyncCodec.open(keys.openKey(isHost), sessionId, msg)
            } catch (e: AEADBadTagException) {
                throw SyncProtocolException("authentication failed (wrong key or tampered frame)", CODE_PROTOCOL, tellPeer = false, e)
            }
        if (opened.seq != expectedRecvSeq) {
            throw SyncProtocolException("out-of-order frame: got ${opened.seq}, expected $expectedRecvSeq", CODE_PROTOCOL, tellPeer = false)
        }
        expectedRecvSeq++
        return opened
    }

    private suspend fun sendError(
        code: String,
        message: String,
    ) {
        runCatching { sendFrame(FrameType.ERROR, ErrorFrame.serializer(), ErrorFrame(code, message)) }
    }

    private fun <T> decode(
        bytes: ByteArray,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T = json.decodeFromString(serializer, String(bytes, Charsets.UTF_8))

    private fun Hello.toAck() = HelloAck(deviceId, deviceName, platform, appVersion, sasConfirmRequired = true)

    companion object {
        private const val CHUNK_SIZE = 1500

        /** A frame the peer sends straight back without involving its user. */
        private val NETWORK_WAIT = 60.seconds

        /** A chunk mid-stream: the sender may be reading a large collection from disk. */
        private val CHUNK_WAIT = 90.seconds

        /** A frame the peer only sends after its user confirms the code or the merge. */
        private val USER_WAIT = 10.minutes

        /** `APPLY_RESULT`: the receiver merges everything into its database first. */
        private val APPLY_WAIT = 10.minutes

        const val CODE_SAS_REJECTED = "sas_rejected"
        const val CODE_HASH_MISMATCH = "hash_mismatch"
        const val CODE_TIMEOUT = "timeout"
        const val CODE_UNSUPPORTED_VERSION = "unsupported_version"
        const val CODE_APPLY_FAILED = "apply_failed"
        const val CODE_PROTOCOL = "protocol_error"
        const val CODE_PEER_CLOSED = "peer_closed"
        const val CODE_DECLINED = "declined"
        const val CODE_PEER_DECLINED = "peer_declined"
    }
}
