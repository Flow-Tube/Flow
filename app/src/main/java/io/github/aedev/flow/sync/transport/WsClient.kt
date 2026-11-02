package io.github.aedev.flow.sync.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Scan/join role transport: an OkHttp WebSocket **client**. The injected
 * [OkHttpClient] is rebuilt with no call/read timeout (a long-lived WS would otherwise be killed
 * by the app's 60 s `callTimeout`) plus a keepalive ping. A message over [maxMessage] bytes fails the connection.
 */
class WsClient(
    private val baseClient: OkHttpClient,
    private val maxMessage: Long = Long.MAX_VALUE,
) {
    suspend fun connect(url: String): SyncConnection =
        suspendCancellableCoroutine { cont ->
            val client =
                baseClient
                    .newBuilder()
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .connectTimeout(4, TimeUnit.SECONDS) // AP-isolation / firewall → fail fast
                    .pingInterval(20, TimeUnit.SECONDS)
                    .build()
            val incoming = Channel<ByteArray>(Channel.UNLIMITED)
            val request = Request.Builder().url(url).build()

            val ws =
                client.newWebSocket(
                    request,
                    BoundedListener(
                        maxMessage,
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
                                incoming.trySend(bytes.toByteArray())
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
                    ),
                )
            cont.invokeOnCancellation { runCatching { ws.cancel() } }
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
        incoming.close()
    }
}
