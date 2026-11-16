package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync2.linking.LanEndpoint
import io.github.aedev.flow.sync2.linking.dial
import io.github.aedev.flow.sync2.store.storeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Plain HTTP to a local store goes straight there, whatever proxy the system has (spec/store.md, Transport security). */
class PlainHttpProxyTest {
    private val system = ProxySelector.getDefault()

    @After
    fun restore() {
        ProxySelector.setDefault(system)
    }

    /** Answers every connection with a 204, counting them. */
    private fun listener(hits: AtomicInteger): ServerSocket {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    socket.accept().use {
                        hits.incrementAndGet()
                        it.getInputStream().read(ByteArray(4096))
                        it.getOutputStream().write("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())
                    }
                }
            }
        }
        return socket
    }

    private fun systemProxy(port: Int) {
        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))

                override fun connectFailed(
                    uri: URI,
                    address: SocketAddress,
                    e: IOException,
                ) = Unit
            },
        )
    }

    @Test
    fun plain_http_to_a_local_store_does_not_go_through_a_system_proxy() {
        val (viaProxy, direct) = AtomicInteger() to AtomicInteger()
        listener(viaProxy).use { proxy ->
            listener(direct).use { store ->
                systemProxy(proxy.localPort)
                val url = "http://127.0.0.1:${store.localPort}/".toHttpUrl()

                storeClient(url).newCall(Request.Builder().url(url).build()).execute().close()

                assertEquals("the proxy saw the request", 0, viaProxy.get())
                assertEquals(1, direct.get())
            }
        }
    }

    @Test
    fun a_dial_on_the_lan_does_not_go_through_a_system_proxy() {
        val (viaProxy, direct) = AtomicInteger() to AtomicInteger()
        listener(viaProxy).use { proxy ->
            listener(direct).use { peer ->
                systemProxy(proxy.localPort)

                runBlocking { withContext(Dispatchers.IO) { dial(listOf(LanEndpoint("127.0.0.1", peer.localPort))) } }

                assertEquals("the proxy saw the dial", 0, viaProxy.get())
                assertEquals(1, direct.get())
            }
        }
    }

    @Test
    fun https_keeps_the_system_s_proxy_choice() {
        assertNull(storeClient("https://example.com/".toHttpUrl()).proxy)
        assertEquals(Proxy.NO_PROXY, storeClient("http://10.0.0.1/".toHttpUrl()).proxy)
        assertEquals(Proxy.NO_PROXY, storeClient("http://printer.local/".toHttpUrl()).proxy)
    }
}
