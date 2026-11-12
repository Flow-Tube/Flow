package io.github.aedev.flow.player.datasource

import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * A googlevideo url names every server holding its stream in `mn`, the first being the one in its
 * host. When a network can't reach that one, the same signed url opens on the next with only the
 * host changed, so a song moves on instead of waiting on a server that never answers (#1974).
 * Servers are remembered as unreachable for [forgetAfterMs], since a route can come back.
 */
internal class StreamHostFallback(
    private val forgetAfterMs: Long = FORGET_AFTER_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val unreachable = ConcurrentHashMap<String, Long>()

    fun markUnreachable(url: String) {
        hostOf(url)?.let { unreachable[it] = clock() }
    }

    /** [url] on the first server it names that can be reached, or null when none is left. */
    fun route(url: String): String? {
        val host = hostOf(url) ?: return url
        if (!isUnreachable(host)) return url
        val prefix = host.substringBefore(SERVER_SEPARATOR, missingDelimiterValue = "")
        if (prefix.isEmpty() || !host.endsWith(GOOGLEVIDEO_SUFFIX)) return null
        return servers(url)
            .map { "$prefix$SERVER_SEPARATOR$it$GOOGLEVIDEO_SUFFIX" }
            .firstOrNull { it != host && !isUnreachable(it) }
            ?.let { url.replaceFirst("://$host/", "://$it/") }
    }

    private fun isUnreachable(host: String): Boolean {
        val markedAt = unreachable[host] ?: return false
        if (clock() - markedAt < forgetAfterMs) return true
        unreachable.remove(host, markedAt)
        return false
    }

    private fun servers(url: String): List<String> =
        runCatching { URI(url).rawQuery }
            .getOrNull()
            ?.split('&')
            ?.firstOrNull { it.startsWith("mn=") }
            ?.removePrefix("mn=")
            ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    private companion object {
        const val FORGET_AFTER_MS = 10 * 60 * 1000L
        const val SERVER_SEPARATOR = "---"
        const val GOOGLEVIDEO_SUFFIX = ".googlevideo.com"
    }
}
