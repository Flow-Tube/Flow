package io.github.aedev.flow.player.datasource

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamHostFallbackTest {
    private var now = 0L
    private val hosts = StreamHostFallback(forgetAfterMs = 600_000L, clock = { now })
    private val url =
        "https://rr3---sn-gwpa-itqed.googlevideo.com/videoplayback?expire=1&id=o-A&itag=251" +
            "&mn=sn-gwpa-itqed%2Csn-gwpa-qxae&mm=31%2C29&fvip=4&sig=abc"

    @Test
    fun `a reachable server keeps the url as it is`() {
        assertThat(hosts.route(url)).isEqualTo(url)
    }

    @Test
    fun `an unreachable server moves the url to the next one it names`() {
        hosts.markUnreachable(url)

        assertThat(hosts.route(url)).isEqualTo(url.replace("rr3---sn-gwpa-itqed.", "rr3---sn-gwpa-qxae."))
    }

    @Test
    fun `when every named server is unreachable there is no route`() {
        hosts.markUnreachable(url)
        hosts.markUnreachable(url.replace("rr3---sn-gwpa-itqed.", "rr3---sn-gwpa-qxae."))

        assertThat(hosts.route(url)).isNull()
    }

    @Test
    fun `a url naming no other server has no route once its server is unreachable`() {
        val single = "https://rr1---sn-abc.googlevideo.com/videoplayback?id=o-A&sig=abc"
        hosts.markUnreachable(single)

        assertThat(hosts.route(single)).isNull()
    }

    @Test
    fun `an unreachable server is tried again after a while`() {
        hosts.markUnreachable(url)
        now += 600_000L

        assertThat(hosts.route(url)).isEqualTo(url)
    }

    @Test
    fun `only the host changes, never the signed query`() {
        hosts.markUnreachable(url)

        assertThat(hosts.route(url)!!.substringAfter("/videoplayback")).isEqualTo(url.substringAfter("/videoplayback"))
    }
}
