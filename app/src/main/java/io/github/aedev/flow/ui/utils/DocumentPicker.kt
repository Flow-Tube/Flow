package io.github.aedev.flow.ui.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation

/** Android TV's stand-in for the apps it leaves out: it claims the document intents and only says no app can do this. */
private const val TV_FRAMEWORK_STUBS = "com.android.tv.frameworkpackagestubs"

/** Whether [handlers], the packages that answer a document intent, include a real picker rather than TV's stub. */
internal fun hasRealDocumentPicker(handlers: List<String>): Boolean = handlers.any { it != TV_FRAMEWORK_STUBS }

/**
 * Whether rows that open a file or folder picker (import, export, a font, a download folder) can work
 * here. Phones always have one; many TVs ship only the stub, which passes the platform's own check.
 */
@Composable
fun rememberDocumentPickerAvailable(): Boolean {
    val onTv = LocalDpadNavigation.current
    val context = LocalContext.current
    return remember(onTv, context) { !onTv || documentPickerAvailable(context) }
}

private fun documentPickerAvailable(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
    val handlers =
        context.packageManager
            .queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY,
            ).map { it.activityInfo.packageName }
    return hasRealDocumentPicker(handlers)
}
