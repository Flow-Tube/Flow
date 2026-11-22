package io.github.aedev.flow.utils

import androidx.annotation.StringRes
import io.github.aedev.flow.R
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.TimeoutCancellationException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What a failed load means to the viewer. Exception text carries hosts, URLs and request bodies and
 * is never translated, so it stays in logcat and the screen says one of these instead.
 */
enum class LoadErrorKind {
    /** No route to YouTube at all: no network, or DNS failing. */
    OFFLINE,

    /** The network is there but answered too slowly. */
    SLOW,

    /** YouTube is rate limiting or failing on its side; only waiting helps. */
    REFUSED,

    /** Anything else; the surface's own message fits best. */
    OTHER,
    ;

    @StringRes
    fun messageRes(
        @StringRes fallback: Int,
    ): Int =
        when (this) {
            OFFLINE -> R.string.error_no_internet
            SLOW -> R.string.error_timeout
            REFUSED -> R.string.categories_rate_limited
            OTHER -> fallback
        }

    companion object {
        private const val MAX_CAUSE_DEPTH = 8

        /** Reads [error] and its causes, since a paging or Ktor wrapper often hides the network failure. */
        fun of(error: Throwable): LoadErrorKind =
            generateSequence(error) { current -> current.cause?.takeIf { it !== current } }
                .take(MAX_CAUSE_DEPTH)
                .firstNotNullOfOrNull(::kindOf)
                ?: OTHER

        private fun kindOf(error: Throwable): LoadErrorKind? =
            when (error) {
                is UnknownHostException, is ConnectException, is NoRouteToHostException -> OFFLINE

                is SocketTimeoutException,
                is ConnectTimeoutException,
                is HttpRequestTimeoutException,
                is TimeoutCancellationException,
                -> SLOW

                is ResponseException -> if (error.response.status.isRateLimitOrOutage()) REFUSED else null

                else -> null
            }

        private fun HttpStatusCode.isRateLimitOrOutage(): Boolean =
            this == HttpStatusCode.TooManyRequests || value >= HttpStatusCode.InternalServerError.value
    }
}
