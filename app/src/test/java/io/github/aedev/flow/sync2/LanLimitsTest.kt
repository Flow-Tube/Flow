package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync.transport.WsServer
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.Purpose
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.encodePayload
import io.github.aedev.flow.sync2.crypto.lanHelloAad
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.crypto.seal
import io.github.aedev.flow.sync2.crypto.u32
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.lan.GREETING_CONNECTIONS
import io.github.aedev.flow.sync2.lan.LanGreeter
import io.github.aedev.flow.sync2.lan.MAX_LAN_FRAME
import io.github.aedev.flow.sync2.lan.acceptLink
import io.github.aedev.flow.sync2.lan.connectLink
import io.github.aedev.flow.sync2.linking.LanEndpoint
import io.github.aedev.flow.sync2.linking.dial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** What a connection on the LAN gets before anyone knows it's a member (spec/live.md, Session; spec/store.md, Untrusted input). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LanLimitsTest {
    private val path = "/flow-sync/2/test"

    private class Silent : SyncConnection {
        var closed = false

        override val remoteHost: String? = null

        override suspend fun send(bytes: ByteArray) = Unit

        override suspend fun receive(): ByteArray? = awaitCancellation()

        override fun close() {
            closed = true
        }
    }

    private class OneFrame(
        private val frame: ByteArray,
    ) : SyncConnection {
        private var read = false
        override val remoteHost: String? = null

        override suspend fun send(bytes: ByteArray) = Unit

        override suspend fun receive(): ByteArray? = if (read) null else frame.also { read = true }

        override fun close() = Unit
    }

    @Test
    fun a_payload_stops_inflating_at_its_limit() {
        val content = ByteArray(1000)
        assertArrayEquals(content, decodePayload(encodePayload(content), 1000))
        val error = runCatching { decodePayload(encodePayload(content), 999) }.exceptionOrNull() as ProtocolException
        assertEquals(ProtocolError.LENGTH, error.error)
    }

    @Test
    fun a_hello_with_more_content_than_a_hello_holds_is_refused() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.b)
            val lanKey = purposeKey(g.epochKey, g.group, 0, Purpose.LAN)
            val frame = u32(0) + seal(lanKey, lanHelloAad(1), encodePayload(ByteArray(5 * 1024)))

            val error = runCatching { acceptLink(OneFrame(frame), session) }.exceptionOrNull() as ProtocolException

            assertEquals(ProtocolError.LENGTH, error.error)
        }

    @Test
    fun a_side_that_goes_quiet_in_the_hello_ends_it() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val session = Session.open(g.deviceDb(), g.store, g.b)
            assertTrue(runCatching { acceptLink(Silent(), session) }.exceptionOrNull() is LinkedException.TimedOut)
            assertTrue(runCatching { connectLink(Silent(), session) }.exceptionOrNull() is LinkedException.TimedOut)
        }

    @Test
    fun connections_past_the_limit_are_closed_until_a_hello_ends() =
        runTest {
            val greeter = LanGreeter { null }
            val waiting = List(GREETING_CONNECTIONS) { Silent() }
            assertTrue(waiting.all(greeter::takes))
            val extra = Silent()
            assertFalse(greeter.takes(extra))
            assertTrue(extra.closed)

            assertTrue(runCatching { greeter.greet(waiting.first()) }.exceptionOrNull() is LinkedException.TimedOut)
            assertTrue("the slot is free again", greeter.takes(Silent()))
        }

    @Test
    fun the_roster_is_not_read_until_a_hello_arrives() =
        runTest {
            val opened = AtomicInteger()
            val greeter =
                LanGreeter {
                    opened.incrementAndGet()
                    null
                }
            val silent = Silent()
            greeter.takes(silent)
            runCatching { greeter.greet(silent) }
            assertEquals(0, opened.get())

            val speaking = OneFrame(ByteArray(8))
            greeter.takes(speaking)
            assertNull(greeter.greet(speaking))
            assertEquals(1, opened.get())
        }

    private fun open(
        client: OkHttpClient,
        port: Int,
        closed: CountDownLatch? = null,
    ): WebSocket {
        val opened = CountDownLatch(1)
        val socket =
            client.newWebSocket(
                Request.Builder().url("ws://127.0.0.1:$port$path").build(),
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) = opened.countDown()

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        closed?.countDown()
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        closed?.countDown()
                    }
                },
            )
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        return socket
    }

    @Test
    fun a_server_for_one_session_closes_a_second_connection() {
        val server = WsServer(path, MAX_LAN_FRAME)
        val port = runBlocking { server.start() }
        val client = OkHttpClient()
        try {
            val first = open(client, port)
            runBlocking { server.awaitConnection() }
            val closed = CountDownLatch(1)
            val second = open(client, port, closed)
            assertTrue("nothing would ever read it", closed.await(5, TimeUnit.SECONDS))
            first.cancel()
            second.cancel()
        } finally {
            server.stop()
        }
    }

    @Test
    fun a_connection_nobody_reads_stops_being_read_from_the_socket() {
        val unread = Channel<SyncConnection>(1)
        val server = WsServer(path, MAX_LAN_FRAME) { unread.trySend(it) }
        val port = runBlocking { server.start() }
        val client = OkHttpClient()
        try {
            val socket = open(client, port)
            val frame = ByteArray(1024 * 1024).toByteString()
            // Far past what the socket and the server's own queues hold.
            val goal = 256
            var sent = 0
            val deadline = System.currentTimeMillis() + 20_000
            while (sent < goal && System.currentTimeMillis() < deadline) {
                if (socket.queueSize() < 8L * 1024 * 1024 && socket.send(frame)) sent++ else Thread.sleep(5)
            }
            Thread.sleep(2_000)
            assertTrue("$sent frames went into a connection nobody reads and ${socket.queueSize()} bytes wait", socket.queueSize() > 0)
            socket.cancel()
        } finally {
            server.stop()
        }
    }

    @Test
    fun a_dial_does_not_follow_a_redirect() {
        val hits = AtomicInteger()
        val target = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val origin = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    target.accept().use { hits.incrementAndGet() }
                }
            }
        }
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    origin.accept().use {
                        it.getInputStream().read(ByteArray(4096))
                        val location = "http://127.0.0.1:${target.localPort}/elsewhere"
                        val answer = "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\n\r\n"
                        it.getOutputStream().write(answer.toByteArray())
                    }
                }
            }
        }
        try {
            val endpoint = LanEndpoint("127.0.0.1", origin.localPort)
            val pipe = runBlocking { withContext(Dispatchers.IO) { withTimeout(10_000) { dial(listOf(endpoint)) } } }
            assertNull(pipe)
            assertEquals(0, hits.get())
        } finally {
            target.close()
            origin.close()
        }
    }
}
