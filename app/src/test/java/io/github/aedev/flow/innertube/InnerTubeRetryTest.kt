package io.github.aedev.flow.innertube

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The retry policy against Ktor's mock engine on virtual time: the engine runs on the test
 * scheduler, so a handler's delay stands for time spent on the wire.
 */
class InnerTubeRetryTest {
    private var calls = 0

    private fun TestScope.client(handle: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        calls++
                        handle(request)
                    }
                },
            ),
        ) { expectSuccess = true }

    private fun TestScope.retry(
        jitter: Double = 1.5,
        hasActiveNetwork: Boolean = true,
    ) = InnerTubeRetry(
        hasActiveNetwork = { hasActiveNetwork },
        nowMs = { currentTime },
        jitter = { jitter },
    )

    private suspend fun failure(
        policy: InnerTubeRetry,
        client: HttpClient,
    ): Throwable = runCatching { policy.run { client.get(URL) } }.exceptionOrNull()!!

    @Test
    fun `a request that ran into its call timeout is not repeated`() =
        runTest {
            val ktorTimeout =
                client { request ->
                    delay(45_000)
                    throw HttpRequestTimeoutException(request)
                }
            assertThat(failure(retry(), ktorTimeout)).isInstanceOf(HttpRequestTimeoutException::class.java)
            assertThat(calls).isEqualTo(1)

            calls = 0
            val okHttpCallTimeout =
                client {
                    delay(45_000)
                    throw InterruptedIOException("timeout")
                }
            assertThat(failure(retry(), okHttpCallTimeout)).isInstanceOf(InterruptedIOException::class.java)
            assertThat(calls).isEqualTo(1)
        }

    @Test
    fun `read timeouts are retried only while the minute lasts`() =
        runTest {
            val stalled =
                client {
                    delay(30_000)
                    throw SocketTimeoutException("read timed out")
                }

            assertThat(failure(retry(), stalled)).isInstanceOf(SocketTimeoutException::class.java)
            assertThat(calls).isEqualTo(2)
            assertThat(currentTime).isAtMost(62_000L)
        }

    @Test
    fun `a dropped connection is retried after a doubling wait scaled by the jitter`() =
        runTest {
            val factors = ArrayDeque(listOf(1.0, 1.99))
            val policy = InnerTubeRetry(nowMs = { currentTime }, jitter = { factors.removeFirst() })
            val flaky =
                client {
                    if (calls < 3) throw IOException("connection reset") else respondOk()
                }

            val response = policy.run { flaky.get(URL) }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(calls).isEqualTo(3)
            assertThat(currentTime).isEqualTo(500L + 1_990L)
        }

    @Test
    fun `a retry still on the wire when the minute ends is cut there`() =
        runTest {
            val slow =
                client {
                    delay(if (calls == 1) 50_000 else 45_000)
                    throw SocketTimeoutException("read timed out")
                }

            failure(retry(), slow)

            assertThat(calls).isEqualTo(2)
            assertThat(currentTime).isEqualTo(60_000L)
        }

    @Test
    fun `gives up after three attempts`() =
        runTest {
            val down = client { throw IOException("connection reset") }

            failure(retry(), down)

            assertThat(calls).isEqualTo(3)
        }

    @Test
    fun `a failed name lookup with no network up is not repeated`() =
        runTest {
            val offline = client { throw UnknownHostException("www.youtube.com") }

            assertThat(failure(retry(hasActiveNetwork = false), offline)).isInstanceOf(UnknownHostException::class.java)
            assertThat(calls).isEqualTo(1)

            calls = 0
            failure(retry(hasActiveNetwork = true), offline)
            assertThat(calls).isEqualTo(3)
        }

    @Test
    fun `an HTTP error is an answer and is never retried`() =
        runTest {
            listOf(HttpStatusCode.NotFound, HttpStatusCode.TooManyRequests).forEach { status ->
                calls = 0
                assertThat(failure(retry(), client { respondError(status) })).isInstanceOf(ClientRequestException::class.java)
                assertThat(calls).isEqualTo(1)
            }
            calls = 0
            assertThat(failure(retry(), client { respondError(HttpStatusCode.InternalServerError) }))
                .isInstanceOf(ServerResponseException::class.java)
            assertThat(calls).isEqualTo(1)
        }

    private companion object {
        const val URL = "https://www.youtube.com/youtubei/v1/browse"
    }
}
