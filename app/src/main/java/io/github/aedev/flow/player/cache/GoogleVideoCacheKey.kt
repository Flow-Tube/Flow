package io.github.aedev.flow.player.cache

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

/**
 * A cache key for a googlevideo stream that survives re-extraction. Every extraction signs a new URL
 * (`expire`, `sig`, `n`, `ip` change), so keying on the URL meant bytes already on disk were never
 * read again. `itag` + `lmt` + `clen` + `xtags` name the encoded file itself; `lmt` is the
 * encode's timestamp in microseconds, so two videos never share it.
 *
 * Null when the URL is not a googlevideo stream or lacks those parameters (live, manifests).
 */
internal fun googleVideoStreamKey(url: String): String? {
    val query = url.substringAfter('?', missingDelimiterValue = "")
    if (query.isEmpty() || !isGoogleVideoHost(url)) return null
    val params =
        query
            .split('&')
            .mapNotNull { pair ->
                val name = pair.substringBefore('=')
                if (name in IdentityParams) name to pair.substringAfter('=', missingDelimiterValue = "") else null
            }.toMap()
    val itag = params["itag"]?.takeIf { it.isNotEmpty() } ?: return null
    val lmt = params["lmt"]?.takeIf { it.isNotEmpty() } ?: return null
    val clen = params["clen"]?.takeIf { it.isNotEmpty() } ?: return null
    return "googlevideo:$itag:$lmt:$clen:${params["xtags"].orEmpty()}"
}

private fun isGoogleVideoHost(url: String): Boolean {
    val host = url.substringAfter("://", missingDelimiterValue = "").substringBefore('/').substringBefore('?')
    return host.endsWith(".googlevideo.com")
}

private val IdentityParams = setOf("itag", "lmt", "clen", "xtags")

/**
 * [CacheKeyFactory.DEFAULT], except googlevideo streams use [googleVideoStreamKey]. DASH sets every
 * segment's key to its representation's signed URL, so a key that is such a URL is read for its
 * identity too; otherwise each re-extraction cached the same stream again under a new key.
 */
@UnstableApi
internal object StreamIdentityCacheKeyFactory : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String {
        val key = dataSpec.key
        val uri = dataSpec.uri.toString()
        return key?.let(::googleVideoStreamKey) ?: key ?: googleVideoStreamKey(uri) ?: uri
    }
}
