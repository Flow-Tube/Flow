package io.github.aedev.flow.utils

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.util.LruCache
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MIN_CONFIDENCE = 0.5f

/**
 * Whether a piece of text is in a language the viewer doesn't use, from Android's on-device
 * language detector (Android 10+, no network). Unsure counts as not foreign.
 */
object ForeignLanguage {
    private const val CACHE_SIZE = 1_000
    private const val PARALLELISM = 2

    private val cache = LruCache<String, Boolean>(CACHE_SIZE)
    private val detector = Dispatchers.Default.limitedParallelism(PARALLELISM)

    fun cached(key: String): Boolean? = cache.get(key)

    /** The detector blocks, and the system logs a warning when it runs on the main thread. */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun isForeign(
        context: Context,
        key: String,
        text: String,
    ): Boolean {
        cache.get(key)?.let { return it }
        val foreign = withContext(detector) { detect(context, text) }
        cache.put(key, foreign)
        return foreign
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun detect(
        context: Context,
        text: String,
    ): Boolean {
        val classifier = context.getSystemService(TextClassificationManager::class.java)?.textClassifier ?: return false
        val language = runCatching { classifier.detectLanguage(TextLanguage.Request.Builder(text).build()) }.getOrNull() ?: return false
        val hypotheses =
            (0 until language.localeHypothesisCount).map { index ->
                val locale = language.getLocale(index)
                locale.language to language.getConfidenceScore(locale)
            }
        return isForeignLanguage(hypotheses, viewerLanguages(context))
    }

    /** The app's UI language and every language in the system list. */
    private fun viewerLanguages(context: Context): Set<String> {
        val app = context.resources.configuration.locales
        val system = Resources.getSystem().configuration.locales
        return (app.languages() + system.languages()).map(::baseLanguage).filter(String::isNotEmpty).toSet()
    }

    private fun LocaleList.languages(): List<String> = (0 until size()).map { get(it).language }
}

/** The most likely language, when the detector is sure enough of it, is none of [viewerLanguages]. */
internal fun isForeignLanguage(
    hypotheses: List<Pair<String, Float>>,
    viewerLanguages: Set<String>,
): Boolean {
    val (language, confidence) = hypotheses.maxByOrNull { it.second } ?: return false
    val base = baseLanguage(language)
    return confidence >= MIN_CONFIDENCE && base.isNotEmpty() && base !in viewerLanguages
}

/** "pt-BR" and "pt" are one language here; Java still spells Hebrew, Indonesian and Yiddish the old way. */
internal fun baseLanguage(tag: String): String =
    when (val base = tag.substringBefore('-').substringBefore('_').lowercase()) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        "und" -> ""
        else -> base
    }
