package io.github.aedev.flow.player.sabr.integration

import android.net.Uri
import android.util.Log
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.sabr.core.SabrBase64
import io.github.aedev.flow.player.stream.VideoCodecUtils

data class SabrStreamInfo(
    val streamingUrl: String,
    val audioItag: Int,
    val audioLmt: Long,
    val videoItag: Int,
    val videoLmt: Long,
    val audioXtags: String = "",
    val videoXtags: String = "",
    val durationMs: Long,
    val poToken: String = "",
    val visitorId: String = "",
    val ustreamerConfig: ByteArray = ByteArray(0),
    val audioMimeType: String = "",
    val videoMimeType: String = "",
    val audioTrackId: String = "",
    val targetHeight: Int = 0,
    val videoHeight: Int = 0,
    val cpn: String = "",
    // Identity of the client whose player response produced this session. The GVS streaming request
    // has to report the same client that minted the PoToken and the serverAbrStreamingUrl, so it
    // travels with the session rather than being re-assumed downstream. Defaults describe WEB,
    // which was the only possibility before MWEB was added.
    val clientNameId: Int = SabrClientIdentity.WEB_CLIENT_NAME_ID,
    val clientVersion: String = "",
    val clientUserAgent: String = "",
)

object SabrUrlResolver {
    private const val TAG = "SabrUrlResolver"

    private val PREFERRED_AUDIO_ITAGS = listOf(251, 250, 249, 140, 141)

    private val PREFERRED_VIDEO_ITAGS_BY_HEIGHT =
        mapOf(
            2160 to listOf(313, 271),
            1440 to listOf(308, 271),
            1080 to listOf(299, 248, 303),
            720 to listOf(298, 247, 302),
            480 to listOf(135, 244, 218),
            360 to listOf(134, 243),
            240 to listOf(133, 242),
        )

    /**
     * Resolve a SABR session from a player response.
     *
     * @param injectedPoToken the GVS/streaming PoToken (base64) to send in StreamerContext.po_token.
     *   Preferred over any `pot` query param on the URL (which is the non-SABR mechanism).
     * @param injectedVisitorData the session-bound visitorData, must match the one used to mint the token.
     */
    fun resolve(
        playerResponse: PlayerResponse,
        preferredCodec: String? = null,
        injectedPoToken: String? = null,
        injectedVisitorData: String? = null,
        audioTrackId: String? = null,
    ): SabrStreamInfo? {
        val streamingData = playerResponse.streamingData ?: return null
        val sabrUrl = streamingData.serverAbrStreamingUrl
        if (sabrUrl.isNullOrEmpty()) {
            Log.d(TAG, "No serverAbrStreamingUrl in player response")
            return null
        }
        val poToken =
            injectedPoToken?.takeIf { it.isNotEmpty() }
                ?: queryParameter(sabrUrl, "pot").orEmpty()
        val visitorId =
            injectedVisitorData?.takeIf { it.isNotEmpty() }
                ?: playerResponse.responseContext.visitorData.orEmpty()
        if (poToken.isEmpty() || visitorId.isEmpty()) {
            Log.d(TAG, "Skipping SABR: missing token context (pot=${poToken.isNotEmpty()}, visitor=${visitorId.isNotEmpty()})")
            return null
        }

        val adaptiveFormats = streamingData.adaptiveFormats
        if (adaptiveFormats.isEmpty()) {
            Log.w(TAG, "No adaptive formats available")
            return null
        }

        val audioFormats = adaptiveFormats.filter { it.isAudio }
        val videoFormats = adaptiveFormats.filter { !it.isAudio }

        val selectedVideo = selectBestVideo(videoFormats, preferredCodec)
        val selectedAudio = selectedVideo?.let { selectBestAudio(audioFormats, it, audioTrackId) }

        if (selectedAudio == null || selectedVideo == null) {
            Log.w(TAG, "Could not select audio/video format: audio=${selectedAudio != null}, video=${selectedVideo != null}")
            return null
        }

        val durationMs =
            selectedVideo.approxDurationMs?.toLongOrNull()
                ?: selectedAudio.approxDurationMs?.toLongOrNull()
                ?: (
                    playerResponse.videoDetails
                        ?.lengthSeconds
                        ?.toLongOrNull()
                        ?.let { it * 1000L }
                )
                ?: 0L
        val ustreamerConfig = extractUstreamerConfig(playerResponse) ?: return null

        Log.d(
            TAG,
            "Resolved SABR: audioItag=${selectedAudio.itag}, videoItag=${selectedVideo.itag}, " +
                "video=${selectedVideo.width}x${selectedVideo.height}, duration=${durationMs}ms, ustreamer=${ustreamerConfig.size}B",
        )
        // Auto-dubbed videos repeat one audio itag per language, so an empty xtags here means the
        // FormatId we send GVS is ambiguous and the session will die on sabr.no_audio_selected.
        val dubsSharingItag = audioFormats.count { it.itag == selectedAudio.itag }
        if (dubsSharingItag > 1 && selectedAudio.xtags.isNullOrEmpty()) {
            Log.w(
                TAG,
                "Audio itag ${selectedAudio.itag} is shared by $dubsSharingItag formats but carries no " +
                    "xtags — GVS cannot identify the selection",
            )
        }

        return SabrStreamInfo(
            streamingUrl = sabrUrl,
            audioItag = selectedAudio.itag,
            audioLmt = selectedAudio.lastModified ?: 0L,
            videoItag = selectedVideo.itag,
            videoLmt = selectedVideo.lastModified ?: 0L,
            audioXtags = selectedAudio.xtags.orEmpty(),
            videoXtags = selectedVideo.xtags.orEmpty(),
            durationMs = durationMs,
            poToken = poToken,
            visitorId = visitorId,
            ustreamerConfig = ustreamerConfig,
            audioMimeType = selectedAudio.mimeType,
            videoMimeType = selectedVideo.mimeType,
            audioTrackId = selectedAudio.audioTrack?.id.orEmpty(),
            videoHeight = selectedVideo.height ?: 0,
        )
    }

    fun resolveForQuality(
        playerResponse: PlayerResponse,
        targetHeight: Int,
        preferredCodec: String? = null,
        injectedPoToken: String? = null,
        injectedVisitorData: String? = null,
        audioTrackId: String? = null,
    ): SabrStreamInfo? {
        val streamingData = playerResponse.streamingData ?: return null
        val sabrUrl = streamingData.serverAbrStreamingUrl ?: return null
        val poToken =
            injectedPoToken?.takeIf { it.isNotEmpty() }
                ?: queryParameter(sabrUrl, "pot").orEmpty()
        val visitorId =
            injectedVisitorData?.takeIf { it.isNotEmpty() }
                ?: playerResponse.responseContext.visitorData.orEmpty()
        if (poToken.isEmpty() || visitorId.isEmpty()) {
            Log.d(TAG, "Skipping SABR quality resolve: missing token context")
            return null
        }

        val adaptiveFormats = streamingData.adaptiveFormats
        val audioFormats = adaptiveFormats.filter { it.isAudio }
        val videoFormats = adaptiveFormats.filter { !it.isAudio }

        val selectedVideo = selectVideoForHeight(videoFormats, targetHeight, preferredCodec) ?: return null
        val selectedAudio = selectBestAudio(audioFormats, selectedVideo, audioTrackId) ?: return null

        val durationMs =
            selectedVideo.approxDurationMs?.toLongOrNull()
                ?: selectedAudio.approxDurationMs?.toLongOrNull()
                ?: (
                    playerResponse.videoDetails
                        ?.lengthSeconds
                        ?.toLongOrNull()
                        ?.let { it * 1000L }
                )
                ?: 0L
        val ustreamerConfig = extractUstreamerConfig(playerResponse) ?: return null

        return SabrStreamInfo(
            streamingUrl = sabrUrl,
            audioItag = selectedAudio.itag,
            audioLmt = selectedAudio.lastModified ?: 0L,
            videoItag = selectedVideo.itag,
            videoLmt = selectedVideo.lastModified ?: 0L,
            audioXtags = selectedAudio.xtags.orEmpty(),
            videoXtags = selectedVideo.xtags.orEmpty(),
            durationMs = durationMs,
            poToken = poToken,
            visitorId = visitorId,
            ustreamerConfig = ustreamerConfig,
            audioMimeType = selectedAudio.mimeType,
            videoMimeType = selectedVideo.mimeType,
            audioTrackId = selectedAudio.audioTrack?.id.orEmpty(),
            targetHeight = targetHeight,
            videoHeight = selectedVideo.height ?: targetHeight,
        )
    }

    // Decode the base64 `videoPlaybackUstreamerConfig` required by the SABR POST body.
    // A SABR session without it gets a degraded/short-lived response from GVS, so a missing
    // or undecodable config makes SABR unavailable (null) rather than silently degraded.
    private fun extractUstreamerConfig(playerResponse: PlayerResponse): ByteArray? {
        val b64 =
            playerResponse.playerConfig
                ?.mediaCommonConfig
                ?.mediaUstreamerRequestConfig
                ?.videoPlaybackUstreamerConfig
                ?.takeIf { it.isNotEmpty() }
        if (b64 == null) {
            Log.w(TAG, "Skipping SABR: player response has no videoPlaybackUstreamerConfig")
            return null
        }
        val decoded = SabrBase64.decode(b64)
        if (decoded == null || decoded.isEmpty()) {
            Log.w(TAG, "Skipping SABR: failed to decode videoPlaybackUstreamerConfig")
            return null
        }
        return decoded
    }

    private fun queryParameter(
        url: String,
        name: String,
    ): String? =
        try {
            Uri.parse(url).getQueryParameter(name)
        } catch (e: Exception) {
            null
        }

    /**
     * On multi-audio (auto-dub) videos the same itags repeat once per language; picking by
     * itag/bitrate alone selects an arbitrary dub. Restrict to the original track first
     * (track id suffix ".4" = ORIGINAL in InnerTube; isAutoDubbed marks generated dubs).
     */
    private fun isOriginalAudioTrack(format: PlayerResponse.StreamingData.Format): Boolean {
        val track = format.audioTrack ?: return true
        if (track.isAutoDubbed == true) return false
        val id = track.id ?: return true
        return id.substringAfterLast('.', missingDelimiterValue = "4") == "4"
    }

    /** The chosen [audioTrackId] when the response still offers it, otherwise the original track. */
    internal fun selectBestAudio(
        audioFormats: List<PlayerResponse.StreamingData.Format>,
        selectedVideo: PlayerResponse.StreamingData.Format,
        audioTrackId: String? = null,
    ): PlayerResponse.StreamingData.Format? {
        val chosen = audioTrackId?.let { id -> audioFormats.filter { it.audioTrack?.id == id } }.orEmpty()
        val candidates = chosen.ifEmpty { audioFormats.filter(::isOriginalAudioTrack).ifEmpty { audioFormats } }
        val videoMime = selectedVideo.mimeType.lowercase()
        val videoCodec = VideoCodecUtils.codecKeyFromMimeType(selectedVideo.mimeType)
        val wantsMp4Audio = videoMime.contains("mp4") && videoCodec == "h264"
        val compatibleCandidates =
            if (wantsMp4Audio) {
                candidates
                    .filter { it.mimeType.contains("mp4", ignoreCase = true) }
                    .ifEmpty { audioFormats.filter { it.mimeType.contains("mp4", ignoreCase = true) } }
            } else {
                candidates
                    .filter { it.mimeType.contains("webm", ignoreCase = true) }
                    .ifEmpty { audioFormats.filter { it.mimeType.contains("webm", ignoreCase = true) } }
            }
        val audioPool = compatibleCandidates.ifEmpty { candidates }

        if (wantsMp4Audio) {
            return audioPool.maxByOrNull { it.bitrate }
        }

        for (preferredItag in PREFERRED_AUDIO_ITAGS) {
            audioPool.find { it.itag == preferredItag }?.let { return it }
        }
        val webmAudio =
            audioPool
                .filter { it.mimeType.contains("webm", ignoreCase = true) }
                .maxByOrNull { it.bitrate }
        if (webmAudio != null) return webmAudio

        return audioPool.maxByOrNull { it.bitrate }
    }

    private fun selectBestVideo(
        videoFormats: List<PlayerResponse.StreamingData.Format>,
        preferredCodec: String?,
    ): PlayerResponse.StreamingData.Format? =
        videoFormats
            .sortedWith(
                compareByDescending<PlayerResponse.StreamingData.Format> { it.height ?: 0 }
                    .thenBy {
                        VideoCodecUtils.codecRankWithPreference(
                            VideoCodecUtils.codecKeyFromMimeType(it.mimeType),
                            preferredCodec,
                        )
                    }.thenByDescending { it.averageBitrate ?: it.bitrate },
            ).firstOrNull()

    private fun selectVideoForHeight(
        videoFormats: List<PlayerResponse.StreamingData.Format>,
        targetHeight: Int,
        preferredCodec: String?,
    ): PlayerResponse.StreamingData.Format? {
        // Walk the preference in order so a video without the preferred codec still lands on the
        // user's fallback at the requested height instead of dropping to the itag table.
        for (codecKey in VideoCodecUtils.codecPriorityList(preferredCodec)) {
            videoFormats
                .filter { it.height == targetHeight && VideoCodecUtils.codecKeyFromMimeType(it.mimeType) == codecKey }
                .maxByOrNull { it.averageBitrate ?: it.bitrate }
                ?.let { return it }
        }

        val preferredItags = PREFERRED_VIDEO_ITAGS_BY_HEIGHT[targetHeight]
        if (preferredItags != null) {
            for (itag in preferredItags) {
                videoFormats.find { it.itag == itag }?.let { return it }
            }
        }

        val anyAtHeight =
            videoFormats
                .filter { it.height == targetHeight }
                .sortedWith(
                    compareBy<PlayerResponse.StreamingData.Format> {
                        VideoCodecUtils.codecRankWithPreference(
                            VideoCodecUtils.codecKeyFromMimeType(it.mimeType),
                            preferredCodec,
                        )
                    }.thenByDescending { it.averageBitrate ?: it.bitrate },
                ).firstOrNull()
        if (anyAtHeight != null) return anyAtHeight

        return videoFormats
            .sortedWith(
                compareBy<PlayerResponse.StreamingData.Format> { kotlin.math.abs((it.height ?: 0) - targetHeight) }
                    .thenBy {
                        VideoCodecUtils.codecRankWithPreference(
                            VideoCodecUtils.codecKeyFromMimeType(it.mimeType),
                            preferredCodec,
                        )
                    }.thenByDescending { it.averageBitrate ?: it.bitrate },
            ).firstOrNull()
    }
}
