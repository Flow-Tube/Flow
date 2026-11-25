package io.github.aedev.flow.innertube

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WebClientVersionTest {
    private val serviceWorker = """self.ytcfg={"INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20261116.00.00","INNERTUBE_CLIENT_NAME":"WEB"}"""

    @Test
    fun `reads the version from each form YouTube has used`() {
        assertEquals("2.20261116.00.00", WebClientVersion.parse(serviceWorker))
        assertEquals("2.20250101.01.00", WebClientVersion.parse("""{"innertube_context_client_version":"2.20250101.01.00"}"""))
        assertEquals("2.20240101.00.00", WebClientVersion.parse("""...&client.version=2.20240101.00.00&c=WEB"""))
        assertEquals(null, WebClientVersion.parse("""{"nothing":"here"}"""))
    }

    @Test
    fun `a worker that names another client is not read as this one`() {
        val mobile = """self.ytcfg={"INNERTUBE_CLIENT_NAME":"MWEB","INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20261123.09.00"}"""

        assertEquals("2.20261123.09.00", WebClientVersion.parse(mobile, clientName = "MWEB"))
        assertEquals(null, WebClientVersion.parse(mobile, clientName = "WEB"))
        assertEquals("2.20261116.00.00", WebClientVersion.parse(serviceWorker, clientName = "WEB"))
        assertEquals("2.20250101.01.00", WebClientVersion.parse("""{"innertube_context_client_version":"2.20250101.01.00"}""", "MWEB"))
    }

    @Test
    fun `fetches once and keeps the live version`() =
        runTest {
            var fetches = 0
            val version =
                WebClientVersion({
                    fetches++
                    serviceWorker
                })
            repeat(3) { assertEquals("2.20261116.00.00", version.get("2.pinned")) }
            assertEquals(1, fetches)
        }

    @Test
    fun `callers asking at the same time share one fetch`() =
        runTest {
            var fetches = 0
            val version =
                WebClientVersion({
                    fetches++
                    delay(100)
                    serviceWorker
                })
            val results = List(5) { async { version.get("2.pinned") } }.awaitAll()
            assertEquals(List(5) { "2.20261116.00.00" }, results)
            assertEquals(1, fetches)
        }

    @Test
    fun `a failed fetch falls back to the pinned version and waits before asking again`() =
        runTest {
            var now = 0L
            var fetches = 0
            var body: String? = null
            val version =
                WebClientVersion(
                    fetchServiceWorker = {
                        fetches++
                        body ?: error("offline")
                    },
                    nowMs = { now },
                )
            assertEquals("2.pinned", version.get("2.pinned"))
            body = serviceWorker
            now = 5 * 60_000L
            assertEquals("2.pinned", version.get("2.pinned"))
            assertEquals(1, fetches)
            now = 11 * 60_000L
            assertEquals("2.20261116.00.00", version.get("2.pinned"))
            assertEquals(2, fetches)
        }

    @Test
    fun `an answer without a version counts as a failure`() =
        runTest {
            val version = WebClientVersion({ "<html>consent</html>" })
            assertEquals("2.pinned", version.get("2.pinned"))
        }
}
