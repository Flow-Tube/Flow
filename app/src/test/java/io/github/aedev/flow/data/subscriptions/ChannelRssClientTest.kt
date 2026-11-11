package io.github.aedev.flow.data.subscriptions

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.IOException

/** #1094: a sweep's odd 429 or 5xx gets one spaced retry instead of emptying the channel. */
class ChannelRssClientTest {
    private class Scripted(
        private val outcomes: List<Any>,
    ) : Interceptor {
        var calls = 0
        val urls = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val outcome = outcomes[calls.coerceAtMost(outcomes.lastIndex)]
            calls++
            urls += chain.request().url.toString()
            if (outcome is IOException) throw outcome
            val (code, retryAfter) = outcome as Pair<*, *>
            return Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code as Int)
                .message("scripted")
                .apply { (retryAfter as String?)?.let { header("Retry-After", it) } }
                .body(FEED.toResponseBody())
                .build()
        }
    }

    private val sleeps = mutableListOf<Long>()

    private fun client(scripted: Scripted): ChannelRssClient {
        val http = OkHttpClient.Builder().addInterceptor(scripted).build()
        return ChannelRssClient(httpClient = { http }, sleep = { sleeps += it })
    }

    @Test
    fun `a 429 is retried once and the second answer is used`() =
        runTest {
            val scripted = Scripted(listOf(429 to null, 200 to null))

            val result = client(scripted).fetch("UCa")

            assertThat(result.isSuccess).isTrue()
            assertThat(scripted.calls).isEqualTo(2)
            assertThat(sleeps).hasSize(1)
        }

    @Test
    fun `Retry-After sets the wait, within bounds`() =
        runTest {
            client(Scripted(listOf(503 to "3", 200 to null))).fetch("UCa")

            assertThat(sleeps).containsExactly(3_000L)
        }

    @Test
    fun `a second server error is a failure, not a third request`() =
        runTest {
            val scripted = Scripted(listOf(500 to null, 502 to null, 200 to null))

            val result = client(scripted).fetch("UCa")

            assertThat(result.isFailure).isTrue()
            assertThat(scripted.calls).isEqualTo(2)
        }

    @Test
    fun `a dropped connection is retried`() =
        runTest {
            val scripted = Scripted(listOf(IOException("reset"), 200 to null))

            assertThat(client(scripted).fetch("UCa").isSuccess).isTrue()
            assertThat(scripted.calls).isEqualTo(2)
        }

    @Test
    fun `a 404 asks the uploads playlist feed once, without waiting (1946)`() =
        runTest {
            val scripted = Scripted(listOf(404 to null, 200 to null))

            val result = client(scripted).fetch("UCabc")

            assertThat(result.isSuccess).isTrue()
            assertThat(scripted.urls)
                .containsExactly(
                    "https://www.youtube.com/feeds/videos.xml?channel_id=UCabc",
                    "https://www.youtube.com/feeds/videos.xml?playlist_id=UUabc",
                ).inOrder()
            assertThat(sleeps).isEmpty()
        }

    @Test
    fun `a 404 from both feeds is final`() =
        runTest {
            val scripted = Scripted(listOf(404 to null, 404 to null, 200 to null))

            val result = client(scripted).fetch("UCabc")

            assertThat(result.isFailure).isTrue()
            assertThat(scripted.calls).isEqualTo(2)
        }

    @Test
    fun `an id that is not a channel id gets no playlist retry`() =
        runTest {
            val scripted = Scripted(listOf(404 to null, 200 to null))

            assertThat(client(scripted).fetch("legacy").isFailure).isTrue()
            assertThat(scripted.calls).isEqualTo(1)
        }

    private companion object {
        const val FEED = "<feed xmlns=\"http://www.w3.org/2005/Atom\"></feed>"
    }
}
