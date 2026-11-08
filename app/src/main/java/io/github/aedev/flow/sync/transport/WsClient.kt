package io.github.aedev.flow.sync.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val QUEUED_MESSAGES = 4

/**
 * Scan/join role transport: an OkHttp WebSocket **client**. The injected
 * [OkHttpClient] is rebuilt with no call/read timeout (a long-lived WS would otherwise be killed
 * by the app's 60 s `callTimeout`) plus a keepalive ping. With a [maxMessage], a message over it fails the connection
 * and at most [QUEUED_MESSAGES] wait to be received: the socket isn't read further until they are.
 */
class WsClient(
    private val baseClient: OkHttpClient,
    private val maxMessage: Long = Long.MAX_VALUE,
) {
    suspend fun connect(url: String): SyncConnection =
        suspendCancellableCoroutine { cont ->
            val limited = maxMessage != Long.MAX_VALUE
            val client =
                baseClient
                    .newBuilder()
                    .proxy(Proxy.NO_PROXY) // These connections stay on the local network.
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .connectTimeout(4, TimeUnit.SECONDS) // AP-isolation / firewall → fail fast
                    .pingInterval(20, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .apply { if (limited) addInterceptor(MessageLimit(maxMessage)) }
                    .build()
            val incoming = Channel<ByteArray>(if (limited) QUEUED_MESSAGES else Channel.UNLIMITED)
            val request = Request.Builder().url(url).build()

            val ws =
                client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            if (cont.isActive) cont.resume(WsConnection(webSocket, incoming))
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            bytes: ByteString,
                        ) {
                            incoming.trySendBlocking(bytes.toByteArray())
                        }

                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            incoming.close()
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            incoming.close()
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            incoming.close(t)
                            if (cont.isActive) cont.resumeWithException(t)
                        }
                    },
                )
            cont.invokeOnCancellation {
                runCatching { ws.cancel() }
                incoming.cancel()
            }
        }
}

/** Wraps an open OkHttp [WebSocket] as a [SyncConnection]. */
class WsConnection(
    private val ws: WebSocket,
    private val incoming: Channel<ByteArray>,
) : SyncConnection {
    override suspend fun send(bytes: ByteArray) {
        // OkHttp refuses (returns false) once the socket is closing or its 16 MiB outgoing queue is
        // full; ignoring that dropped the frame silently and the session died later with no cause.
        if (!ws.send(bytes.toByteString())) throw IOException("WebSocket refused a ${bytes.size}-byte frame")
    }

    override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()

    override fun close() {
        runCatching { ws.close(1000, null) }
        incoming.cancel()
    }
}
