package io.github.aedev.flow.player.dlna

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

/** A song or download on this device (a content:// or file:// uri) that the proxy serves to a renderer. */
internal class LocalCastFile(
    val resolver: ContentResolver,
    val uri: Uri,
    val contentType: String,
)

/** Serves a [LocalCastFile] with the byte ranges renderers seek with, read straight from the file. */
internal object LocalCastSource {
    private const val BUFFER_SIZE = 64 * 1024

    fun serve(
        file: LocalCastFile,
        output: OutputStream,
        rangeHeader: String?,
        head: Boolean,
    ): Boolean {
        val descriptor = runCatching { file.resolver.openAssetFileDescriptor(file.uri, "r") }.getOrNull() ?: return false
        descriptor.use {
            val length = it.length.takeIf { known -> known != AssetFileDescriptor.UNKNOWN_LENGTH } ?: it.parcelFileDescriptor.statSize
            respond(output, file.contentType, length, rangeHeader, head) { it.createInputStream() }
        }
        return true
    }

    fun respond(
        output: OutputStream,
        contentType: String,
        length: Long,
        rangeHeader: String?,
        head: Boolean,
        open: () -> InputStream,
    ) {
        val range = byteRange(rangeHeader, length)
        if (range == null) {
            output.write(
                "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$length\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray(),
            )
            output.flush()
            return
        }
        val partial = rangeHeader != null
        val count = range.last - range.first + 1
        val headers =
            buildString {
                append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                if (partial) append("Content-Range: bytes ${range.first}-${range.last}/$length\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: $count\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Connection: close\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                append("\r\n")
            }
        output.write(headers.toByteArray())
        if (head || count == 0L) {
            output.flush()
            return
        }
        open().use { input ->
            var skipped = 0L
            while (skipped < range.first) {
                val step = input.skip(range.first - skipped)
                if (step <= 0) return
                skipped += step
            }
            val buffer = ByteArray(BUFFER_SIZE)
            var left = count
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read == -1) break
                output.write(buffer, 0, read)
                left -= read
            }
        }
        output.flush()
    }

    /**
     * The bytes a `Range` header asks for out of [length]: all of them without one, the first range
     * of several, or null when none of it lies within the file.
     */
    fun byteRange(
        header: String?,
        length: Long,
    ): LongRange? {
        val whole = 0L until length
        val spec = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) } ?: return whole
        if (length <= 0) return null
        val first = spec.substring(6).substringBefore(',').trim()
        val start = first.substringBefore('-').trim()
        val end = first.substringAfter('-', "").trim()
        return when {
            start.isEmpty() -> {
                val suffix = end.toLongOrNull()?.takeIf { it > 0 } ?: return null
                (length - suffix).coerceAtLeast(0) until length
            }

            else -> {
                val from = start.toLongOrNull() ?: return whole
                if (from >= length) return null
                val to = end.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
                if (to < from) null else from..to
            }
        }
    }
}
