package io.github.aedev.flow.innertube

import io.github.aedev.flow.innertube.models.YouTubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * WEB, MWEB and WEB_REMIX as their own sites send them today. Each site serves its client's
 * version in its `sw.js` (www, m and music respectively), so every web-family request goes out
 * with the version YouTube's own page would use, and the version pinned in [YouTubeClient] is
 * only the fallback when that read fails. Every other client is returned unchanged.
 */
internal class WebClients(
    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    fetchServiceWorker: suspend (origin: String, userAgent: String) -> String,
) {
    private val reads = mutableMapOf<String, Job>()

    private val versions: Map<String, WebClientVersion> =
        listOf(
            YouTubeClient.WEB to YouTubeClient.ORIGIN_YOUTUBE,
            YouTubeClient.MWEB to ORIGIN_MOBILE_YOUTUBE,
            YouTubeClient.WEB_REMIX to YouTubeClient.ORIGIN_YOUTUBE_MUSIC,
        ).associate { (client, origin) ->
            client.clientName to
                WebClientVersion(
                    fetchServiceWorker = { fetchServiceWorker(origin, client.userAgent) },
                    clientName = client.clientName,
                )
        }

    suspend fun live(client: YouTubeClient): YouTubeClient {
        val version = versions[client.clientName] ?: return client
        return client.copy(clientVersion = version.get(fallback = client.clientVersion))
    }

    /**
     * [client] with its live version once that has been read, else as pinned while the read runs in
     * the background, so a first request never waits on a worker download.
     */
    fun current(client: YouTubeClient): YouTubeClient {
        val version = versions[client.clientName] ?: return client
        version.known()?.let { return client.copy(clientVersion = it) }
        synchronized(reads) {
            if (reads[client.clientName]?.isActive != true) {
                reads[client.clientName] = background.launch { version.get(fallback = client.clientVersion) }
            }
        }
        return client
    }

    /** The version [client] is sent with right now, without fetching. */
    fun known(client: YouTubeClient): String = versions[client.clientName]?.known() ?: client.clientVersion

    private companion object {
        const val ORIGIN_MOBILE_YOUTUBE = "https://m.youtube.com"
    }
}
