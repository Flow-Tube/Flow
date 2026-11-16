package io.github.aedev.flow.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.widget.Toast
import io.github.aedev.flow.R

/**
 * Hands text to the translator app the viewer has installed. Android's Translate action comes
 * first; few apps take it, so the text-processing action most translators register is the fallback.
 */
object TranslateIntents {
    /** The intent to start, or null when no installed app takes text to translate. */
    fun forText(
        context: Context,
        text: String,
    ): Intent? {
        val packageManager = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val translate = Intent(Intent.ACTION_TRANSLATE).putExtra(Intent.EXTRA_TEXT, text)
            if (packageManager.activitiesFor(translate).isNotEmpty()) return translate
        }
        val process =
            Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val apps = packageManager.activitiesFor(process).filter { it.activityInfo.packageName != context.packageName }
        return when (apps.size) {
            0 -> null
            1 -> process.setClassName(apps[0].activityInfo.packageName, apps[0].activityInfo.name)
            else -> Intent.createChooser(process, context.getString(R.string.translate_with))
        }
    }

    /** Opens the translator, or says there is none. */
    fun open(
        context: Context,
        text: String,
    ) {
        val intent = text.takeIf { it.isNotBlank() }?.let { forText(context, it) }
        val started =
            intent != null &&
                try {
                    context.startActivity(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
        if (!started) Toast.makeText(context, R.string.translate_no_app, Toast.LENGTH_LONG).show()
    }

    private fun PackageManager.activitiesFor(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, 0)
        }
}
