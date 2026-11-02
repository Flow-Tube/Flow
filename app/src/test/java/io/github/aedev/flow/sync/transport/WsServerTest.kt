package io.github.aedev.flow.sync.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WsServerTest {
    @Test
    fun `a frame past the limit closes the connection`() =
        runBlocking {
            val accepted = CompletableDeferred<SyncConnection>()
            val server = WsServer("/limit", maxFrame = 1024) { accepted.complete(it) }
            try {
                val port = server.start()
                val client = WsClient(OkHttpClient()).connect("ws://127.0.0.1:$port/limit")
                client.send(ByteArray(1024) { 1 })
                val connection = accepted.await()
                assertArrayEquals(ByteArray(1024) { 1 }, connection.receive())
                client.send(ByteArray(1025))
                assertNull(connection.receive())
                client.close()
            } finally {
                server.stop()
            }
        }
}
