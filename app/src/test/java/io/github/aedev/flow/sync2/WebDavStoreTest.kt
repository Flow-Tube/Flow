package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync2.store.StoreException
import io.github.aedev.flow.sync2.store.WebDavStore
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** WebDAV as a file store, against a stand-in that answers as real servers do (spec/store.md, WebDAV). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WebDavStoreTest {
    private var server: WebDavStandIn? = null

    private fun serve(standIn: WebDavStandIn) = standIn.also { server = it }

    @After
    fun stop() {
        server?.close()
    }

    @Test
    fun files_round_trip_and_their_folders_are_made_when_needed() =
        runTest {
            val dav = serve(WebDavStandIn())
            val store = dav.store()
            store.put("dev/aa/log/0000000000000000/0000000000000001.o", byteArrayOf(1, 2))
            assertArrayEquals(byteArrayOf(1, 2), store.get("dev/aa/log/0000000000000000/0000000000000001.o"))
            assertEquals(listOf("aa"), store.list("dev"))
            assertEquals(listOf("0000000000000001.o"), store.list("dev/aa/log/0000000000000000"))
            assertEquals(emptyList<String>(), store.list("roster"))
            assertNull(store.get("dev/aa/log/0000000000000000/0000000000000002.o"))

            dav.store(conditional = true).put("dev/aa/log/0000000000000000/0000000000000001.o", byteArrayOf(9))
            assertArrayEquals(
                "a guarded write leaves what is there",
                byteArrayOf(1, 2),
                dav.file("dev/aa/log/0000000000000000/0000000000000001.o"),
            )

            store.delete("dev/aa/log/0000000000000000/0000000000000001.o")
            store.delete("dev/aa/log/0000000000000000/0000000000000001.o")
            assertNull(store.get("dev/aa/log/0000000000000000/0000000000000001.o"))
            assertTrue("a PROPFIND always says how deep", dav.requests.none { it.startsWith("PROPFIND") && it.contains("400") })
        }

    @Test
    fun a_refused_password_stops_every_later_request_with_it() =
        runTest {
            val dav = serve(WebDavStandIn())
            val wrong = dav.store(password = "wrong")
            repeat(3) {
                val error = runCatching { wrong.get("store.json") }.exceptionOrNull()
                assertTrue("$error", error is StoreException.LoginRefused)
            }
            assertEquals("only the first request reached the server", 1, dav.requests.size)
            assertNull("other credentials are tried again", dav.store().get("store.json"))
        }

    @Test
    fun the_probe_writes_reads_back_and_cleans_up_and_tells_whether_if_none_match_works() =
        runTest {
            assertTrue(serve(WebDavStandIn(ifNoneMatch = true)).store().probe())
            stop()
            val ignoring = serve(WebDavStandIn(ifNoneMatch = false))
            assertFalse(ignoring.store().probe())
            assertTrue("the probe cleans up", ignoring.files.keys.none { it.contains("probe/") })
            assertTrue("and leaves the store's folder made", "flow/" in ignoring.folders)
        }

    @Test
    fun only_folder_tags_that_follow_their_contents_are_used() =
        runTest {
            val nextcloud = serve(WebDavStandIn(nextcloud = true)).store()
            nextcloud.put("dev/aa/blob/x.b", byteArrayOf(1))
            val before = nextcloud.tag("dev")
            nextcloud.put("dev/bb/blob/y.b", byteArrayOf(2))
            assertNotEquals(before, nextcloud.tag("dev"))
            stop()

            val plain = serve(WebDavStandIn()).store()
            plain.put("dev/aa/blob/x.b", byteArrayOf(1))
            assertNull(plain.tag("dev"))
        }

    @Test
    fun answers_past_their_limit_are_refused() =
        runTest {
            StandInServer {
                if (request.httpMethod == HttpMethod.Get) {
                    respondBytes(ByteArray(4 * 1024 * 1024 + 1))
                } else {
                    respondText(" ".repeat(16 * 1024 * 1024 + 1), status = HttpStatusCode.MultiStatus)
                }
            }.use { standIn ->
                val dav = WebDavStore.connect(standIn.url, "u", "p")
                assertTrue(runCatching { dav.get("dev/x.o") }.exceptionOrNull() is StoreException.TooLarge)
                assertTrue(runCatching { dav.list("dev/") }.exceptionOrNull() is StoreException.TooLarge)
            }
        }

    @Test
    fun plain_http_is_refused_except_to_private_addresses() {
        val error = runCatching { WebDavStore.connect("http://example.com/dav/", "u", "p") }.exceptionOrNull()
        assertTrue("$error", error is StoreException.Insecure)
        WebDavStore.connect("http://192.168.1.4/dav/", "u", "p")
        WebDavStore.connect("https://example.com/dav/", "u", "p")
    }
}
