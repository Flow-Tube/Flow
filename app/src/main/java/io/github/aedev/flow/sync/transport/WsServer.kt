package io.github.aedev.flow.sync.transport

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Show-QR/host role transport: an embedded Ktor **CIO** WebSocket server bound to `0.0.0.0:0`
 * (OS-assigned ephemeral port, read back for the QR). CIO is pure-Kotlin/coroutine so it runs on
 * Android. v1 handles a single 1↔1 session per server instance. [path] is FLOW-SYNC/1's unless
 * FLOW-SYNC/2 serves on its own; with [onConnection], every connection goes to it instead of [awaitConnection].
 * A frame over [maxFrame] bytes closes the connection.
 */
class WsServer(
    private val path: String = QrPath.PATH,
    private val maxFrame: Long = Long.MAX_VALUE,
    private val onConnection: (suspend (SyncConnection) -> Unit)? = null,
) {
    private var server: EmbeddedServer<*, *>? = null
    private val connectionDeferred = CompletableDeferred<SyncConnection>()

    /** Bind to [port], or an ephemeral port by default, and start listening; returns the bound port. */
    suspend fun start(port: Int = 0): Int {
        val e =
            embeddedServer(CIO, host = "0.0.0.0", port = port) {
                install(WebSockets) {
                    // The OkHttp client pings on its own; the desktop client does too, but a peer that
                    // vanished without closing is only noticed when our pings go unanswered.
                    pingPeriodMillis = PING_PERIOD_MS
                    timeoutMillis = PING_TIMEOUT_MS
                    maxFrameSize = maxFrame
                    // Ktor queues without a limit by default, so the socket would keep being read for nobody.
                    channels { incoming = bounded(QUEUED_FRAMES) }
                }
                routing {
                    webSocket(path) {
                        val conn = KtorServerConnection(this, call.request.local.remoteAddress)
                        val handler = onConnection
                        if (handler != null) {
                            launch { handler(conn) }
                        } else if (!connectionDeferred.complete(conn)) {
                            // One session per server: nothing would ever read this connection.
                            conn.close()
                            return@webSocket
                        }
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Binary) conn.deliver(frame.readBytes())
                            }
                        } catch (_: Throwable) {
                        } finally {
                            conn.onClosed()
                        }
                    }
                }
            }
        e.start(wait = false)
        server = e
        return e.engine
            .resolvedConnectors()
            .first()
            .port
    }

    /** Suspends until a peer connects and the handshake socket is ready. */
    suspend fun awaitConnection(): SyncConnection = connectionDeferred.await()

    fun stop() {
        runCatching { server?.stop(0, 0) }
        server = null
    }

    private object QrPath {
        const val PATH = "/flow-sync"
    }

    private companion object {
        const val PING_PERIOD_MS = 15_000L
        const val PING_TIMEOUT_MS = 45_000L
    }
}

private const val QUEUED_FRAMES = 4

/** Wraps a Ktor server [DefaultWebSocketSession] as a [SyncConnection]. */
private class KtorServerConnection(
    private val session: DefaultWebSocketSession,
    override val remoteHost: String,
) : SyncConnection {
    private val inbound = Channel<ByteArray>(QUEUED_FRAMES)

    /** Waits while frames are queued, so the socket isn't read further until someone receives them. */
    suspend fun deliver(bytes: ByteArray) {
        inbound.send(bytes)
    }

    fun onClosed() {
        inbound.close()
    }

    override suspend fun send(bytes: ByteArray) {
        session.send(Frame.Binary(true, bytes))
    }

    override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()

    override fun close() {
        inbound.close()
        runCatching { session.cancel() }
    }
}
