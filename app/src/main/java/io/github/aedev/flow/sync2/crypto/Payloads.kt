package io.github.aedev.flow.sync2.crypto

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private const val MIN_SIZE = 256
private const val MAX_INFLATED = 64 * 1024 * 1024

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
    return try {
        inflate(payload.inputStream(4, length), limit)
    } catch (_: IOException) {
        reject(ProtocolError.PAYLOAD)
    }
}

private fun inflate(
    body: InputStream,
    limit: Int,
): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    GZIPInputStream(body).use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_INFLATED) reject(ProtocolError.PAYLOAD)
            if (out.size() > limit) reject(ProtocolError.LENGTH)
        }
    }
    return out.toByteArray()
}
