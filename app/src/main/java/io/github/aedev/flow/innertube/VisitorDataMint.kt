package io.github.aedev.flow.innertube

import kotlinx.coroutines.CancellationException

/**
 * Mints a fresh visitorData. `visitor_id` answers a context-only POST with the value in a named
 * field, `responseContext.visitorData`, in under a kilobyte; `sw.js_data` is read by position, so it
 * stays as the fallback for when the first one fails or comes back blank.
 */
internal class VisitorDataMint(
    private val visitorId: suspend () -> String?,
    private val swJsData: suspend () -> String?,
) {
    suspend fun mint(): String =
        attempt(visitorId)
            ?: attempt(swJsData)
            ?: throw IllegalStateException("Neither visitor_id nor sw.js_data returned visitorData")

    private suspend fun attempt(source: suspend () -> String?): String? =
        try {
            source()?.takeIf { VISITOR_DATA.containsMatchIn(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    companion object {
        val VISITOR_DATA = Regex("^Cg[ts]")
    }
}
