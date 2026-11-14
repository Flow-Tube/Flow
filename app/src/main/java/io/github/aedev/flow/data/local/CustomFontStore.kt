package io.github.aedev.flow.data.local

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Why a picked file was not taken as the app font. */
enum class FontImportError { NOT_A_FONT_FILE, TOO_LARGE, UNREADABLE }

sealed interface FontImportResult {
    data class Imported(
        val displayName: String,
    ) : FontImportResult

    data class Rejected(
        val error: FontImportError,
    ) : FontImportResult
}

/** The one custom font file, kept in app storage because a picked document's URI does not stay readable. */
class CustomFontStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR)

    fun file(): File? = dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(BASENAME) }

    suspend fun import(uri: Uri): FontImportResult =
        withContext(Dispatchers.IO) {
            val (name, size) = describe(uri)
            val extension = fontExtension(name) ?: return@withContext FontImportResult.Rejected(FontImportError.NOT_A_FONT_FILE)
            if (size != null && !isWithinSizeLimit(size)) return@withContext FontImportResult.Rejected(FontImportError.TOO_LARGE)
            dir.mkdirs()
            val incoming = File(dir, INCOMING)
            try {
                val copied = copyCapped(uri, incoming)
                if (!isWithinSizeLimit(copied)) {
                    incoming.delete()
                    return@withContext FontImportResult.Rejected(FontImportError.TOO_LARGE)
                }
                if (loadTypeface(incoming) == null) {
                    incoming.delete()
                    return@withContext FontImportResult.Rejected(FontImportError.UNREADABLE)
                }
                file()?.delete()
                if (!incoming.renameTo(File(dir, "$BASENAME.$extension"))) {
                    incoming.delete()
                    return@withContext FontImportResult.Rejected(FontImportError.UNREADABLE)
                }
                FontImportResult.Imported(name.orEmpty())
            } catch (e: IOException) {
                incoming.delete()
                FontImportResult.Rejected(FontImportError.UNREADABLE)
            } catch (e: SecurityException) {
                incoming.delete()
                FontImportResult.Rejected(FontImportError.UNREADABLE)
            }
        }

    fun delete() {
        file()?.delete()
    }

    /**
     * The custom font as a family, or null when the file is gone or no longer reads as a font.
     *
     * A variable font becomes one [Font] per weight, each setting the file's `wght` axis, so a
     * heading asks for 700 and gets the font's own bold. Wrapping the file in a single Typeface
     * drew every weight from its default instance (#1428). A static file stays one Typeface: listed
     * as nine weights it would claim a real bold it lacks and stop Compose synthesising one.
     */
    fun loadFamily(file: File): FontFamily? {
        val base = loadTypeface(file) ?: return null
        if (!base.supportsAxis(WEIGHT_AXIS)) return FontFamily(base)
        val styles = if (base.supportsAxis(ITALIC_AXIS)) listOf(FontStyle.Normal, FontStyle.Italic) else listOf(FontStyle.Normal)
        return runCatching {
            FontFamily(styles.flatMap { style -> FontWeights.map { weight -> Font(file, weight, style) } })
        }.getOrElse { FontFamily(base) }
    }

    /** `Paint` refuses variation settings naming an axis no font in the typeface has. */
    private fun Typeface.supportsAxis(tag: String): Boolean = Paint().also { it.typeface = this }.setFontVariationSettings("'$tag' 400")

    private fun describe(uri: Uri): Pair<String?, Long?> =
        runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(c::getString)
                val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
                name to size
            }
        }.getOrNull() ?: (uri.lastPathSegment to null)

    /** Copies at most one byte past the limit, so an unknown-size stream can't fill the disk. */
    private fun copyCapped(
        uri: Uri,
        target: File,
    ): Long {
        val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("No stream for $uri")
        var total = 0L
        input.use { source ->
            target.outputStream().use { sink ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    total += read
                    if (!isWithinSizeLimit(total)) break
                }
            }
        }
        return total
    }

    /** `Typeface.createFromFile` falls back to the default font for a bad file; the builder returns null instead. */
    private fun loadTypeface(file: File): Typeface? = runCatching { Typeface.Builder(file).build() }.getOrNull()

    companion object {
        const val MAX_SIZE_BYTES = 10L * 1024 * 1024
        private const val DIR = "fonts"
        private const val BASENAME = "custom"
        private const val INCOMING = "incoming.tmp"
        private const val BUFFER_SIZE = 64 * 1024
        private val Extensions = setOf("ttf", "otf")
        private const val WEIGHT_AXIS = "wght"
        private const val ITALIC_AXIS = "ital"
        private val FontWeights =
            listOf(
                FontWeight.W100,
                FontWeight.W200,
                FontWeight.W300,
                FontWeight.W400,
                FontWeight.W500,
                FontWeight.W600,
                FontWeight.W700,
                FontWeight.W800,
                FontWeight.W900,
            )

        /** The lowercase extension when [name] is a TrueType or OpenType file name, else null. */
        fun fontExtension(name: String?): String? =
            name
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase()
                ?.takeIf { it in Extensions }

        fun isWithinSizeLimit(bytes: Long): Boolean = bytes in 1..MAX_SIZE_BYTES
    }
}
