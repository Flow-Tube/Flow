package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.encodePayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Random
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** What this device writes reads back, and what it reads stays within its limit (spec/crypto.md, Payload). */
class PayloadTest {
    private fun randomBytes(size: Int) = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }

    private val mebibyte = 1024 * 1024

    @Test
    fun what_the_encoder_writes_reads_back() {
        for (content in listOf(ByteArray(0), "short".toByteArray(), ByteArray(mebibyte), randomBytes(mebibyte))) {
            assertArrayEquals(content, decodePayload(encodePayload(content)))
        }
    }

    @Test
    fun the_encoder_writes_one_member_with_no_flags() {
        val payload = encodePayload("short".toByteArray())
        assertArrayEquals(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0), payload.copyOfRange(4, 8))
        val length = ByteBuffer.wrap(payload, 0, 4).int
        assertEquals("the trailer holds the length", 5, payload[4 + length - 4].toInt())
    }

    @Test
    fun content_up_to_the_limit_reads_and_past_it_is_refused() {
        val limit = 64 * mebibyte
        assertEquals(limit, decodePayload(encodePayload(ByteArray(limit))).size)
        val error = runCatching { decodePayload(encodePayload(ByteArray(limit + 1))) }.exceptionOrNull()
        assertEquals(ProtocolError.PAYLOAD, (error as? ProtocolException)?.error)
    }

    private fun assertRefused(payload: ByteArray) {
        val error = runCatching { decodePayload(payload) }.exceptionOrNull()
        assertEquals("$error", ProtocolError.PAYLOAD, (error as? ProtocolException)?.error)
    }

    private val header = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0)

    private fun framed(body: ByteArray) = ByteBuffer.allocate(4).putInt(body.size).array() + body

    @Test
    fun a_body_too_short_for_its_header_and_trailer_is_refused() {
        val whole = encodePayload("short".toByteArray()).drop(4).toByteArray()
        for (size in 0 until 18) assertRefused(framed(whole.copyOf(size)))
    }

    @Test
    fun a_stream_that_isn_t_deflate_is_refused() {
        assertRefused(framed(header + ByteArray(8) { -1 } + ByteArray(8)))
    }

    @Test(timeout = 30_000)
    fun a_stream_cut_off_before_its_end_is_refused() {
        val deflated =
            ByteArrayOutputStream()
                .also { out ->
                    DeflaterOutputStream(out, Deflater(9, true)).use { it.write(randomBytes(4096)) }
                }.toByteArray()
        assertRefused(framed(header + deflated.copyOf(deflated.size / 2)))
    }
}
