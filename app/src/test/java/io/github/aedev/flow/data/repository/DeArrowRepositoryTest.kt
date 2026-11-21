package io.github.aedev.flow.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeArrowRepositoryTest {
    private val requests = AtomicInteger()
    private var nowMs = 1_000L
    private var askedVideoId: String? = null

    private fun repository(
        gate: CountDownLatch? = null,
        answer: () -> Pair<Int, String>,
    ): DeArrowRepository {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    askedVideoId = chain.request().url.queryParameter("videoID")
                    gate?.await(5, TimeUnit.SECONDS)
                    val (code, body) = answer()
                    if (code < 0) throw IOException("offline")
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
        return DeArrowRepository(client, Dispatchers.IO) { nowMs }
    }

    @Test
    fun `a 404 for no submissions is an answer with the random time`() =
        runBlocking {
            val deArrow = repository { 404 to """{"titles":[],"thumbnails":[],"casualVotes":[],"randomTime":0.4,"videoDuration":250.5}""" }

            val content = deArrow.branding("abc123def45")

            assertThat(content?.randomTime).isEqualTo(0.4)
            assertThat(content?.videoDuration).isEqualTo(250.5)
            assertThat(askedVideoId).isEqualTo("abc123def45")
        }

    @Test
    fun `a 404 is cached like any answer`() =
        runBlocking {
            val deArrow = repository { 404 to """{"titles":[],"thumbnails":[],"randomTime":0.4,"videoDuration":null}""" }

            repeat(3) { deArrow.branding("abc123def45") }

            assertThat(requests.get()).isEqualTo(1)
            assertThat(deArrow.cached("abc123def45")?.randomTime).isEqualTo(0.4)
        }

    @Test
    fun `cards asking at the same time share one request`() =
        runBlocking {
            val gate = CountDownLatch(1)
            val deArrow =
                repository(gate) {
                    200 to
                        """{"titles":[{"title":"t","votes":1,"locked":false,"original":false}],"thumbnails":[]}"""
                }

            val asks = (1..5).map { async(Dispatchers.IO) { deArrow.branding("abc123def45") } }
            Thread.sleep(200)
            gate.countDown()

            assertThat(asks.awaitAll().map { it?.titles?.single()?.title }).containsExactly("t", "t", "t", "t", "t")
            assertThat(requests.get()).isEqualTo(1)
        }

    @Test
    fun `a card flung away before the answer still gets it cached`() =
        runBlocking {
            val gate = CountDownLatch(1)
            val deArrow = repository(gate) { 404 to """{"titles":[],"thumbnails":[],"randomTime":0.4}""" }

            val card = launch(Dispatchers.IO) { deArrow.branding("abc123def45") }
            while (requests.get() == 0) Thread.sleep(5)
            card.cancelAndJoin()
            gate.countDown()
            while (!deArrow.isCached("abc123def45")) Thread.sleep(5)
            deArrow.branding("abc123def45")

            assertThat(requests.get()).isEqualTo(1)
        }

    @Test
    fun `a network failure is asked again next time`() =
        runBlocking {
            val deArrow = repository { -1 to "" }

            assertThat(deArrow.branding("abc123def45")).isNull()
            assertThat(deArrow.branding("abc123def45")).isNull()
            assertThat(requests.get()).isEqualTo(2)
        }

    @Test
    fun `a server error is not cached`() =
        runBlocking {
            val deArrow = repository { 503 to "" }

            deArrow.branding("abc123def45")
            deArrow.branding("abc123def45")

            assertThat(requests.get()).isEqualTo(2)
        }

    @Test
    fun `an answer expires after the ttl`() =
        runBlocking {
            val deArrow = repository { 200 to """{"titles":[],"thumbnails":[]}""" }

            deArrow.branding("abc123def45")
            nowMs += DeArrowRepository.TTL_MS + 1
            assertThat(deArrow.cached("abc123def45")).isNull()
            deArrow.branding("abc123def45")

            assertThat(requests.get()).isEqualTo(2)
        }

    @Test
    fun `unknown fields are ignored and broken bodies parse to nothing`() {
        assertThat(DeArrowRepository.parse("""{"titles":[],"thumbnails":[],"extra":{"a":1}}""")).isNotNull()
        assertThat(DeArrowRepository.parse("<html>")).isNull()
    }
}
