package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.encodePayload
import io.github.aedev.flow.sync2.crypto.lanFrameAad
import io.github.aedev.flow.sync2.crypto.seal
import io.github.aedev.flow.sync2.crypto.u64
import io.github.aedev.flow.sync2.lan.LanLink
import io.github.aedev.flow.sync2.lan.MAX_LAN_PAYLOAD
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LanLinkTest {
    private val key = ByteArray(32) { 1 }

    /** A link whose other side sent one frame carrying [size] bytes of content. */
    private fun receiving(size: Int) =
        LanLink(
            object : SyncConnection {
                override suspend fun send(bytes: ByteArray) = Unit

                override suspend fun receive() = u64(0) + seal(key, lanFrameAad(0), encodePayload(ByteArray(size)))

                override fun close() = Unit
            },
            peer = "peer",
            peerPort = null,
            sendKey = key,
            receiveKey = key,
        )

    @Test
    fun `a frame whose payload inflates past the limit is refused`() =
        runTest {
            assertEquals(MAX_LAN_PAYLOAD, receiving(MAX_LAN_PAYLOAD).receive()?.size)
            val error = runCatching { receiving(MAX_LAN_PAYLOAD + 1).receive() }.exceptionOrNull()
            assertEquals("length", (error as ProtocolException).error.code)
        }
}
