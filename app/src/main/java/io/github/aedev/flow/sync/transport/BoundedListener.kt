package io.github.aedev.flow.sync.transport

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.utf8Size

/**
 * Hands [delegate] only messages of at most [limit] bytes. OkHttp reads a whole message before it calls back and has
 * no limit of its own, so a bigger one fails the socket instead of being read on.
 */
class BoundedListener(
    private val limit: Long,
    private val delegate: WebSocketListener,
) : WebSocketListener() {
    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) = delegate.onOpen(webSocket, response)

    override fun onMessage(
        webSocket: WebSocket,
        bytes: ByteString,
    ) = if (bytes.size > limit) webSocket.cancel() else delegate.onMessage(webSocket, bytes)

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) = if (text.utf8Size() > limit) webSocket.cancel() else delegate.onMessage(webSocket, text)

    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) = delegate.onClosing(webSocket, code, reason)

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) = delegate.onClosed(webSocket, code, reason)

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) = delegate.onFailure(webSocket, t, response)
}
