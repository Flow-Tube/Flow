package io.github.aedev.flow.sync2.store

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why a store could not be reached, so the message can say what to check (#2233). */
enum class NetworkFailure {
    /** Nothing answered at the address within the connect timeout: wrong address or port, or a firewall. */
    CONNECT_TIMEOUT,

    /** The address answered, and nothing listens on that port. */
    CONNECTION_REFUSED,

    /** The name does not resolve, or there is no route to the address. */
    NO_SUCH_HOST,

    /** Connected, then the server stopped answering. */
    NO_ANSWER,

    /** Something answered that does not speak HTTP, or HTTPS was expected and not offered. */
    NOT_HTTP,

    OTHER,
    ;

    companion object {
        /** How Android words a host it has no route to; OkHttp wraps it in its own ConnectException. */
        private val UNREACHABLE = listOf("EHOSTUNREACH", "ENETUNREACH", "No route to host")

        fun of(error: IOException): NetworkFailure =
            when (error) {
                is SocketTimeoutException -> timeout(error)
                is ConnectException -> refusedOrUnreachable(error)
                is UnknownHostException, is NoRouteToHostException -> NO_SUCH_HOST
                is ProtocolException, is SSLException -> NOT_HTTP
                is InterruptedIOException -> NO_ANSWER
                else -> OTHER
            }

        private fun timeout(error: SocketTimeoutException): NetworkFailure =
            if (error.message.orEmpty().contains("connect", ignoreCase = true)) CONNECT_TIMEOUT else NO_ANSWER

        private fun refusedOrUnreachable(error: ConnectException): NetworkFailure {
            val text = generateSequence<Throwable>(error) { it.cause }.take(4).joinToString(" ") { it.message.orEmpty() }
            return if (UNREACHABLE.any { it in text }) NO_SUCH_HOST else CONNECTION_REFUSED
        }
    }
}
