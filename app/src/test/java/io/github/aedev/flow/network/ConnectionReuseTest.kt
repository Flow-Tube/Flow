package io.github.aedev.flow.network

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import io.github.aedev.flow.di.NetworkModule
import io.github.aedev.flow.innertube.InnerTube
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.Collections

/**
 * A connection one client opened is there for the next request to the same host, whichever client
 * makes it, so a warm connection to YouTube isn't opened again by the next component that asks.
 */
class ConnectionReuseTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: HttpServer
    private val connections = Collections.synchronizedList(mutableListOf<Int>())

    private val url get() = "http://127.0.0.1:${server.address.port}/"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            connections += exchange.remoteAddress.port
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        AppProxyManager.update(AppProxyConfig())
    }

    private fun OkHttpClient.fetch() = newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

    private fun context(): Context = mockk { every { cacheDir } returns folder.newFolder() }

    @Test
    fun `InnerTube and the proxy-aware clients share their connections`() {
        val innerTube = InnerTube()
        val playerJs = ProxyAwareClient()

        runBlocking { innerTube.registerPlayback(url, cpn = "cpn", playlistId = null) }
        playerJs.get().fetch()
        ProxyAwareClient().get().fetch()
        runBlocking { innerTube.registerPlayback(url, cpn = "cpn", playlistId = null) }

        assertThat(connections).hasSize(4)
        assertThat(connections.toSet()).hasSize(1)
    }

    @Test
    fun `the app client and the metadata client share their connections`() {
        val context = context()

        NetworkModule.provideOkHttpClient(context).fetch()
        NetworkModule.provideMetadataClient(context).fetch()

        assertThat(connections).hasSize(2)
        assertThat(connections.toSet()).hasSize(1)
    }

    @Test
    fun `a proxy change closes the connections the app client kept open`() {
        val client = NetworkModule.provideOkHttpClient(context())

        client.fetch()
        AppProxyManager.update(AppProxyConfig(enabled = true, host = "proxy.example.test", port = 3128))
        client.fetch()

        assertThat(connections.toSet()).hasSize(2)
    }
}
