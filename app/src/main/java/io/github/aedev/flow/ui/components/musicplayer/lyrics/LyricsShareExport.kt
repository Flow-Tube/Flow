package io.github.aedev.flow.ui.components.musicplayer.lyrics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.aedev.flow.utils.sharedPngUri
import io.github.aedev.flow.utils.writePngTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SHARE_DIR = "lyrics"
private const val SHARE_FILE = "flow_lyrics.png"
private const val PNG_MIME = "image/png"

/** Sends the card through the system share sheet. False when the image could not be written. */
internal suspend fun shareLyricsCard(
    context: Context,
    image: ImageBitmap,
    chooserTitle: String,
): Boolean {
    val uri = sharedPngUri(context, image.asAndroidBitmap(), SHARE_DIR, SHARE_FILE) ?: return false
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = PNG_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            // The chooser reads its preview from the clip, which also carries the read grant.
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}

/** Writes the card to the document the user picked. */
internal suspend fun saveLyricsCard(
    context: Context,
    image: ImageBitmap,
    target: Uri,
): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(target)?.use { out -> image.asAndroidBitmap().writePngTo(out) } == true
        }.getOrDefault(false)
    }

internal fun lyricsCardFileName(
    title: String,
    artist: String,
): String =
    listOf(artist, title)
        .filter { it.isNotBlank() }
        .joinToString(" - ")
        .replace(Regex("[\\/:*?\"<>|]"), "_")
        .ifBlank { "lyrics" } + ".png"
