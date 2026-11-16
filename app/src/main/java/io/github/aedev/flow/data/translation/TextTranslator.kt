package io.github.aedev.flow.data.translation

import androidx.collection.LruCache
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** How Translate works, as stored: the translator app, or the viewer's own server (#892). */
data class TranslationSettings(
    val useServer: Boolean = false,
    val serverType: TranslationServerType = TranslationServerType.LIBRETRANSLATE,
    val serverUrl: String? = null,
    val apiKey: String? = null,
    /** Empty follows the content language. */
    val target: String = "",
) {
    /** The server Translate sends text to, or null when it hands text to an app instead. */
    val server: TranslationServer?
        get() =
            serverUrl
                ?.takeIf { useServer }
                ?.let(TranslationServer::normalizeUrl)
                ?.let { TranslationServer(serverType, it, apiKey) }
}

data class Translation(
    val text: String,
    val host: String,
)

sealed interface TranslationResult {
    data class Done(
        val translation: Translation,
    ) : TranslationResult

    /** Translate is set to the translator app. */
    data object NoServer : TranslationResult

    data class Failed(
        val host: String,
    ) : TranslationResult
}

/** Translates text in place through the viewer's server. One tap sends one request; a repeat comes from memory. */
@Singleton
class TextTranslator
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val preferences: PlayerPreferences,
    ) {
        private val cache = TranslationCache()

        suspend fun translate(text: String): TranslationResult {
            val settings = preferences.translationSettings.first()
            val server = settings.server ?: return TranslationResult.NoServer
            val target = settings.target.ifBlank { YouTube.locale.hl }
            val key = TranslationCache.key(server, target, text)
            cache[key]?.let { return TranslationResult.Done(it) }
            val translated =
                withContext(PerformanceDispatcher.networkIO) {
                    runCatching {
                        server.requests(text, target).map { request ->
                            client.newCall(request).execute().use { response ->
                                response.body
                                    .string()
                                    .takeIf { response.isSuccessful }
                                    ?.let(server::parse)
                                    ?: return@runCatching null
                            }
                        }
                    }.getOrNull()
                } ?: return TranslationResult.Failed(server.host)
            val translation = Translation(translated.joinToString("\n"), server.host)
            cache.put(key, translation)
            return TranslationResult.Done(translation)
        }
    }

internal class TranslationCache {
    private val entries = LruCache<String, Translation>(SIZE)

    operator fun get(key: String): Translation? = entries[key]

    fun put(
        key: String,
        translation: Translation,
    ) {
        entries.put(key, translation)
    }

    companion object {
        private const val SIZE = 200

        fun key(
            server: TranslationServer,
            target: String,
            text: String,
        ): String = listOf(server.type.key, server.baseUrl, target, text).joinToString("\u0000")
    }
}

/** A video's title and description translated together, in one request. */
data class TitledTranslation(
    val title: String,
    val body: String,
    val host: String,
)

/** The title on the first line, so [splitTitle] can take it back off. */
fun titledText(
    title: String,
    body: String,
): String = if (body.isBlank()) title.trim() else title.trim() + "\n\n" + body

fun Translation.splitTitle(): TitledTranslation =
    TitledTranslation(
        title = text.substringBefore('\n').trim(),
        body = text.substringAfter('\n', "").trimStart('\n'),
        host = host,
    )
