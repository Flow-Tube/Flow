package io.github.aedev.flow.sync.transport

/**
 * A single full-duplex binary message channel for one FLOW-SYNC/1 session, abstracting over the
 * OkHttp WebSocket client ([WsClient]) and the Ktor embedded server ([WsServer]). Each message is
 * one complete wire frame.
 */
interface SyncConnection {
    /** Send one binary wire frame. */
    suspend fun send(bytes: ByteArray)

    /** Receive the next binary wire frame, or null when the connection closes. */
    suspend fun receive(): ByteArray?

    /** Close the underlying socket. */
    fun close()

    /** The other end's address, on a connection this side accepted. */
    val remoteHost: String? get() = null
}
