package io.github.aedev.flow.innertube

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import io.github.aedev.flow.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

class AcceptLanguageTest {
    private lateinit var server: HttpServer
    private val received = Collections.synchronizedList(mutableListOf<String?>())

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received += exchange.requestHeaders.getFirst("Accept-Language")
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `English keeps the header the app always sent`() {
        assertThat(acceptLanguage("en")).isEqualTo("en-US,en;q=0.9")
        assertThat(acceptLanguage("en-GB")).isEqualTo("en-GB,en;q=0.9")
    }

    @Test
    fun `another language comes first with English after it`() {
        assertThat(acceptLanguage("fr")).isEqualTo("fr,en;q=0.9")
        assertThat(acceptLanguage("pt-BR")).isEqualTo("pt-BR,pt;q=0.9,en;q=0.8")
        assertThat(acceptLanguage("zh-TW")).isEqualTo("zh-TW,zh;q=0.9,en;q=0.8")
    }

    @Test
    fun `each request carries the content language in force when it is sent`() {
        val innerTube = InnerTube()
        val url = "http://127.0.0.1:${server.address.port}/"

        innerTube.locale = YouTubeLocale(gl = "FR", hl = "fr")
        runBlocking { innerTube.registerPlayback(url, cpn = "cpn", playlistId = null) }
        innerTube.locale = YouTubeLocale(gl = "BR", hl = "pt-BR")
        runBlocking { innerTube.registerPlayback(url, cpn = "cpn", playlistId = null) }

        assertThat(received).containsExactly("fr,en;q=0.9", "pt-BR,pt;q=0.9,en;q=0.8").inOrder()
    }
}
