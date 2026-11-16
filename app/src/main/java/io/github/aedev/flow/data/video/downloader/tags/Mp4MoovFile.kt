package io.github.aedev.flow.data.video.downloader.tags

import io.github.aedev.flow.data.video.downloader.tags.Mp4Boxes.Span
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Replaces the `moov` box of an MP4 file without moving `mdat`, so every chunk offset stays valid:
 * a trailing `moov` is rewritten in place, and a `moov` that precedes `mdat` is appended at the end
 * of the file while the old one becomes a `free` box of the same size. The file is edited in place.
 */
internal object Mp4MoovFile {
    private const val MOOV = "moov"
    private const val FREE = "free"
    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024
    private const val UINT32_MAX = 0xFFFFFFFFL

    /** [edit] gets the whole `moov` box and its header size, and returns the new box or null to leave the file alone. */
    @Throws(IOException::class)
    fun edit(
        file: File,
        edit: (moov: ByteArray, headerSize: Int) -> ByteArray?,
    ) {
        RandomAccessFile(file, "rw").use { raf ->
            val channel = raf.channel
            val boxes = Mp4Boxes.topLevel(channel)
            val moov = boxes.firstOrNull { it.type == MOOV } ?: throw IOException("No moov box in ${file.name}")
            if (moov.size > MAX_MOOV_BYTES) throw IOException("moov box too large: ${moov.size}")
            val moovBytes = Mp4Boxes.readFully(channel, moov.start, moov.size.toInt())
            val newMoov = edit(moovBytes, moov.headerSize) ?: return
            if (moov.end == channel.size()) {
                writeFully(channel, moov.start, newMoov)
                channel.truncate(moov.start + newMoov.size)
            } else {
                boxes.last().takeIf { it.extendsToEnd }?.let { pinSize(channel, it) }
                writeFully(channel, channel.size(), newMoov)
                writeFully(channel, moov.start + 4, FREE.toByteArray(Charsets.ISO_8859_1))
            }
            channel.force(false)
        }
    }

    private fun pinSize(
        channel: FileChannel,
        box: Span,
    ) {
        if (box.size > UINT32_MAX) throw IOException("Cannot pin the size of open-ended '${box.type}' (${box.size} bytes)")
        writeFully(channel, box.start, ByteBuffer.allocate(4).putInt(box.size.toInt()).array())
    }

    private fun writeFully(
        channel: FileChannel,
        position: Long,
        bytes: ByteArray,
    ) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer, position + buffer.position())
    }
}
