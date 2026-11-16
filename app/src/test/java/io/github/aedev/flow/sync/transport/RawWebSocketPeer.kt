package io.github.aedev.flow.sync.transport

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

/** A WebSocket server on a bare socket, so a test decides every byte it sends. Answers the upgrade, then runs [frames]. */
internal class RawWebSocketPeer(
    private val responseHeaders: String = "",
    private val beforeUpgrade: (String, Socket) -> Boolean = { _, _ -> false },
    private val frames: (OutputStream) -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val release = CountDownLatch(1)
    val port: Int = server.localPort

    @Volatile
    var requestHead: String = ""
        private set

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { socket.use(::serve) }
            }
        }
    }

    private fun serve(socket: Socket) {
        runCatching {
            while (true) {
                val head = readHead(socket) ?: return
                if (beforeUpgrade(head, socket)) continue
                requestHead = head
                upgrade(socket, head)
                socket.getOutputStream().apply(frames).flush()
                release.await(60, TimeUnit.SECONDS)
                return
            }
        }
    }

    private fun readHead(socket: Socket): String? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = socket.getInputStream().read()
            if (byte < 0) return null
            head.append(byte.toChar())
        }
        Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(head)?.let {
            socket.getInputStream().readNBytes(it.groupValues[1].toInt())
        }
        return head.toString()
    }

    private fun upgrade(
        socket: Socket,
        head: String,
    ) {
        val key =
            head
                .lineSequence()
                .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                .substringAfter(':')
                .trim()
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray()))
        val protocol = if ("Sec-WebSocket-Protocol:" in head) "Sec-WebSocket-Protocol: flow-sync.v1\r\n" else ""
        socket.getOutputStream().write(
            (
                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n$protocol$responseHeaders" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n"
            ).toByteArray(),
        )
    }

    override fun close() {
        release.countDown()
        server.close()
    }
}

internal const val BINARY = 0x2
internal const val CONTINUATION = 0x0
internal const val PING = 0x9

/** A server frame's header announcing [length] bytes, with no body. */
internal fun frameHeader(
    opcode: Int,
    length: Long,
    fin: Boolean = true,
    rsv1: Boolean = false,
): ByteArray {
    val first = byteArrayOf((opcode or (if (fin) 0x80 else 0) or (if (rsv1) 0x40 else 0)).toByte())
    return first +
        when {
            length in 0..125 -> byteArrayOf(length.toByte())
            length in 126..0xffff -> byteArrayOf(126) + ByteBuffer.allocate(2).putShort(length.toShort()).array()
            else -> byteArrayOf(127) + ByteBuffer.allocate(8).putLong(length).array()
        }
}

internal fun frame(
    opcode: Int,
    body: ByteArray,
    fin: Boolean = true,
): ByteArray = frameHeader(opcode, body.size.toLong(), fin) + body
