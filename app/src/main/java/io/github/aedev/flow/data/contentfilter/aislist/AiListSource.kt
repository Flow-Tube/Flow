package io.github.aedev.flow.data.contentfilter.aislist

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

/**
 * Where the list comes from. The default is Flow's own mirror. A custom [baseUrl] with no
 * [publicKey] must serve files Flow's key signed (a plain mirror); a self-hosted pipeline signs with
 * its own key, which the viewer enters, and then only that key is trusted for it.
 */
class AiListSource private constructor(
    val baseUrl: String,
    val publicKey: ByteArray?,
) {
    val isDefault: Boolean get() = baseUrl == DEFAULT_URL && publicKey == null

    /** Shown in Settings: the host of a custom source. */
    val host: String get() = baseUrl.toHttpUrlOrNull()?.host ?: baseUrl

    /** Ties a stored list to the source and key it came from, so switching starts from a fresh snapshot. */
    internal val id: String get() = baseUrl + "#" + (publicKey?.sha256Hex() ?: "builtin")

    internal fun keyFor(keyId: String): ByteArray? = publicKey ?: AiListKeys.trusted[keyId]

    override fun equals(other: Any?): Boolean = other is AiListSource && other.id == id

    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val DEFAULT_URL = "https://ailist.flow-tube.org/"
        private const val KEY_SIZE = 32

        val DEFAULT = AiListSource(DEFAULT_URL, null)

        /** The source from stored settings; anything invalid falls back to the default. */
        fun from(
            url: String?,
            key: String?,
        ): AiListSource {
            if (url.isNullOrBlank()) return DEFAULT
            val base = normalizeUrl(url) ?: return DEFAULT
            if (key.isNullOrBlank()) return AiListSource(base, null)
            val decoded = decodeKey(key) ?: return DEFAULT
            return AiListSource(base, decoded)
        }

        /** An `https://` URL with a trailing slash, or null when [input] is not one. */
        fun normalizeUrl(input: String): String? {
            val url = input.trim().toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.query != null || url.fragment != null) return null
            val text = url.toString()
            return if (text.endsWith("/")) text else "$text/"
        }

        /**
         * An Ed25519 public key as the pipeline's `keys/<id>.pub.pem` holds it, or its raw 32 bytes in
         * base64. A PEM body is the key's SubjectPublicKeyInfo: a fixed 12-byte prefix, then the key.
         */
        fun decodeKey(input: String): ByteArray? {
            val body =
                input
                    .lineSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("-----") }
                    .joinToString("")
            val bytes = runCatching { Base64.getDecoder().decode(body) }.getOrNull() ?: return null
            return when {
                bytes.size == KEY_SIZE -> {
                    bytes
                }

                bytes.size == SPKI_PREFIX.size + KEY_SIZE && bytes.copyOf(SPKI_PREFIX.size).contentEquals(SPKI_PREFIX) -> {
                    bytes.copyOfRange(SPKI_PREFIX.size, bytes.size)
                }

                else -> {
                    null
                }
            }
        }

        private val SPKI_PREFIX =
            byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    }
}
