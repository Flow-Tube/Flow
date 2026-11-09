package io.github.aedev.flow.sync2.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

private const val MIN_SIZE = 256
private const val MAX_INFLATED = 64 * 1024 * 1024
private const val GZIP_HEADER = 10
private const val GZIP_TRAILER = 8
private val GZIP_START = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0)

/** PADMÉ with a 256-byte floor. */
fun paddedSize(n: Int): Int {
    if (n <= MIN_SIZE) return MIN_SIZE
    val e = 31 - Integer.numberOfLeadingZeros(n)
    val s = 32 - Integer.numberOfLeadingZeros(e)
    val mask = (1 shl (e - s)) - 1
    return (n + mask) and mask.inv()
}

fun encodePayload(content: ByteArray): ByteArray {
    val body = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(content) } }.toByteArray()
    val payload = u32(body.size) + body
    return payload.copyOf(paddedSize(payload.size))
}

/** The content of [payload], failing as soon as it inflates past [limit] bytes. */
fun decodePayload(
    payload: ByteArray,
    limit: Int = MAX_INFLATED,
): ByteArray {
    if (payload.size < 4) reject(ProtocolError.PAYLOAD)
    val length = ByteBuffer.wrap(payload, 0, 4).int
    if (length < 0 || length > payload.size - 4) reject(ProtocolError.PAYLOAD)
    if ((4 + length until payload.size).any { payload[it] != 0.toByte() }) reject(ProtocolError.PAYLOAD)
    return inflate(payload, 4, length, limit)
}

/**
 * The one gzip member at [start], [length] bytes long (spec/crypto.md, Payload), failing past [limit] bytes. No library
 * reads gzip this strictly, so the fixed header and trailer are read here; inflating and the CRC stay in the library.
 */
private fun inflate(
    payload: ByteArray,
    start: Int,
    length: Int,
    limit: Int,
): ByteArray {
    if (length < GZIP_HEADER + GZIP_TRAILER) reject(ProtocolError.PAYLOAD)
    if (GZIP_START.indices.any { payload[start + it] != GZIP_START[it] }) reject(ProtocolError.PAYLOAD)
    val inflater = Inflater(true)
    try {
        // The trailer stays in the input: zlib may want a byte past the stream, and what's left shows where it ended.
        inflater.setInput(payload, start + GZIP_HEADER, length - GZIP_HEADER)
        val out = ByteArrayOutputStream()
        val crc = CRC32()
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val read = inflater.inflate(buffer)
            if (read == 0 && !inflater.finished()) reject(ProtocolError.PAYLOAD)
            crc.update(buffer, 0, read)
            out.write(buffer, 0, read)
            if (out.size() > MAX_INFLATED) reject(ProtocolError.PAYLOAD)
            if (out.size() > limit) reject(ProtocolError.LENGTH)
        }
        if (inflater.remaining != GZIP_TRAILER) reject(ProtocolError.PAYLOAD)
        val trailer = ByteBuffer.wrap(payload, start + length - GZIP_TRAILER, GZIP_TRAILER).order(ByteOrder.LITTLE_ENDIAN)
        if (trailer.int != crc.value.toInt() || trailer.int != out.size()) reject(ProtocolError.PAYLOAD)
        return out.toByteArray()
    } catch (_: DataFormatException) {
        reject(ProtocolError.PAYLOAD)
    } finally {
        inflater.end()
    }
}
