package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync2.service.LinkedError
import io.github.aedev.flow.sync2.service.linkedError
import io.github.aedev.flow.sync2.store.NetworkFailure
import io.github.aedev.flow.sync2.store.StoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** #2233: every network failure used to read "Couldn't reach the server". */
class NetworkFailureTest {
    private fun network(error: IOException) = StoreException.Network(error).linkedError()

    @Test
    fun android_and_jvm_connect_timeouts_say_nothing_answered() {
        val android = SocketTimeoutException("failed to connect to /192.168.1.15 (port 8019) from /192.168.1.20 (port 41234) after 10000ms")
        assertEquals(LinkedError.CONNECT_TIMEOUT, network(android))
        assertEquals(LinkedError.CONNECT_TIMEOUT, network(SocketTimeoutException("Connect timed out")))
    }

    @Test
    fun a_timeout_after_connecting_says_the_server_stopped_answering() {
        assertEquals(LinkedError.NO_ANSWER, network(SocketTimeoutException("timeout")))
        assertEquals(LinkedError.NO_ANSWER, network(SocketTimeoutException("Read timed out")))
        assertEquals(LinkedError.NO_ANSWER, network(InterruptedIOException("timeout")))
    }

    @Test
    fun a_refused_port_and_a_missing_route_differ() {
        val refused =
            ConnectException("Failed to connect to /192.168.1.15:8019").apply {
                initCause(ConnectException("ECONNREFUSED (Connection refused)"))
            }
        val noRoute =
            ConnectException("Failed to connect to /192.168.1.15:8019").apply {
                initCause(ConnectException("EHOSTUNREACH (No route to host)"))
            }

        assertEquals(LinkedError.CONNECTION_REFUSED, network(refused))
        assertEquals(LinkedError.NO_SUCH_HOST, network(noRoute))
        assertEquals(LinkedError.NO_SUCH_HOST, network(UnknownHostException("nas.local")))
    }

    @Test
    fun a_port_that_is_not_http_says_so() {
        assertEquals(LinkedError.NOT_HTTP, network(ProtocolException("Unexpected status line: \u0015\u0003")))
        assertEquals(LinkedError.NOT_HTTP, network(SSLHandshakeException("handshake failed")))
        assertFalse(LinkedError.NOT_HTTP.retryable)
    }

    @Test
    fun anything_else_keeps_the_general_message() {
        assertEquals(NetworkFailure.OTHER, NetworkFailure.of(IOException("unexpected end of stream")))
        assertEquals(LinkedError.UNREACHABLE, network(IOException()))
        assertTrue(LinkedError.CONNECT_TIMEOUT.retryable)
    }

    @Test
    fun webdav_folder_answers_name_the_folder_problem() {
        assertEquals(LinkedError.FOLDER_REDIRECTED, StoreException.Refused(301, "webdav").linkedError())
        assertEquals(LinkedError.FOLDER_MISSING, StoreException.Refused(404, "webdav").linkedError())
        assertEquals(LinkedError.FOLDER_MISSING, StoreException.Refused(409, "webdav").linkedError())
        assertEquals(LinkedError.FOLDER_FORBIDDEN, StoreException.Refused(403, "webdav").linkedError())
        assertEquals(LinkedError.REFUSED, StoreException.Refused(500, "webdav").linkedError())
        assertEquals(LinkedError.REFUSED, StoreException.Refused(404, "not_found").linkedError())
    }
}
