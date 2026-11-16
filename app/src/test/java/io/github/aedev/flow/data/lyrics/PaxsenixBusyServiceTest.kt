package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.lyrics.paxsenix.Paxsenix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections

/** #1844: every candidate id is asked of the same server, so a busy Paxsenix ends the song at once. */
class PaxsenixBusyServiceTest {
    private val askedIds: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val search =
        (1..4).joinToString(prefix = """{"results":[""", postfix = "]}") { id ->
            """{"trackId":$id,"trackName":"Song","artistName":"Band","collectionName":"Album","trackTimeMillis":200000}"""
        }

    private fun Interceptor.Chain.answer(
        status: Int,
        body: String,
        retryAfter: String? = null,
    ): Response =
        Response
            .Builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("test")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun paxsenix(lyrics: Interceptor.Chain.(id: String) -> Response) =
        Paxsenix(
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val url = chain.request().url
                    if (url.host == "itunes.apple.com") {
                        chain.answer(200, search)
                    } else {
                        val id = url.queryParameter("id").orEmpty()
                        askedIds += id
                        chain.lyrics(id)
                    }
                }.build(),
        )

    private suspend fun Paxsenix.lyricsFor() = getLyrics("Song", "Band", 200)

    @Test
    fun `a busy server ends the search after one lyrics request`() =
        runBlocking {
            val failure = paxsenix { answer(503, "busy handling 500 requests", retryAfter = "3") }.lyricsFor().exceptionOrNull()

            assertThat(askedIds).containsExactly("1")
            assertThat(failure).isInstanceOf(LyricsSourceBusyException::class.java)
            assertThat((failure as LyricsSourceBusyException).retryAfterMs).isEqualTo(3_000L)
        }

    @Test
    fun `a timed out request ends the search too`() =
        runBlocking {
            val failure = paxsenix { throw InterruptedIOException("timeout") }.lyricsFor().exceptionOrNull()

            assertThat(askedIds).containsExactly("1")
            assertThat(failure).isInstanceOf(LyricsSourceBusyException::class.java)
            assertThat((failure as LyricsSourceBusyException).retryAfterMs).isNull()
        }

    @Test
    fun `a not found answer moves on to the next id`() =
        runBlocking {
            val lyrics =
                paxsenix { id ->
                    if (id == "2") answer(200, """{"elrc":"[00:01.00]First\n[00:12.00]Second"}""") else answer(404, "{}")
                }.lyricsFor()

            assertThat(askedIds).containsExactly("1", "2", "3", "4").inOrder()
            assertThat(lyrics.getOrNull()).contains("First")
        }

    @Test
    fun `every id missing is a miss rather than a busy service`() =
        runBlocking {
            val failure = paxsenix { answer(404, "{}") }.lyricsFor().exceptionOrNull()

            assertThat(askedIds).containsExactly("1", "2", "3", "4").inOrder()
            assertThat(failure).isInstanceOf(LyricsNotFoundException::class.java)
        }

    @Test
    fun `a search that is given up stops after the request in flight`() =
        runBlocking {
            val search = CompletableDeferred<Job>()
            var answered: Result<String>? = null
            val pax =
                paxsenix {
                    runBlocking { search.await() }.cancel()
                    answer(404, "{}")
                }

            val job = launch(Dispatchers.IO) { answered = pax.lyricsFor() }
            search.complete(job)
            job.join()

            assertThat(askedIds).containsExactly("1")
            assertThat(job.isCancelled).isTrue()
            assertThat(answered).isNull()
        }

    @Test
    fun `a busy source rests two minutes, or longer when the server asks`() {
        assertThat(LyricsChecks.restAfter(LyricsSourceBusyException("busy"))).isEqualTo(120_000L)
        assertThat(LyricsChecks.restAfter(LyricsSourceBusyException("busy", retryAfterMs = 3_000L))).isEqualTo(120_000L)
        assertThat(LyricsChecks.restAfter(LyricsSourceBusyException("busy", retryAfterMs = 300_000L))).isEqualTo(300_000L)
    }

    @Test
    fun `a refused app still rests ten minutes and a plain failure does not rest`() {
        assertThat(LyricsChecks.restAfter(IOException("HTTP 401"))).isEqualTo(600_000L)
        assertThat(LyricsChecks.restAfter(IOException("connection reset"))).isNull()
    }

    @Test
    fun `only paxsenix rests after missing the deadline`() {
        val client = OkHttpClient()

        assertThat(PaxsenixLyricsProvider(client).restsAfterTimeout).isTrue()
        assertThat(LrcLibLyricsProvider(client).restsAfterTimeout).isFalse()
    }
}
