package io.github.aedev.flow.player.stream

import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.player.sabr.integration.SabrStreamInfo
import io.github.aedev.flow.player.stream.model.AudioStream

/**
 * Moves a video's audio between YouTube's normal and DRC (Stable volume) copies of the same itag
 * and track, using the formats its `/player` response already listed, so the setting changes
 * without asking for the video again.
 */
internal object StableVolumeVariants {
    fun isAvailable(
        formats: List<Format>,
        playing: AudioStream?,
    ): Boolean = AudioVariantPolicy.hasStableVariant(formats, playing?.audioTrackId)

    fun streams(
        streams: List<AudioStream>,
        formats: List<Format>,
        stable: Boolean,
    ): List<AudioStream> {
        if (streams.isEmpty() || formats.isEmpty()) return streams
        val listed = formats.mapNotNullTo(HashSet()) { it.url }
        val variants =
            InnerTubeStreamBridge
                .convertAudioFormats(formats, stable)
                .associateBy { it.id to it.audioTrackId }
        return streams.map { stream ->
            if (stream.content !in listed) return@map stream
            variants[stream.id to stream.audioTrackId]?.takeIf { it.content != stream.content } ?: stream
        }
    }

    fun stream(
        stream: AudioStream?,
        formats: List<Format>,
        stable: Boolean,
    ): AudioStream? = stream?.let { streams(listOf(it), formats, stable).single() }

    /** The session asking GVS for the chosen copy: same itag, its own lmt and xtags. */
    fun sabr(
        info: SabrStreamInfo?,
        formats: List<Format>,
        stable: Boolean,
    ): SabrStreamInfo? {
        if (info == null) return null
        val trackId = info.audioTrackId.ifEmpty { null }
        val copy =
            formats
                .filter { it.isAudio && it.itag == info.audioItag && it.audioTrack?.id == trackId }
                .selectAudioVariants(stable)
                .firstOrNull() ?: return info
        val lmt = copy.lastModified ?: 0L
        val xtags = copy.xtags.orEmpty()
        if (lmt == info.audioLmt && xtags == info.audioXtags) return info
        return info.copy(audioLmt = lmt, audioXtags = xtags)
    }
}
