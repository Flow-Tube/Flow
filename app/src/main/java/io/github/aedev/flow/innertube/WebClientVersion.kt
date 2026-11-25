package io.github.aedev.flow.innertube

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The version one web client's own site currently sends, read once per process from that site's
 * `sw.js`. A pinned version slowly goes stale and YouTube starts answering it differently, so the
 * live one is preferred and the pinned one is only the fallback.
 *
 * @param clientName when the worker names a different client (a redirect to another host), its
 *   version is not this client's and the read counts as a failure.
 */
internal class WebClientVersion(
    private val fetchServiceWorker: suspend () -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val clientName: String? = null,
) {
    private val mutex = Mutex()

    @Volatile
    private var version: String? = null
    private var failedAtMs: Long? = null

    /** The live version if it has been read already; never fetches. */
    fun known(): String? = version

    suspend fun get(fallback: String): String =
        mutex.withLock {
            version?.let { return it }
            val lastFailure = failedAtMs
            if (lastFailure != null && nowMs() - lastFailure < RETRY_AFTER_MS) return fallback
            val fetched =
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    runCatching { parse(fetchServiceWorker(), clientName) }.getOrNull()
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

        private val ClientNamePattern = Regex("""INNERTUBE_CLIENT_NAME":"([A-Z_]+)"""")

        fun parse(
            body: String,
            clientName: String? = null,
        ): String? {
            val servedName = ClientNamePattern.find(body)?.groupValues?.get(1)
            if (clientName != null && servedName != null && servedName != clientName) return null
            return Patterns.firstNotNullOfOrNull { it.find(body)?.groupValues?.get(1) }?.takeIf { it.isNotBlank() }
        }
    }
}
