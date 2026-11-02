package io.github.aedev.flow.sync2.linking

import io.github.aedev.flow.sync.qr.LocalNetworkAddress
import io.github.aedev.flow.sync.transport.LanAddress
import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync.transport.WsClient
import io.github.aedev.flow.sync.transport.WsServer
import io.github.aedev.flow.sync2.data.LanAddressRow
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.lan.MAX_LAN_FRAME
import io.github.aedev.flow.sync2.store.Mailbox
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.util.Locale

private const val PAIR_PATH = "/flow-sync/2/pair"
private const val CONNECT_TIMEOUT_MS = 3_000L
private const val CLOSE_WAIT_MS = 5_000L
private const val POLL_INTERVAL_MS = 2_000L
internal const val SLOT_LIFETIME_MS = 180_000L

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
 * WebSocket. Either way the scanner's ephemeral key goes first, in the clear.
 */
internal interface Pipe {
    suspend fun send(bytes: ByteArray)

    suspend fun receive(): ByteArray

    /** The scanner's first step: its ephemeral public key. */
    suspend fun sendKey(key: ByteArray)

    /** The shower's first step: the scanner's key, which it needs to open anything. */
    suspend fun receiveKey(): ByteArray
}

/** A slot of a store's pairing mailbox. The scanner's key is the prefix of its first message. */
internal class MailboxPipe(
    private val mailbox: Mailbox,
    private val slot: String,
    shower: Boolean,
) : Pipe {
    private val sendDirection = if (shower) 'a' else 'b'
    private val receiveDirection = if (shower) 'b' else 'a'
    private var sent = 0L
    private var received = 0L
    private var prefix = ByteArray(0)
    private var pending: ByteArray? = null

    private fun name(
        direction: Char,
        seq: Long,
    ) = "%c-%016x.p".format(Locale.ROOT, direction, seq)

    override suspend fun send(bytes: ByteArray) {
        mailbox.putMessage(slot, name(sendDirection, sent), prefix + bytes)
        prefix = ByteArray(0)
        sent++
    }

    override suspend fun receive(): ByteArray {
        pending?.let {
            pending = null
            return it
        }
        val name = name(receiveDirection, received)
        var waited = 0L
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

    override suspend fun sendKey(key: ByteArray) {
        prefix = key
    }

    override suspend fun receiveKey(): ByteArray {
        val first = receive()
        if (first.size <= KEY_SIZE) throw LinkedException.Unexpected()
        pending = first.copyOfRange(KEY_SIZE, first.size)
        return first.copyOfRange(0, KEY_SIZE)
    }
}

/** A WebSocket to the other device on the same network, at [host]. The scanner's key is its own first frame. */
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

    override suspend fun sendKey(key: ByteArray) = send(key)

    override suspend fun receiveKey(): ByteArray = receive().takeIf { it.size == KEY_SIZE } ?: throw LinkedException.Unexpected()

    fun close() = connection.close()

    /** Waits for the other side to close, so a last message isn't lost when this side stops serving. */
    suspend fun awaitClosed() {
        withTimeoutOrNull(CLOSE_WAIT_MS) { while (connection.receive() != null) Unit }
    }
}

/** Where this device listens while it shows a code. */
class Listening internal constructor(
    private val server: WsServer,
    val addresses: List<LanEndpoint>,
) {
    /** Waits for one scanner and reads its key; other connections stay unanswered. */
    internal suspend fun accept(): Pair<LanPipe, ByteArray> {
        val connection =
            try {
                withTimeout(SLOT_LIFETIME_MS) { server.awaitConnection() }
            } catch (_: TimeoutCancellationException) {
                throw LinkedException.TimedOut()
            }
        val pipe = LanPipe(connection, connection.remoteHost?.let(LocalNetworkAddress::urlHost))
        return pipe to pipe.receiveKey()
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
    if (addresses.isEmpty()) return null
    val server = WsServer(PAIR_PATH, MAX_LAN_FRAME)
    val port = server.start()
    return Listening(server, addresses.map { LanEndpoint(it, port) })
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
