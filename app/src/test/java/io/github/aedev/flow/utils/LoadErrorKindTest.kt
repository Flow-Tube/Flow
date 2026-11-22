package io.github.aedev.flow.utils

import androidx.paging.LoadState
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class LoadErrorKindTest {
    private fun refusal(status: HttpStatusCode): Throwable {
        val response = mockk<HttpResponse>(relaxed = true) { every { this@mockk.status } returns status }
        return ResponseException(response, "{\"error\":{}}")
    }

    @Test
    fun `no route to YouTube reads as offline`() {
        assertThat(LoadErrorKind.of(UnknownHostException("Unable to resolve host \"www.youtube.com\""))).isEqualTo(LoadErrorKind.OFFLINE)
        assertThat(LoadErrorKind.of(ConnectException("failed to connect"))).isEqualTo(LoadErrorKind.OFFLINE)
    }

    @Test
    fun `a timeout reads as slow`() {
        assertThat(LoadErrorKind.of(SocketTimeoutException("timeout"))).isEqualTo(LoadErrorKind.SLOW)
    }

    @Test
    fun `a rate limit or a server error asks the viewer to wait`() {
        assertThat(LoadErrorKind.of(refusal(HttpStatusCode.TooManyRequests))).isEqualTo(LoadErrorKind.REFUSED)
        assertThat(LoadErrorKind.of(refusal(HttpStatusCode.ServiceUnavailable))).isEqualTo(LoadErrorKind.REFUSED)
    }

    @Test
    fun `a client error or a plain failure falls back to the surface's own message`() {
        assertThat(LoadErrorKind.of(refusal(HttpStatusCode.BadRequest))).isEqualTo(LoadErrorKind.OTHER)
        assertThat(LoadErrorKind.of(IOException("Unable to resolve host"))).isEqualTo(LoadErrorKind.OTHER)
        assertThat(LoadErrorKind.OTHER.messageRes(R.string.search_failed)).isEqualTo(R.string.search_failed)
    }

    @Test
    fun `the cause under a wrapper decides`() {
        val wrapped = IllegalStateException("page failed", IOException("io", UnknownHostException("youtube.com")))

        assertThat(LoadErrorKind.of(LoadState.Error(wrapped).error)).isEqualTo(LoadErrorKind.OFFLINE)
    }

    @Test
    fun `each kind has its own message`() {
        assertThat(LoadErrorKind.OFFLINE.messageRes(R.string.load_failed)).isEqualTo(R.string.error_no_internet)
        assertThat(LoadErrorKind.SLOW.messageRes(R.string.load_failed)).isEqualTo(R.string.error_timeout)
        assertThat(LoadErrorKind.REFUSED.messageRes(R.string.load_failed)).isEqualTo(R.string.categories_rate_limited)
    }
}
