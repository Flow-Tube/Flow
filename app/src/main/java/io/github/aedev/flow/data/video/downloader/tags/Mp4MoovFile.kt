package io.github.aedev.flow.data.video.downloader.tags

import io.github.aedev.flow.data.video.downloader.tags.Mp4Boxes.Span
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Replaces the `moov` box of an MP4 file without moving `mdat`, so every chunk offset stays valid.
 * The new box goes to the front whenever `free` space there holds it: beside a leading `moov`, or
 * the room the remuxer reserved ahead of `mdat` for a trailing one. Otherwise a trailing `moov` is
 * rewritten in place, and a leading one is appended at the end while the old one becomes `free`.
 */
internal object Mp4MoovFile {
    private const val MOOV = "moov"
    private const val FREE = "free"
    private const val MDAT = "mdat"
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
            val index = boxes.indexOfFirst { it.type == MOOV }
            if (index < 0) throw IOException("No moov box in ${file.name}")
            val moov = boxes[index]
            if (moov.size > MAX_MOOV_BYTES) throw IOException("moov box too large: ${moov.size}")
            val moovBytes = Mp4Boxes.readFully(channel, moov.start, moov.size.toInt())
            val newMoov = edit(moovBytes, moov.headerSize) ?: return
            val (roomStart, roomEnd) = if (moov.end == channel.size()) frontRoom(boxes) else roomAround(boxes, index)
            val spare = roomEnd - roomStart - newMoov.size
            if (spare == 0L || spare in Mp4Boxes.HEADER_SIZE..UINT32_MAX) {
                writeFully(channel, roomStart, newMoov)
                if (spare > 0L) writeFully(channel, roomStart + newMoov.size, freeHeader(spare))
                if (moov.end == channel.size() && roomStart < moov.start) channel.truncate(moov.start)
            } else if (moov.end == channel.size()) {
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

    /** The widest run of `free` boxes ahead of `mdat`, where a trailing `moov` moves to; empty when there is none. */
    private fun frontRoom(boxes: List<Span>): Pair<Long, Long> {
        val mdat = boxes.indexOfFirst { it.type == MDAT }.takeIf { it >= 0 } ?: return 0L to 0L
        var best = 0L to 0L
        var runStart = -1
        for (i in 0..mdat) {
            if (i < mdat && boxes[i].type == FREE) {
                if (runStart < 0) runStart = i
                continue
            }
            if (runStart >= 0) {
                val run = boxes[runStart].start to boxes[i - 1].end
                if (run.second - run.first > best.second - best.first) best = run
                runStart = -1
            }
        }
        return best
    }

    /** Where the first `free` box right before `moov` starts and the last one right after it ends. */
    private fun roomAround(
        boxes: List<Span>,
        index: Int,
    ): Pair<Long, Long> {
        var first = index
        while (first > 0 && boxes[first - 1].type == FREE) first--
        var last = index
        while (last < boxes.lastIndex && boxes[last + 1].type == FREE) last++
        return boxes[first].start to boxes[last].end
    }

    private fun freeHeader(size: Long): ByteArray =
        ByteBuffer
            .allocate(Mp4Boxes.HEADER_SIZE)
            .putInt(size.toInt())
            .put(FREE.toByteArray(Charsets.ISO_8859_1))
            .array()

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
