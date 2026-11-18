package io.github.aedev.flow.innertube

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The WEB client version YouTube's own pages currently send, read once per process from
 * `www.youtube.com/sw.js`. A pinned version slowly goes stale and YouTube starts answering it
 * differently, so the live one is preferred and the pinned one is only the fallback.
 */
internal class WebClientVersion(
    private val fetchServiceWorker: suspend () -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var version: String? = null
    private var failedAtMs: Long? = null

    suspend fun get(fallback: String): String =
        mutex.withLock {
            version?.let { return it }
            val lastFailure = failedAtMs
            if (lastFailure != null && nowMs() - lastFailure < RETRY_AFTER_MS) return fallback
            val fetched =
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    runCatching { parse(fetchServiceWorker()) }.getOrNull()
                }
            if (fetched != null) {
                version = fetched
                failedAtMs = null
            } else {
                failedAtMs = nowMs()
            }
            fetched ?: fallback
        }

    companion object {
        private const val FETCH_TIMEOUT_MS = 6_000L
        private const val RETRY_AFTER_MS = 10 * 60_000L

        private val Patterns =
            listOf(
                Regex("""INNERTUBE_CONTEXT_CLIENT_VERSION":"([0-9.]+?)""""),
                Regex("""innertube_context_client_version":"([0-9.]+?)""""),
                Regex("""client\.version=([0-9.]+)"""),
            )

        fun parse(body: String): String? = Patterns.firstNotNullOfOrNull { it.find(body)?.groupValues?.get(1) }?.takeIf { it.isNotBlank() }
    }
}
