package io.github.aedev.flow.sync.transport

import io.ktor.websocket.FrameParser
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import okio.Socket
import okio.Source
import okio.buffer
import java.net.ProtocolException
import java.nio.ByteBuffer

private const val EXTENSIONS = "Sec-WebSocket-Extensions"
private const val LONGEST_FRAME_HEADER = 14L

/**
 * Fails a WebSocket client on a message over [limit] bytes as soon as a frame header announces it. OkHttp has no limit
 * of its own and holds a whole message before it calls back. Compression is refused, because the limit counts bytes on the wire.
 */
internal class MessageLimit(
    private val limit: Long,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response =
            chain.proceed(
                chain
                    .request()
                    .newBuilder()
                    .removeHeader(EXTENSIONS)
                    .build(),
            )
        val socket = response.socket ?: return response
        return response
            .newBuilder()
            .removeHeader(EXTENSIONS)
            .socket(
                object : Socket by socket {
                    override val source: Source = LimitedFrames(socket.source, limit)
                },
            ).build()
    }
}

private class LimitedFrames(
    source: Source,
    private val limit: Long,
) : Source by source {
    private val input: BufferedSource = source.buffer()
    private val parser = FrameParser()
    private var message = 0L
    private var body = 0L
    private var inBody = false

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (!inBody) return readHeader(sink)
        val read = input.read(sink, minOf(byteCount, body))
        if (read > 0) body -= read
        if (body == 0L) nextFrame()
        return read
    }

    private fun readHeader(sink: Buffer): Long {
        var moved = 0L
        var wanted = 1L
        while (!parser.bodyReady) {
            input.require(wanted)
            val bytes = input.peek().readByteArray(minOf(input.buffer.size, LONGEST_FRAME_HEADER))
            val header = ByteBuffer.wrap(bytes)
            parse(header)
            val taken = header.position().toLong()
            input.read(sink, taken)
            moved += taken
            wanted = if (taken == 0L) bytes.size + 1L else 1L
        }
        admit()
        body = parser.length
        if (body == 0L) nextFrame() else inBody = true
        return moved
    }

    private fun parse(header: ByteBuffer) =
        try {
            parser.frame(header)
        } catch (e: IllegalStateException) {
            throw ProtocolException(e.message)
        }

    private fun admit() {
        val length = parser.length
        val control = parser.frameType.controlFrame
        val total = if (control) length else message + length
        if (length < 0 || total > limit) throw ProtocolException("WebSocket message over $limit bytes")
        if (!control) message = if (parser.fin) 0 else total
    }

    private fun nextFrame() {
        parser.bodyComplete()
        inBody = false
    }
}
