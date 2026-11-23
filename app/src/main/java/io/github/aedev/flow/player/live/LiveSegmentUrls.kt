package io.github.aedev.flow.player.live

import io.github.aedev.flow.player.error.StreamDenialClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The URL each itag of a live session is fetched from, kept valid for as long as the stream plays.
 *
 * Live URLs expire about six hours after the player response that issued them, and a 403 is all a
 * segment gets after that. The URLs are replaced once, in the background, when expiry is under
 * [REFRESH_MARGIN_MS] away or a segment is refused, so a refresh never blocks a segment load.
 * Only one refresh runs at a time.
 */
class LiveSegmentUrls(
    urls: Map<Int, String>,
    private val scope: CoroutineScope,
    private val refresh: suspend () -> Map<Int, String>?,
    private val serverNow: () -> Long,
) {
    private val urls = ConcurrentHashMap(urls)
    private val refreshing = AtomicBoolean(false)

    @Volatile
    private var expiresAtMs = earliestExpiryMs(urls.values)

    /** The current URL for [itag], starting a refresh first when this one is about to expire. */
    fun urlFor(itag: Int): String? {
        if (serverNow() >= expiresAtMs - REFRESH_MARGIN_MS) startRefresh()
        return urls[itag]
    }

    /** A segment was refused: its URL is no longer honoured, whatever its expiry says. */
    fun onRefused() = startRefresh()

    private fun startRefresh() {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val fresh = refresh().orEmpty().filterKeys(urls::containsKey)
                if (fresh.isNotEmpty()) {
                    urls.putAll(fresh)
                    expiresAtMs = earliestExpiryMs(urls.values)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                refreshing.set(false)
            }
        }
    }

    companion object {
        const val REFRESH_MARGIN_MS = 20 * 60_000L

        private fun earliestExpiryMs(urls: Collection<String>): Long =
            urls.mapNotNull { StreamDenialClassifier.queryParam(it, "expire")?.toLongOrNull() }.minOrNull()?.times(1_000) ?: Long.MAX_VALUE
    }
}
