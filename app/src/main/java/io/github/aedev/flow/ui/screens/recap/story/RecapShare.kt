package io.github.aedev.flow.ui.screens.recap.story

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.aedev.flow.utils.sharedPngUri

private const val SHARE_DIR = "recap"
private const val SHARE_FILE = "flow_recap.png"

/**
 * Shares [image] through the system sheet, with [text] alongside for apps that take only text.
 * The image lives in the app's cache under a single name, so each share replaces the last one.
 * Returns false when the image could not be written, and the caller falls back to text alone.
 */
internal suspend fun shareRecap(
    context: Context,
    image: ImageBitmap?,
    text: String,
    chooserTitle: String,
): Boolean {
    val uri = image?.let { sharedPngUri(context, it.asAndroidBitmap(), SHARE_DIR, SHARE_FILE) }
    val send =
        Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            if (uri != null) {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
    context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return uri != null
}
