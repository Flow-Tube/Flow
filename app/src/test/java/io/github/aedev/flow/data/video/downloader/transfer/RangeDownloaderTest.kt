package io.github.aedev.flow.data.video.downloader.transfer

import androidx.media3.common.C
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Drives the range downloader against an in-process googlevideo stand-in, so no socket is opened. */
@OptIn(ExperimentalCoroutinesApi::class)
class RangeDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val content = Random(7).nextBytes((RangeDownloader.BLOCK_SIZE * 2 + 12_345).toInt())

    private class FakeCdn(
        private val content: ByteArray,
        private val respond: (range: LongRange, attempt: Int) -> Int = { _, _ -> 200 },
        private val truncate: Boolean = false,
    ) : Interceptor {
        val requests = AtomicInteger()

        override fun intercept(chain: Interceptor.Chain): Response {
            val attempt = requests.incrementAndGet()
            val request = chain.request()
            val range =
                request.url
                    .queryParameter("range")
                    ?.split('-')
                    ?.let { (from, to) -> from.toLong()..to.toLong() }
                    ?: 0L..content.lastIndex.toLong()
            val code = respond(range, attempt)
            val builder =
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("fake")
            if (code != 200) return builder.body(ByteArray(0).toResponseBody()).build()
            val end = minOf(range.last, content.lastIndex.toLong()).toInt()
            val slice = content.copyOfRange(range.first.toInt(), end + 1)
            val served = if (truncate) slice.copyOf(slice.size / 2) else slice
            return builder
                .header("Content-Range", "bytes ${range.first}-$end/${content.size}")
                .body(served.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }
    }

    private fun downloader(cdn: FakeCdn) = RangeDownloader({ OkHttpClient.Builder().addInterceptor(cdn).build() }, dispatcher)

    private fun job(
        withClen: Boolean = true,
        threads: Int = 2,
    ): TransferJob {
        val url =
            "https://rr1---sn.googlevideo.com/videoplayback?itag=140&expire=9999999999" + if (withClen) "&clen=${content.size}" else ""
        return TransferJob("vid", listOf(TransferStream(StreamRole.AUDIO, url, folder.newFile("audio.part"), 140, 0L)), threads, "ua")
    }

    @Test
    fun `a stream is fetched block by block into its part file`() =
        runTest(dispatcher) {
            val cdn = FakeCdn(content)
            val job = job()

            val result = downloader(cdn).run(job)

            assertThat(result).isEqualTo(TransferResult.Completed)
            assertThat(
                job.streams
                    .single()
                    .file
                    .readBytes(),
            ).isEqualTo(content)
            assertThat(cdn.requests.get()).isEqualTo(RangeDownloader.blockCount(content.size.toLong()))
            assertThat(job.downloadedBytes).isEqualTo(content.size.toLong())
        }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun `a download waits between blocks while a player loads`() =
        runBlocking {
            val playback = PriorityTaskManager().apply { add(C.PRIORITY_PLAYBACK) }
            val cdn = FakeCdn(content)
            val job = job()
            val downloader = RangeDownloader({ OkHttpClient.Builder().addInterceptor(cdn).build() }, Dispatchers.IO, playback)

            val run = async(Dispatchers.IO) { downloader.run(job) }
            withTimeout(5_000) { while (!job.isYieldingToPlayback) delay(10) }
            assertThat(cdn.requests.get()).isEqualTo(0)
            playback.remove(C.PRIORITY_PLAYBACK)

            assertThat(withTimeout(10_000) { run.await() }).isEqualTo(TransferResult.Completed)
            assertThat(job.isYieldingToPlayback).isFalse()
        }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun `a block cut off for playback resumes from the byte it reached`() =
        runBlocking {
            val playback = PriorityTaskManager()
            val first =
                java.util.concurrent.atomic
                    .AtomicBoolean(true)
            val cdn =
                FakeCdn(content, respond = { _, _ ->
                    if (first.getAndSet(false)) {
                        playback.add(C.PRIORITY_PLAYBACK)
                        Thread {
                            Thread.sleep(300)
                            playback.remove(C.PRIORITY_PLAYBACK)
                        }.start()
                    }
                    200
                })
            val job = job(threads = 1)
            val downloader = RangeDownloader({ OkHttpClient.Builder().addInterceptor(cdn).build() }, Dispatchers.IO, playback)

            assertThat(withTimeout(10_000) { async(Dispatchers.IO) { downloader.run(job) }.await() }).isEqualTo(TransferResult.Completed)
            assertThat(
                job.streams
                    .single()
                    .file
                    .readBytes(),
            ).isEqualTo(content)
            assertThat(cdn.requests.get()).isGreaterThan(RangeDownloader.blockCount(content.size.toLong()))
        }

    @Test
    fun `a block the player already cached is copied, not fetched`() =
        runTest(dispatcher) {
            val cdn = FakeCdn(content)
            val job = job()
            val firstBlock =
                CachedBlocks { _, from, to, sink ->
                    if (from != 0L) return@CachedBlocks false
                    val bytes = content.copyOfRange(0, (to + 1).toInt())
                    sink(bytes, bytes.size)
                    true
                }
            val downloader = RangeDownloader({ OkHttpClient.Builder().addInterceptor(cdn).build() }, dispatcher, cached = firstBlock)

            assertThat(downloader.run(job)).isEqualTo(TransferResult.Completed)
            assertThat(
                job.streams
                    .single()
                    .file
                    .readBytes(),
            ).isEqualTo(content)
            assertThat(cdn.requests.get()).isEqualTo(RangeDownloader.blockCount(content.size.toLong()) - 1)
            assertThat(job.downloadedBytes).isEqualTo(content.size.toLong())
        }

    @Test
    fun `a resumed stream fetches only the blocks it is missing`() =
        runTest(dispatcher) {
            val cdn = FakeCdn(content)
            val job = job()
            val stream = job.streams.single()
            stream.file.writeBytes(content)
            stream.completedBlocks.addAll(listOf(0, 1))

            val result = downloader(cdn).run(job)

            assertThat(result).isEqualTo(TransferResult.Completed)
            assertThat(cdn.requests.get()).isEqualTo(1)
            assertThat(stream.file.readBytes()).isEqualTo(content)
        }

    @Test
    fun `a refused url stops the job and names the url`() =
        runTest(dispatcher) {
            val cdn = FakeCdn(content, respond = { _, _ -> 403 })
            val job = job()

            val result = downloader(cdn).run(job)

            assertThat(result).isInstanceOf(TransferResult.Denied::class.java)
            assertThat((result as TransferResult.Denied).url).contains("itag=140")
            assertThat(cdn.requests.get()).isAtMost(2)
        }

    @Test
    fun `a server that keeps cutting blocks short fails the job instead of writing holes`() =
        runTest(dispatcher) {
            val cdn = FakeCdn(content, truncate = true)
            val job = job(threads = 1)

            val result = downloader(cdn).run(job)

            assertThat(result).isInstanceOf(TransferResult.Failed::class.java)
            assertThat((result as TransferResult.Failed).reason).isEqualTo(TransferFailure.BLOCK_FAILED)
            assertThat(job.streams.single().completedBlocks).isEmpty()
        }

    @Test
    fun `a transient server error is retried`() =
        runTest(dispatcher) {
            val cdn = FakeCdn(content, respond = { _, attempt -> if (attempt == 1) 503 else 200 })
            val job = job(threads = 1)

            assertThat(downloader(cdn).run(job)).isEqualTo(TransferResult.Completed)
            assertThat(
                job.streams
                    .single()
                    .file
                    .readBytes(),
            ).isEqualTo(content)
        }

    @Test
    fun `a stop keeps the finished blocks for a later resume`() =
        runTest(dispatcher) {
            lateinit var job: TransferJob
            val cdn =
                FakeCdn(content, respond = { _, attempt ->
                    if (attempt == 2) job.stop()
                    200
                })
            job = job(threads = 1)

            val result = downloader(cdn).run(job)

            assertThat(result).isEqualTo(TransferResult.Stopped)
            assertThat(job.streams.single().completedBlocks).containsExactly(0)
        }

    @Test
    fun `without clen the size comes from a status-checked probe`() =
        runTest(dispatcher) {
            val job = job(withClen = false)

            assertThat(downloader(FakeCdn(content)).run(job)).isEqualTo(TransferResult.Completed)
            assertThat(job.streams.single().totalBytes).isEqualTo(content.size.toLong())
        }

    @Test
    fun `a probe the server refuses reports the refusal rather than a size`() =
        runTest(dispatcher) {
            val job = job(withClen = false)

            assertThat(downloader(FakeCdn(content, respond = { _, _ -> 403 })).run(job)).isInstanceOf(TransferResult.Denied::class.java)
        }
}
