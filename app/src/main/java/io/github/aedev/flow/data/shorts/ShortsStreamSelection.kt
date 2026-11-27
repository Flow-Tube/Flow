package io.github.aedev.flow.data.shorts

import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.stream.VideoCodecUtils
import io.github.aedev.flow.player.stream.selectAudioVariants
import java.util.Locale

/**
 * The quality class of a portrait format. A reel is taller than it is wide, so `height` is the
 * long side and a 1080×1920 stream is "1080p" — YouTube's own label says so, and the short side is
 * the right fallback when the label is missing.
 */
internal fun shortsQualityClass(format: PlayerResponse.StreamingData.Format): Int {
    format.qualityLabel?.let { label ->
        QUALITY_LABEL
            .find(label)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }
    }
    val width = format.width ?: 0
    val height = format.height ?: 0
    return if (width > 0 && height > 0) minOf(width, height) else maxOf(width, height)
}

/** Highest class at or under [targetHeight] (0 = no cap), the preferred codec first within it. */
internal fun selectShortVideoFormat(
    formats: List<PlayerResponse.StreamingData.Format>,
    targetHeight: Int,
    preferredCodecKey: String,
): PlayerResponse.StreamingData.Format? {
    val candidates = if (targetHeight == 0) formats else formats.filter { shortsQualityClass(it) <= targetHeight }
    return candidates.sortedForPlayback(preferredCodecKey).firstOrNull()
        ?: formats.minByOrNull { shortsQualityClass(it) }
}

internal fun List<PlayerResponse.StreamingData.Format>.sortedForPlayback(preferredCodecKey: String) =
    sortedWith(
        compareByDescending<PlayerResponse.StreamingData.Format> { shortsQualityClass(it) }
            .thenBy { VideoCodecUtils.codecRankWithPreference(VideoCodecUtils.codecKeyFromMimeType(it.mimeType), preferredCodecKey) }
            .thenByDescending { it.averageBitrate ?: it.bitrate },
    )

/** The best-sounding format in [preferredLanguage], else the original track, else anything; [stable] takes DRC copies. */
internal fun selectShortAudioFormat(
    formats: List<PlayerResponse.StreamingData.Format>,
    preferredLanguage: String,
    stable: Boolean = false,
): PlayerResponse.StreamingData.Format? {
    val sorted =
        formats.selectAudioVariants(stable).sortedByDescending {
            (it.averageBitrate ?: it.bitrate) +
                if (it.mimeType.contains("webm", true)) 10_000 else 0
        }
    if (preferredLanguage.isBlank() || preferredLanguage == "original") {
        return sorted.firstOrNull { it.isOriginal } ?: sorted.firstOrNull()
    }
    return sorted.firstOrNull { it.audioLanguageTag?.startsWith(preferredLanguage, ignoreCase = true) == true }
        ?: sorted.firstOrNull { it.isOriginal }
        ?: sorted.firstOrNull()
}

/** What the audio-track sheet shows for a format: YouTube's own name, else the language, else the tag. */
internal fun PlayerResponse.StreamingData.Format.audioTrackLabel(): String? {
    audioTrack?.displayName?.takeIf { it.isNotBlank() }?.let { return it }
    val tag = audioLanguageTag ?: return null
    return Locale.forLanguageTag(tag).displayName.takeIf { it.isNotBlank() && it != tag } ?: tag
}

private val QUALITY_LABEL = Regex("(\\d+)p")
