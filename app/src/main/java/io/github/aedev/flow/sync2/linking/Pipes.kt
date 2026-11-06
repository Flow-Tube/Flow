package io.github.aedev.flow.sync2.linking

import io.github.aedev.flow.sync.qr.LocalNetworkAddress
import io.github.aedev.flow.sync.transport.LanAddress
import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync.transport.WsClient
import io.github.aedev.flow.sync.transport.WsServer
import io.github.aedev.flow.sync2.crypto.pairingProof
import io.github.aedev.flow.sync2.data.LanAddressRow
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.lan.MAX_LAN_FRAME
import io.github.aedev.flow.sync2.store.Mailbox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.Locale

private const val PAIR_PATH = "/flow-sync/2/pair"
private const val CONNECT_TIMEOUT_MS = 3_000L
private const val CLOSE_WAIT_MS = 5_000L
private const val POLL_INTERVAL_MS = 2_000L
internal const val SLOT_LIFETIME_MS = 180_000L

/** How long a connection has to send its first message before it is dropped. */
private const val FIRST_MESSAGE_MS = 5_000L

/** Connections waited on at once, and as many more left unserved until one ends; any further ones are closed. */
private const val WAITING_CONNECTIONS = 8
private const val PROOF_SIZE = 16

/** Addresses a code lists, best first, as many as FLOW-SYNC/1 codes carry. */
private const val LAN_ADDRESSES = 3
internal const val KEY_SIZE = 32

/** One entry of a code's `l`: where the device showing it listens. */
@Serializable
data class LanEndpoint(
    val a: String,
    val p: Int,
)

/**
 * How pairing messages travel (spec/protocol.md, Channel): through a store's mailbox, or over a LAN
 * WebSocket. Either way the scanner's key goes first and the shower's second, in the clear.
 */
internal interface Pipe {
    suspend fun send(bytes: ByteArray)

    suspend fun receive(): ByteArray
}

/** The scanner's key from its first message, or null when the message doesn't prove it read the code. */
internal fun scannerKey(
    first: ByteArray,
    token: ByteArray,
): ByteArray? {
    if (first.size != KEY_SIZE + PROOF_SIZE) return null
    val key = first.copyOfRange(0, KEY_SIZE)
    return key.takeIf { MessageDigest.isEqual(first.copyOfRange(KEY_SIZE, first.size), pairingProof(token, it)) }
}

/** A slot of a store's pairing mailbox: one file per message, numbered from 0 in each direction. */
internal class MailboxPipe(
    private val mailbox: Mailbox,
    private val slot: String,
    shower: Boolean,
) : Pipe {
    private val sendDirection = if (shower) 'a' else 'b'
    private val receiveDirection = if (shower) 'b' else 'a'
    private var sent = 0L
    private var received = 0L
    private var waited = 0L

    private fun name(
        direction: Char,
        seq: Long,
    ) = "%c-%016x.p".format(Locale.ROOT, direction, seq)

    override suspend fun send(bytes: ByteArray) {
        mailbox.putMessage(slot, name(sendDirection, sent), bytes)
        sent++
    }

    override suspend fun receive(): ByteArray {
        val name = name(receiveDirection, received)
        waited = 0L
        while (true) {
            mailbox.getMessage(slot, name)?.let {
                received++
                return it
            }
            if (waited >= SLOT_LIFETIME_MS) throw LinkedException.TimedOut()
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
    }

    /** The shower's first step. A wrong proof ends this path but not the code, so it goes quiet and the LAN stays open. */
    suspend fun awaitScanner(token: ByteArray): ByteArray {
        scannerKey(receive(), token)?.let { return it }
        delay(SLOT_LIFETIME_MS - waited)
        throw LinkedException.TimedOut()
    }
}

/** A WebSocket to the other device on the same network, at [host]: one frame per message. */
internal class LanPipe(
    private val connection: SyncConnection,
    val host: String?,
) : Pipe {
    override suspend fun send(bytes: ByteArray) = connection.send(bytes)

    override suspend fun receive(): ByteArray =
        try {
            // A closed connection: the other device gave up, often on an error of its own.
            withTimeout(SLOT_LIFETIME_MS) { connection.receive() } ?: throw LinkedException.Stopped()
        } catch (_: TimeoutCancellationException) {
            throw LinkedException.TimedOut()
        }

    fun close() = connection.close()

    /** Waits for the other side to close, so a last message isn't lost when this side stops serving. */
    suspend fun awaitClosed() {
        withTimeoutOrNull(CLOSE_WAIT_MS) { while (connection.receive() != null) Unit }
    }
}

/** Where this device listens while it shows a code. */
class Listening internal constructor(
    private val server: WsServer,
    private val connections: ReceiveChannel<SyncConnection>,
    val addresses: List<LanEndpoint>,
) {
    /**
     * Waits on connections at once, each with a few seconds to prove it read the code, so a silent
     * one can't hold up the real scanner. The first to prove it wins and every other is dropped.
     */
    internal suspend fun accept(token: ByteArray): Pair<LanPipe, ByteArray> =
        try {
            withTimeout(SLOT_LIFETIME_MS) {
                coroutineScope {
                    val winner = CompletableDeferred<Pair<SyncConnection, ByteArray>>()
                    val waiting = Semaphore(WAITING_CONNECTIONS)
                    val serving =
                        launch {
                            for (connection in connections) {
                                waiting.acquire()
                                launch {
                                    var won = false
                                    try {
                                        val first = withTimeoutOrNull(FIRST_MESSAGE_MS) { connection.receive() }
                                        won = first?.let { scannerKey(it, token) }?.let { winner.complete(connection to it) } == true
                                    } finally {
                                        if (!won) connection.close()
                                        waiting.release()
                                    }
                                }
                            }
                        }
                    val (connection, key) = winner.await()
                    serving.cancel()
                    LanPipe(connection, connection.remoteHost?.let(LocalNetworkAddress::urlHost)) to key
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw LinkedException.TimedOut()
        } finally {
            connections.cancel()
        }

    fun stop() = server.stop()
}

/** Listens on every interface and lists the local addresses another device may reach; null without any. */
suspend fun listen(): Listening? {
    val addresses =
        LanAddress
            .candidates()
            .map { it.ip }
            .filter(LocalNetworkAddress::isLocal)
            .take(LAN_ADDRESSES)
    return if (addresses.isEmpty()) null else listenOn(addresses)
}

/** Listens on every interface and lists [addresses] as where to reach this device. */
internal suspend fun listenOn(addresses: List<String>): Listening {
    val connections = Channel<SyncConnection>(WAITING_CONNECTIONS, onUndeliveredElement = { it.close() })
    val server =
        WsServer(PAIR_PATH, MAX_LAN_FRAME) { connection ->
            if (connections.trySend(connection).isFailure) connection.close()
        }
    val port = server.start()
    return Listening(server, connections, addresses.map { LanEndpoint(it, port) })
}

/** Dials the addresses a code lists, in order, each for a few seconds. */
internal suspend fun dial(addresses: List<LanEndpoint>): LanPipe? {
    val client = WsClient(OkHttpClient(), MAX_LAN_FRAME)
    for (address in addresses.filter { LocalNetworkAddress.isLocal(it.a) }) {
        val host = LocalNetworkAddress.urlHost(address.a)
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { runCatching { client.connect("ws://$host:${address.p}$PAIR_PATH") }.getOrNull() }
            ?.let { return LanPipe(it, host) }
    }
    return null
}

/** Remembers [did] at this pipe's host and the LAN port [p] it named, so the two meet without mDNS (spec/protocol.md, Messages). */
internal suspend fun LanPipe.remember(
    db: LinkedDatabase,
    did: String,
    p: Int?,
) {
    if (host != null && p != null) db.lan().save(LanAddressRow(did, host, p))
}
