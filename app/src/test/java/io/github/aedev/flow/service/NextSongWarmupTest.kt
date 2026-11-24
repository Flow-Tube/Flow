package io.github.aedev.flow.service

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(UnstableApi::class)
@ExperimentalCoroutinesApi
class NextSongWarmupTest {
    private val dispatcher = StandardTestDispatcher()
    private val opened = mutableListOf<DataSpec>()
    private var bytesRead = 0L
    private var closed = 0
    private var savesData = false

    private val source =
        object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) = Unit

            override fun open(dataSpec: DataSpec): Long {
                opened += dataSpec
                return dataSpec.length
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                if (bytesRead >= STREAM_BYTES) return C.RESULT_END_OF_INPUT
                bytesRead += length
                return length
            }

            override fun getUri(): Uri? = null

            override fun close() {
                closed++
            }
        }

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { mockk(relaxed = true) }
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    private fun TestScope.warmup() = NextSongWarmup({ source }, this, savesData = { savesData }, dispatcher = dispatcher)

    @Test
    fun `the head of the next song is read under its cache key and stops there`() =
        runTest(dispatcher) {
            warmup().warm("abc123")
            advanceUntilIdle()

            val spec = opened.single()
            assertThat(spec.key).isEqualTo("abc123")
            assertThat(spec.position).isEqualTo(0L)
            assertThat(spec.length).isEqualTo(128L * 1024)
            assertThat(bytesRead).isEqualTo(128L * 1024)
            assertThat(closed).isEqualTo(1)
        }

    @Test
    fun `the same next song is read once, a different one again`() =
        runTest(dispatcher) {
            val warmup = warmup()

            warmup.warm("abc123")
            advanceUntilIdle()
            warmup.warm("abc123")
            advanceUntilIdle()
            warmup.warm("def456")
            advanceUntilIdle()

            assertThat(opened.map { it.key }).containsExactly("abc123", "def456").inOrder()
        }

    @Test
    fun `saving data, a local file or no id reads nothing`() =
        runTest(dispatcher) {
            val warmup = warmup()

            warmup.warm("local_42")
            warmup.warm("")
            savesData = true
            warmup.warm("abc123")
            advanceUntilIdle()

            assertThat(opened).isEmpty()
        }

    private companion object {
        const val STREAM_BYTES = 4L * 1024 * 1024
    }
}
