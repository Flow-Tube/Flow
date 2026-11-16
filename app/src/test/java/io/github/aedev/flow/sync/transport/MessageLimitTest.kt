package io.github.aedev.flow.sync.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MessageLimitTest {
    private val limit = 1024L

    private fun connected(
        allowed: Long = limit,
        responseHeaders: String = "",
        frames: (OutputStream) -> Unit,
        check: suspend (SyncConnection, RawWebSocketPeer) -> Unit,
    ) = runBlocking {
        RawWebSocketPeer(responseHeaders, frames = frames).use { peer ->
            val connection =
                withContext(Dispatchers.IO) { WsClient(OkHttpClient(), allowed).connect("ws://127.0.0.1:${peer.port}/") }
            withTimeout(30_000) { check(connection, peer) }
            connection.close()
        }
    }

    @Test
    fun `a message at the limit arrives`() =
        connected(frames = { it.write(frame(BINARY, ByteArray(1024) { 7 })) }) { connection, _ ->
            assertArrayEquals(ByteArray(1024) { 7 }, connection.receive())
        }

    @Test
    fun `a header announcing more than the limit fails before any body comes`() =
        connected(frames = { it.write(frameHeader(BINARY, limit + 1)) }) { connection, _ ->
            assertNull(connection.receive())
        }

    @Test
    fun `a header announcing an impossible length fails`() =
        connected(frames = { it.write(frameHeader(BINARY, -1)) }) { connection, _ ->
            assertNull(connection.receive())
        }

    @Test
    fun `fragments adding up past the limit fail`() =
        connected(
            frames = {
                it.write(frame(BINARY, ByteArray(1024), fin = false))
                it.write(frameHeader(CONTINUATION, 1))
            },
        ) { connection, _ ->
            assertNull(connection.receive())
        }

    @Test
    fun `fragments within the limit arrive whole with a ping between them`() =
        connected(
            frames = {
                it.write(frame(BINARY, ByteArray(512) { 1 }, fin = false))
                it.write(frame(PING, ByteArray(100)))
                it.write(frame(CONTINUATION, ByteArray(512) { 1 }))
                it.write(frame(BINARY, ByteArray(1024) { 2 }))
            },
        ) { connection, _ ->
            assertArrayEquals(ByteArray(1024) { 1 }, connection.receive())
            assertArrayEquals(ByteArray(1024) { 2 }, connection.receive())
        }

    @Test
    fun `a header split across writes is still read`() =
        connected(
            frames = { out ->
                frame(BINARY, ByteArray(300) { 3 }).forEach {
                    out.write(it.toInt())
                    out.flush()
                }
            },
        ) { connection, _ ->
            assertArrayEquals(ByteArray(300) { 3 }, connection.receive())
        }

    @Test
    fun `compression is not offered and a compressed frame fails`() =
        connected(
            responseHeaders = "Sec-WebSocket-Extensions: permessage-deflate\r\n",
            frames = { it.write(frameHeader(BINARY, 10, rsv1 = true) + ByteArray(10)) },
        ) { connection, peer ->
            assertNull(connection.receive())
            assertFalse(peer.requestHead.contains("permessage-deflate", ignoreCase = true))
        }

    @Test
    fun `the socket is not read further while messages wait to be received`() {
        val mebibyte = 1024 * 1024
        val total = 64
        val sent = AtomicInteger()
        val finished = CountDownLatch(1)
        connected(
            allowed = mebibyte.toLong(),
            frames = { out ->
                repeat(total) { i ->
                    out.write(frame(BINARY, ByteArray(mebibyte) { i.toByte() }))
                    sent.incrementAndGet()
                }
                finished.countDown()
            },
        ) { connection, _ ->
            withContext(Dispatchers.IO) { finished.await(3, TimeUnit.SECONDS) }
            assertTrue("every message was read with nothing receiving them", sent.get() < total)
            repeat(total) { i -> assertEquals(i.toByte(), checkNotNull(connection.receive())[0]) }
        }
    }
}
