package io.github.aedev.flow.ui.components.shared

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.translation.TextTranslator
import io.github.aedev.flow.data.translation.Translation
import io.github.aedev.flow.data.translation.TranslationResult
import io.github.aedev.flow.utils.TranslateIntents

/** The activity's translator, installed by MainActivity; null in previews, where Translate hands text to an app. */
val LocalTextTranslator = staticCompositionLocalOf<TextTranslator?> { null }

/** Translate on a comment or description: in place through the viewer's server, or handed to their translator app (#892). */
@Stable
class TranslateAction internal constructor(
    private val context: Context,
    private val translator: TextTranslator?,
) {
    /** The translation to show in place, or null when the text went to an app or the server failed (already said). */
    suspend fun translate(text: String): Translation? {
        val result = translator?.translate(text) ?: TranslationResult.NoServer
        return when (result) {
            is TranslationResult.Done -> {
                result.translation
            }

            TranslationResult.NoServer -> {
                TranslateIntents.open(context, text)
                null
            }

            is TranslationResult.Failed -> {
                Toast.makeText(context, context.getString(R.string.translation_failed, result.host), Toast.LENGTH_LONG).show()
                null
            }
        }
    }
}

@Composable
fun rememberTranslateAction(): TranslateAction {
    val context = LocalContext.current
    val translator = LocalTextTranslator.current
    return remember(context, translator) { TranslateAction(context, translator) }
}
