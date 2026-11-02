package io.github.aedev.flow.sync.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WsClientTest {
    @Test
    fun `the client closes on a message past its limit`() =
        runBlocking {
            val accepted = CompletableDeferred<SyncConnection>()
            val server = WsServer("/limit") { accepted.complete(it) }
            try {
                val port = server.start()
                val client = WsClient(OkHttpClient(), maxMessage = 1024).connect("ws://127.0.0.1:$port/limit")
                val connection = accepted.await()
                connection.send(ByteArray(1024) { 2 })
                assertArrayEquals(ByteArray(1024) { 2 }, client.receive())
                connection.send(ByteArray(1025))
                assertNull(client.receive())
                connection.close()
            } finally {
                server.stop()
            }
        }
}
