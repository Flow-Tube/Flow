package io.github.aedev.flow.network

import com.google.common.truth.Truth.assertThat
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** One client per proxy setting: built once, rebuilt only when the proxy changes. */
class ProxyAwareClientTest {
    @After
    fun reset() = AppProxyManager.update(AppProxyConfig())

    @Test
    fun `the same client serves every call while the proxy is unchanged`() {
        val http = ProxyAwareClient()

        assertThat(http.get()).isSameInstanceAs(http.get())
    }

    @Test
    fun `a proxy change gives a new client that goes through the new proxy`() {
        val http = ProxyAwareClient()
        val direct = http.get()
        assertThat(direct.proxy).isNull()

        AppProxyManager.update(AppProxyConfig(enabled = true, host = "proxy.example.test", port = 3128))
        val proxied = http.get()

        assertThat(proxied).isNotSameInstanceAs(direct)
        assertThat(proxied.proxy!!.type()).isEqualTo(Proxy.Type.HTTP)
        assertThat((proxied.proxy!!.address() as InetSocketAddress).hostString).isEqualTo("proxy.example.test")
        assertThat(http.get()).isSameInstanceAs(proxied)
    }

    @Test
    fun `an http proxy with credentials gets its authorization header`() {
        AppProxyManager.update(
            AppProxyConfig(enabled = true, host = "proxy.example.test", port = 3128, username = "user", password = "pass"),
        )
        val client = ProxyAwareClient().get()
        val challenge =
            Response
                .Builder()
                .request(Request.Builder().url("https://www.youtube.com/").build())
                .protocol(Protocol.HTTP_1_1)
                .code(407)
                .message("Proxy Authentication Required")
                .build()

        val retry = client.proxyAuthenticator.authenticate(null, challenge)

        assertThat(retry!!.header("Proxy-Authorization")).isEqualTo(AppProxyManager.currentHttpProxyAuthorizationHeader())
    }

    @Test
    fun `the caller's settings are applied on top of the proxy`() {
        val client = ProxyAwareClient { connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS) }.get()

        assertThat(client.connectTimeoutMillis).isEqualTo(8_000)
        assertThat(client.readTimeoutMillis).isEqualTo(8_000)
        assertThat(client.writeTimeoutMillis).isEqualTo(10_000)
    }
}
