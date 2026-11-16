package io.github.aedev.flow.player.datasource

import io.github.aedev.flow.innertube.models.YouTubeClient

/**
 * How a `googlevideo` request must look for the client that minted its URL (the URL's `c=`
 * parameter). Playback and downloads both fetch through this, because a user agent or header set
 * that does not match the minting client is a known cause of mid-stream 403s.
 */
object GoogleVideoRequestPolicy {
    /** Clients whose URLs a browser minted, and which therefore send browser CORS headers. */
    private val WEB_FAMILY_CLIENTS =
        setOf(
            "WEB",
            "MWEB",
            "WEB_REMIX",
            "WEB_CREATOR",
            "WEB_EMBEDDED_PLAYER",
            "TVHTML5",
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        )

    private const val CAPTION_PATH = "/api/timedtext"

    /** Whether [path] is YouTube's caption endpoint, which [captionHeaders] describes. */
    fun isCaptionRequest(path: String?): Boolean = path == CAPTION_PATH

    /**
     * A caption URL carries no `c=`, so [headers] would take it for a web page's request and add
     * Origin, Referer and `Sec-Fetch-*`, which no browser sends without the web player's PO token.
     * NewTube fetches the same signed URL with none of them. Encoding is left to the transport:
     * caption text compresses well.
     */
    fun captionHeaders(): Map<String, String> = mapOf("Accept" to "*/*")

    /**
     * Whether a caption at [path] goes over Android's own Cronet rather than OkHttp. A phone A/B on
     * one network and video got a translated caption over it 3 times out of 3 and a 429 over OkHttp
     * every time (#1369). The platform engine takes no proxy, so with the app proxy on ([proxied])
     * captions stay on OkHttp rather than leave the device outside it.
     */
    fun captionsOverPlatformCronet(
        path: String?,
        proxied: Boolean,
    ): Boolean = !proxied && isCaptionRequest(path)

    fun userAgent(
        clientName: String?,
        fallback: String,
    ): String =
        when (clientName?.uppercase()) {
            "IOS" -> YouTubeClient.IPADOS.userAgent
            "ANDROID", "ANDROID_CREATOR" -> YouTubeClient.ANDROID.userAgent
            "ANDROID_VR" -> YouTubeClient.ANDROID_VR_1_61_48.userAgent
            "VISIONOS" -> YouTubeClient.VISIONOS.userAgent
            "TVHTML5" -> YouTubeClient.TV_TIZEN.userAgent
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER.userAgent
            "MWEB" -> YouTubeClient.USER_AGENT_MWEB
            "WEB", "WEB_REMIX" -> YouTubeClient.USER_AGENT_WEB
            else -> fallback
        }

    /**
     * `Origin`, `Referer` and the `Sec-Fetch-*` triple are browser-only: a real visionOS or Android
     * VR client sends none of them, so they go only on URLs a web client minted.
     *
     * [allowGzip] is for HLS playlists and DASH manifests: a live playlist is about 880 KB as text and
     * 12 KB gzipped, reloaded every few seconds (#1042). Leaving the header out lets OkHttp ask for
     * gzip and decompress it.
     */
    fun headers(
        clientName: String?,
        allowGzip: Boolean = false,
    ): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        // Media is already compressed and served in byte ranges, so identity keeps the range
        // arithmetic exact rather than saving anything.
        if (!allowGzip) headers["Accept-Encoding"] = "identity"
        headers["Accept"] = "*/*"
        val client = clientName?.uppercase()
        if (client == null || client in WEB_FAMILY_CLIENTS) {
            headers["Origin"] = "https://www.youtube.com"
            headers["Referer"] = "https://www.youtube.com/"
            headers["Sec-Fetch-Dest"] = "empty"
            headers["Sec-Fetch-Mode"] = "cors"
            headers["Sec-Fetch-Site"] = "cross-site"
        }
        return headers
    }
}
