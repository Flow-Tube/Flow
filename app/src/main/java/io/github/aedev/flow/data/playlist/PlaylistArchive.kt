package io.github.aedev.flow.data.playlist

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val JSON_EXTENSION = ".json"
private val ZipSignature = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

// Far above any real playlist (5,000 videos is about 2.5 MB), low enough that a wrong file can't exhaust memory.
internal const val PLAYLIST_FILE_MAX_BYTES = 16L * 1024 * 1024

// A whole library unpacked; bounds a zip whose entries are small but many.
internal const val PLAYLIST_ARCHIVE_MAX_BYTES = 64L * 1024 * 1024

/** A playlist file to put in an archive: the playlist's name and the file's JSON. */
data class PlaylistArchiveFile(
    val playlistName: String,
    val text: String,
)

/** Several Flow playlist files in one zip, and reading either a zip or a single file back. */
object PlaylistArchive {
    /** One entry per file, in the given order; playlists that share a name get distinct file names. */
    fun write(
        out: OutputStream,
        files: List<PlaylistArchiveFile>,
    ) {
        val zip = ZipOutputStream(out)
        val taken = HashSet<String>()
        files.forEach { file ->
            zip.putNextEntry(ZipEntry(uniqueName(PlaylistFileCodec.fileName(file.playlistName), taken)))
            zip.write(file.text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        zip.finish()
    }

    /**
     * Hands each playlist file in [input] to [onFile]: every JSON entry of a zip in order, or the
     * whole stream when it is not a zip. False when it can't be read or is larger than any playlist.
     */
    suspend fun read(
        input: InputStream,
        onFile: suspend (text: String, inArchive: Boolean) -> Unit,
    ): Boolean {
        val stream = BufferedInputStream(input)
        stream.mark(ZipSignature.size)
        val head = ByteArray(ZipSignature.size)
        var headLength = 0
        while (headLength < head.size) {
            val read = stream.read(head, headLength, head.size - headLength)
            if (read < 0) break
            headLength += read
        }
        stream.reset()
        if (headLength < head.size || !head.contentEquals(ZipSignature)) {
            onFile(stream.readText(PLAYLIST_FILE_MAX_BYTES) ?: return false, false)
            return true
        }
        return try {
            readZip(ZipInputStream(stream), onFile)
        } catch (_: ZipException) {
            false
        }
    }

    private suspend fun readZip(
        zip: ZipInputStream,
        onFile: suspend (String, Boolean) -> Unit,
    ): Boolean {
        var total = 0L
        while (true) {
            val entry = zip.nextEntry ?: return true
            if (entry.isDirectory || !entry.name.endsWith(JSON_EXTENSION, ignoreCase = true)) continue
            val text = zip.readText(minOf(PLAYLIST_FILE_MAX_BYTES, PLAYLIST_ARCHIVE_MAX_BYTES - total)) ?: return false
            total += text.length
            onFile(text, true)
        }
    }

    private fun uniqueName(
        name: String,
        taken: MutableSet<String>,
    ): String {
        val stem = name.removeSuffix(JSON_EXTENSION)
        var candidate = name
        var copy = 2
        // Case-insensitive: unpacked on a phone or Windows, "Mix" and "mix" would overwrite each other.
        while (!taken.add(candidate.lowercase())) candidate = "$stem (${copy++})$JSON_EXTENSION"
        return candidate
    }

    private fun InputStream.readText(maxBytes: Long): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
