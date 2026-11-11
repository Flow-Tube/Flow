package io.github.aedev.flow.player.stream

/**
 * Decides whether a player response should be played as a live stream rather than as a VOD ladder.
 *
 * Kept pure and separate because getting it wrong is silent and expensive: a VOD misread as live is
 * handed to the player with no video/audio formats at all, so quality and audio-track selection come
 * up empty rather than failing loudly.
 */
object LiveDetectionRules {
    /**
     * @param hasHlsManifest whether `streamingData.hlsManifestUrl` is present. Deliberately weak
     *   evidence: Apple-family clients (VISIONOS, IOS) return a manifest for ordinary VODs as well
     *   as for live, so it only implies live when there is no adaptive ladder to play instead.
     * @param hasAdaptiveFormats whether the response carries any adaptive formats.
     */
    fun isLiveNow(
        isLive: Boolean?,
        isPostLiveDvr: Boolean?,
        hasLiveStreamability: Boolean,
        hasHlsManifest: Boolean,
        hasAdaptiveFormats: Boolean,
    ): Boolean {
        if (isLive == true || isPostLiveDvr == true) return true
        if (hasLiveStreamability) return true
        return hasHlsManifest && !hasAdaptiveFormats
    }

    /**
     * Whether a live HLS manifest was signed for a fixed stretch behind the live edge rather than
     * for everything YouTube keeps seekable. The stretch is part of the signed URL, so a longer one
     * can only come from a client that is not handed the cap.
     */
    fun hasCappedDvrWindow(hlsManifestUrl: String?): Boolean = hlsManifestUrl?.contains(PLAYLIST_DURATION_PARAM) == true

    private const val PLAYLIST_DURATION_PARAM = "/playlist_duration/"
}
