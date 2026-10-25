package io.github.aedev.flow.data.video

import io.github.aedev.flow.data.local.MusicAudioQuality
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.player.stream.VideoCodecUtils
import io.github.aedev.flow.player.stream.preferNonDrc
import java.util.Locale
import kotlin.math.abs

/**
 * Which formats a download offers and which audio track it pairs with a video track. Pure policy:
 * no Compose, no resources, no Android UI, so every rule here is unit-testable.
 */
object DownloadStreamPolicy {
    /**
     * Codec order within one resolution for a *download*, which is deliberately not
     * [VideoCodecUtils.playbackCodecRank]: playback ranks h264 first because it is the codec every
     * decoder handles, while a download ranks vp9 first because it is the smallest file the app can
     * mux reliably, and av1 above the legacy vp8/hevc ladder.
     */
    val DOWNLOAD_CODEC_PRIORITY = mapOf("vp9" to 0, "h264" to 1, "av1" to 2, "vp8" to 3, "hevc" to 4)

    private const val UNRANKED_CODEC = 99
    private const val AUDIO_MP4 = "audio/mp4"
    private const val AUDIO_WEBM = "audio/webm"
    private const val MEDIUM_BITRATE = 128_000
    private val VIDEO_CONTAINERS = setOf("video/mp4", "video/webm", "video/3gpp")

    /** Codecs the MP4 muxer takes only with AAC; the Matroska writer takes any audio. */

    fun videoHeight(format: Format): Int = VideoCodecUtils.qualityHeightFromFormat(format.qualityLabel, format.height ?: 0)

    fun videoCodecKey(format: Format): String = VideoCodecUtils.codecKeyFromMimeType(format.mimeType)

    fun isHdr(format: Format): Boolean = format.colorInfo?.isHdr == true

    /** "VP9 1080p", "VP9 1080p60", or with [hdrLabel] appended for an HDR format. */
    fun videoQualityLabel(
        format: Format,
        hdrLabel: String,
    ): String {
        val base =
            "${VideoCodecUtils.codecLabelFromKey(videoCodecKey(format))} " +
                VideoCodecUtils.qualityLabelWithFrameRate(videoHeight(format), format.fps ?: 0)
        return if (isHdr(format)) "$base $hdrLabel" else base
    }

    /**
     * The video ladder both download dialogs show: one entry per (resolution, codec, frame rate,
     * HDR), highest resolution first, [DOWNLOAD_CODEC_PRIORITY] within a resolution, then SDR before
     * HDR and the higher frame rate first. Formats with no URL are dropped: selecting one can only
     * fail.
     */
    fun buildDownloadVideoFormats(formats: List<Format>): List<Format> =
        formats
            .filter { !it.url.isNullOrBlank() && it.height != null && containerOf(it.mimeType) in VIDEO_CONTAINERS }
            .distinctBy { listOf(videoHeight(it), videoCodecKey(it), it.fps ?: 0, isHdr(it)) }
            .sortedWith(
                compareByDescending<Format> { videoHeight(it) }
                    .thenBy { DOWNLOAD_CODEC_PRIORITY[videoCodecKey(it)] ?: UNRANKED_CODEC }
                    .thenBy { isHdr(it) }
                    .thenByDescending { it.fps ?: 0 },
            )

    private fun audioBitrate(format: Format): Int = format.averageBitrate ?: format.bitrate

    fun audioBitrateKbps(format: Format): Int {
        val raw = format.averageBitrate?.takeIf { it > 0 } ?: format.bitrate
        return if (raw > 1000) raw / 1000 else raw.coerceAtLeast(0)
    }

    fun audioFormatLabel(
        format: Format,
        unknownLabel: String = "",
    ): String =
        when (containerOf(format.mimeType)) {
            AUDIO_WEBM -> if ("opus" in format.mimeType.lowercase()) "OPUS" else "WEBM"
            AUDIO_MP4 -> "M4A"
            else -> unknownLabel
        }

    fun audioLanguageLabel(format: Format): String? =
        format.audioTrack?.displayName?.takeIf { it.isNotBlank() }
            ?: audioLocale(format)?.displayLanguage?.takeIf { it.isNotBlank() }
            ?: format.audioTrack?.id?.takeIf { it.isNotBlank() }

    fun audioTrackTypeLabel(
        format: Format,
        originalLabel: String,
        dubbedLabel: String,
    ): String = if (format.isOriginal) originalLabel else dubbedLabel

    /**
     * The audio formats a download can use: AAC only, since every download is an MP4 or M4A and
     * Media3 cuts the start of Opus in MP4. DRC twins and URL-less formats are dropped, one entry
     * per (bitrate, track, language, role). The original track comes first, then the dubs by
     * language, each highest bitrate first: dubs often share one bitrate, so a bitrate-first list
     * opened on whichever dub sorted first (#1444).
     */
    fun buildDownloadAudioFormats(formats: List<Format>): List<Format> =
        formats
            .preferNonDrc()
            .filter { !it.url.isNullOrBlank() && isAacFormat(it) }
            .distinctBy { format ->
                listOf(
                    audioBitrateKbps(format).toString(),
                    format.audioTrack?.id.orEmpty(),
                    audioLocale(format)?.toLanguageTag().orEmpty(),
                    format.isOriginal.toString(),
                ).joinToString("|")
            }.sortedWith(
                compareByDescending<Format> { it.isOriginal }
                    .thenBy { audioLocale(it)?.displayLanguage.orEmpty() }
                    .thenBy { it.audioTrack?.id.orEmpty() }
                    .thenByDescending { audioBitrateKbps(it) },
            )

    /**
     * The AAC audio of a download, which is always an MP4 or M4A: the chosen language's AAC when it
     * has one, otherwise any AAC, otherwise none. Opus is never taken, because Opus in MP4 loses its
     * first 300 ms in Media3 (androidx/media#3431). The best one wins, unless [quality] asks for
     * Medium (nearest 128 kbps) or Low (the smallest).
     */
    fun pickAacAudio(
        allAudio: List<Format>,
        preferredLang: String?,
        quality: MusicAudioQuality = MusicAudioQuality.HIGH,
    ): Format? {
        val aac = allAudio.filter(::isAacFormat)
        if (aac.isEmpty()) return null
        val inLanguage =
            if (!preferredLang.isNullOrEmpty() && preferredLang != "original") {
                aac.filter {
                    val locale = audioLocale(it)
                    locale?.language.equals(preferredLang, ignoreCase = true) ||
                        locale?.toLanguageTag().equals(preferredLang, ignoreCase = true)
                }
            } else {
                aac.filter { it.isOriginal }
            }
        val candidates = inLanguage.ifEmpty { aac }
        return when (quality) {
            MusicAudioQuality.MEDIUM -> candidates.minByOrNull { abs(audioBitrate(it) - MEDIUM_BITRATE) }
            MusicAudioQuality.LOW -> candidates.minByOrNull(::audioBitrate)
            MusicAudioQuality.HIGH, MusicAudioQuality.AUTO -> candidates.maxByOrNull(::audioBitrate)
        }
    }

    /**
     * The tracks a video download can pair with: one entry per track, its best AAC, in
     * [buildDownloadAudioFormats] order so the original comes first. A video with one track gives one
     * entry, and the dialog then shows no choice.
     */
    fun audioTrackChoices(streams: List<Format>): List<Format> =
        streams
            .filter(::isAacFormat)
            .groupBy { listOf(it.audioTrack?.id.orEmpty(), audioLocale(it)?.toLanguageTag().orEmpty(), it.isOriginal.toString()) }
            .values
            .map { track -> track.maxBy(::audioBitrate) }

    /**
     * The row an audio-only download opens on: [pickAacAudio]'s track, so the original unless a
     * language is set. The remembered row wins only when it is that same track, or the last dub
     * someone downloaded would become the default for every later video.
     */
    fun defaultAudioIndex(
        streams: List<Format>,
        preferredLang: String?,
        rememberedLabel: String?,
        labelOf: (Format) -> String,
    ): Int {
        val pick = pickAacAudio(streams, preferredLang) ?: return 0
        val remembered =
            streams.indexOfFirst {
                labelOf(it) == rememberedLabel && it.audioTrack?.id == pick.audioTrack?.id && it.isOriginal == pick.isOriginal
            }
        return remembered.takeIf { it >= 0 } ?: streams.indexOf(pick).coerceAtLeast(0)
    }

    fun isAacFormat(format: Format): Boolean = containerOf(format.mimeType) == AUDIO_MP4

    private fun containerOf(mimeType: String): String = mimeType.substringBefore(';').trim().lowercase()

    private fun audioLocale(format: Format): Locale? =
        format.audioLanguageTag
            ?.let { tag -> runCatching { Locale.forLanguageTag(tag) }.getOrNull() }
            ?.takeIf { it.language.isNotBlank() }
}
