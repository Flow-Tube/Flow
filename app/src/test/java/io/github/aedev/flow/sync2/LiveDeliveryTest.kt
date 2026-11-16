package io.github.aedev.flow.sync2

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.sync2.live.LiveTransport
import io.github.aedev.flow.sync2.live.deliverLive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class LiveDeliveryTest {
    private val envelope = byteArrayOf(1, 2, 3)

    private class Kept : LiveTransport {
        val sent = mutableListOf<ByteArray>()

        override suspend fun send(envelope: ByteArray) {
            sent += envelope
        }
    }

    private class Failing(
        private val error: Exception,
    ) : LiveTransport {
        override suspend fun send(envelope: ByteArray) = throw error
    }

    @Test
    fun `a closing session doesn't stop the others`() =
        runTest {
            val before = Kept()
            val closing = Failing(IOException("WebSocket refused a 632-byte frame"))
            val after = Kept()
            val failed = mutableListOf<LiveTransport>()

            deliverLive(listOf(before, closing, after), envelope) { transport, _ -> failed += transport }

            assertThat(before.sent).containsExactly(envelope)
            assertThat(after.sent).containsExactly(envelope)
            assertThat(failed).containsExactly(closing)
        }

    @Test
    fun `cancellation still cancels`() {
        val failed = mutableListOf<LiveTransport>()
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                deliverLive(listOf(Failing(CancellationException("left"))), envelope) { transport, _ -> failed += transport }
            }
        }
        assertThat(failed).isEmpty()
    }

    @Test
    fun `no transports is fine`() =
        runTest {
            deliverLive(emptyList(), envelope) { _, _ -> error("nothing to fail") }
        }
}
