package io.github.aedev.flow.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent

/**
 * False when no installed app takes speech input, which is common on ROMs without Google apps.
 * Needs the RECOGNIZE_SPEECH entry in the manifest's queries, or Android 11+ hides every such app.
 */
fun canVoiceSearch(context: Context): Boolean {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    val packageManager = context.packageManager
    val match =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)
        }
    return match != null
}

/** Starts free-form speech input through [launch], doing nothing when no app can take it (#2252). */
fun launchVoiceSearch(
    prompt: String,
    launch: (Intent) -> Unit,
) {
    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
    try {
        launch(intent)
    } catch (_: ActivityNotFoundException) {
    }
}
