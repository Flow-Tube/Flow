package io.github.aedev.flow.data.translation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The kinds of translation server the viewer can run or point Flow at (#892). There is no preset one. */
enum class TranslationServerType(
    val key: String,
) {
    LIBRETRANSLATE("libretranslate"),
    LINGVA("lingva"),
    ;

    companion object {
        fun fromKey(key: String?): TranslationServerType = entries.firstOrNull { it.key == key } ?: LIBRETRANSLATE
    }
}

/** A server as entered in Settings, already checked by [TranslationServer.normalizeUrl]. */
data class TranslationServer(
    val type: TranslationServerType,
    val baseUrl: String,
    val apiKey: String?,
) {
    val host: String get() = baseUrl.toHttpUrlOrNull()?.host ?: baseUrl

    /** The requests for [text], one each; only Lingva, which takes the text in its URL, ever needs more than one. */
    fun requests(
        text: String,
        target: String,
    ): List<Request> =
        when (type) {
            TranslationServerType.LIBRETRANSLATE -> listOf(libreTranslateRequest(text, target))
            TranslationServerType.LINGVA -> lingvaChunks(text).map { lingvaRequest(it, target) }
        }

    private fun libreTranslateRequest(
        text: String,
        target: String,
    ): Request {
        val body = LibreTranslateRequest(q = text, target = targetCode(target, type), apiKey = apiKey?.takeIf { it.isNotBlank() })
        return Request
            .Builder()
            .url(
                baseUrl
                    .toHttpUrl()
                    .newBuilder()
                    .addPathSegment("translate")
                    .build(),
            ).post(TranslationJson.encodeToString(LibreTranslateRequest.serializer(), body).toRequestBody(JsonMediaType))
            .build()
    }

    private fun lingvaRequest(
        text: String,
        target: String,
    ): Request =
        Request
            .Builder()
            .url(
                baseUrl
                    .toHttpUrl()
                    .newBuilder()
                    .addPathSegments("api/v1/auto")
                    .addPathSegment(targetCode(target, type))
                    .addPathSegment(text)
                    .build(),
            ).get()
            .build()

    /** The translated text in a response body, or null when the server answered with something else. */
    fun parse(body: String): String? =
        runCatching {
            when (type) {
                TranslationServerType.LIBRETRANSLATE -> {
                    TranslationJson
                        .decodeFromString(
                            LibreTranslateResponse.serializer(),
                            body,
                        ).translatedText
                }

                TranslationServerType.LINGVA -> {
                    TranslationJson.decodeFromString(LingvaResponse.serializer(), body).translation
                }
            }
        }.getOrNull()

    companion object {
        /**
         * A Lingva request carries its text in the path, and servers cap the URL near 8 KB. Each
         * UTF-8 byte is at most three characters once encoded, so this keeps a piece under 6 KB.
         */
        internal const val LINGVA_CHUNK_BYTES = 2_000
        private const val LONG_LINE_CHARS = 500

        /**
         * The address with a trailing slash, or null when it is not one Flow sends text to: `https`,
         * or plain `http` only to this device or the local network, where a home server often has no
         * certificate. An address typed without a scheme is taken as `https`.
         */
        fun normalizeUrl(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            if (url.query != null || url.fragment != null || url.username.isNotEmpty()) return null
            if (!url.isHttps && !isLocalHost(url.host)) return null
            val text = url.toString()
            return if (text.endsWith("/")) text else "$text/"
        }

        internal fun isLocalHost(host: String): Boolean {
            val name = host.lowercase().trim('[', ']')
            val singleLabel = '.' !in name && ':' !in name
            if (name == "localhost" || singleLabel || LocalSuffixes.any(name::endsWith)) return true
            if (':' in name) return name == "::1" || name.startsWith("fc") || name.startsWith("fd") || name.startsWith("fe80:")
            val parts = name.split('.').map { it.toIntOrNull() ?: return false }
            if (parts.size != 4) return false
            val (a, b) = parts
            return when (a) {
                10, 127 -> true
                192 -> b == 168
                172 -> b in 16..31
                169 -> b == 254
                100 -> b in 64..127
                else -> false
            }
        }

        /**
         * The server's code for a language tag from Settings. Both take plain ISO codes for most
         * languages; they differ on Traditional Chinese, Hebrew, Filipino and Norwegian.
         */
        internal fun targetCode(
            tag: String,
            type: TranslationServerType,
        ): String {
            val parts = tag.replace('_', '-').split('-')
            val language = parts.first().lowercase()
            val traditional =
                language == "zh" && parts.drop(1).any { it.equals("TW", true) || it.equals("HK", true) || it.equals("Hant", true) }
            val lingva = type == TranslationServerType.LINGVA
            return when {
                traditional -> if (lingva) "zh_HANT" else "zt"
                language == "he" || language == "iw" -> if (lingva) "iw" else "he"
                language == "fil" -> "tl"
                language == "nb" || language == "no" -> if (lingva) "no" else "nb"
                else -> language
            }
        }

        /** Whole lines where they fit, so the pieces join back with the line breaks they had. */
        internal fun lingvaChunks(text: String): List<String> {
            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            var bytes = 0
            text.lines().flatMap { it.chunked(LONG_LINE_CHARS) }.forEach { line ->
                val size = line.toByteArray().size
                if (current.isNotEmpty() && bytes + 1 + size > LINGVA_CHUNK_BYTES) {
                    chunks += current.toString()
                    current.clear()
                    bytes = 0
                }
                if (current.isNotEmpty()) {
                    current.append('\n')
                    bytes++
                }
                current.append(line)
                bytes += size
            }
            if (current.isNotBlank()) chunks += current.toString()
            return chunks
        }

        private val LocalSuffixes = listOf(".local", ".lan", ".home.arpa", ".internal")
    }
}

private fun String.toHttpUrl(): HttpUrl = requireNotNull(toHttpUrlOrNull()) { "not a URL: $this" }

private val JsonMediaType = "application/json".toMediaType()

private val TranslationJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

@Serializable
private data class LibreTranslateRequest(
    val q: String,
    val source: String = "auto",
    val target: String,
    val format: String = "text",
    @SerialName("api_key") val apiKey: String? = null,
)

@Serializable
private data class LibreTranslateResponse(
    val translatedText: String,
)

@Serializable
private data class LingvaResponse(
    val translation: String,
)
