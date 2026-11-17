package io.github.aedev.flow.player.stream

import android.util.Log
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.stream.model.AudioStream
import io.github.aedev.flow.player.stream.model.AudioTrackType
import io.github.aedev.flow.player.stream.model.ItagInfo
import io.github.aedev.flow.player.stream.model.MediaFormat
import io.github.aedev.flow.player.stream.model.VideoStream
import java.util.Locale

object InnerTubeStreamBridge {
    private const val TAG = "InnerTubeStreamBridge"

    fun convertVideoFormats(formats: List<PlayerResponse.StreamingData.Format>): List<VideoStream> {
        return formats.mapNotNull { format ->
            val url = format.url ?: return@mapNotNull null
            val height = format.height ?: return@mapNotNull null
            val mediaFormat = mapVideoMimeToMediaFormat(format.mimeType) ?: return@mapNotNull null
            val resolutionLabel =
                format.qualityLabel
                    ?.takeIf { it.isNotBlank() }
                    ?: "${height}p"

            VideoStream(
                id = format.itag.toString(),
                content = url,
                format = mediaFormat,
                itagItem = buildItagItem(format, isAudio = false),
                resolution = resolutionLabel,
                isVideoOnly = true,
            )
        }
    }

    fun convertAudioFormats(formats: List<PlayerResponse.StreamingData.Format>): List<AudioStream> {
        return formats.preferNonDrc().mapNotNull { format ->
            val url = format.url ?: return@mapNotNull null
            val mediaFormat = mapAudioMimeToMediaFormat(format.mimeType) ?: return@mapNotNull null
            val bitrate = format.averageBitrate ?: format.bitrate

            val track = format.audioTrack
            AudioStream(
                id = format.itag.toString(),
                content = url,
                format = mediaFormat,
                itagItem = buildItagItem(format, isAudio = true),
                averageBitrate = bitrate,
                audioTrackId = track?.id?.takeIf { it.isNotBlank() },
                audioTrackName = track?.displayName?.takeIf { it.isNotBlank() },
                audioLocale =
                    format.audioLanguageTag?.let { tag ->
                        runCatching { Locale.forLanguageTag(tag) }.getOrNull()?.takeIf { it.language.isNotBlank() }
                    },
                audioTrackType = if (format.isOriginal) AudioTrackType.ORIGINAL else AudioTrackType.DUBBED,
            )
        }
    }

    private fun mapVideoMimeToMediaFormat(mimeType: String): MediaFormat? {
        val mime = mimeType.lowercase()
        return when {
            mime.startsWith("video/mp4") -> {
                MediaFormat.MPEG_4
            }

            mime.startsWith("video/webm") -> {
                MediaFormat.WEBM
            }

            mime.startsWith("video/3gpp") -> {
                MediaFormat.v3GPP
            }

            else -> {
                Log.d(TAG, "Unknown video MIME: $mimeType")
                null
            }
        }
    }

    private fun mapAudioMimeToMediaFormat(mimeType: String): MediaFormat? {
        val mime = mimeType.lowercase()
        return when {
            mime.startsWith("audio/mp4") -> {
                MediaFormat.M4A
            }

            mime.startsWith("audio/webm") && mime.contains("opus") -> {
                MediaFormat.WEBMA_OPUS
            }

            mime.startsWith("audio/webm") -> {
                MediaFormat.WEBMA
            }

            else -> {
                Log.d(TAG, "Unknown audio MIME: $mimeType")
                null
            }
        }
    }

    private fun buildItagItem(
        format: PlayerResponse.StreamingData.Format,
        isAudio: Boolean,
    ): ItagInfo? {
        val item =
            ItagInfo.forItag(format.itag)
                ?: syntheticItagItem(format, isAudio)
                ?: run {
                    Log.d(TAG, "No itag metadata for itag=${format.itag}")
                    return null
                }

        VideoCodecUtils
            .codecStringFromMimeType(format.mimeType)
            .takeIf { it.isNotBlank() }
            ?.let { item.codec = it }
        item.bitrate = format.averageBitrate ?: format.bitrate
        format.contentLength?.let { item.contentLength = it }
        format.approxDurationMs?.toLongOrNull()?.let { item.approxDurationMs = it }
        format.lastModified?.let { item.lastModified = it }
        format.initRange?.let { range ->
            range.start?.toIntOrNull()?.let { item.initStart = it }
            range.end?.toIntOrNull()?.let { item.initEnd = it }
        }
        format.indexRange?.let { range ->
            range.start?.toIntOrNull()?.let { item.indexStart = it }
            range.end?.toIntOrNull()?.let { item.indexEnd = it }
        }

        if (isAudio) {
            format.audioChannels?.let { item.audioChannels = it }
            format.audioSampleRate?.let { item.sampleRate = it }
        } else {
            format.width?.let { item.width = it }
            format.height?.let { item.height = it }
            format.fps?.let { item.fps = it }
            item.quality = format.quality
        }
        return item
    }

    /**
     * The itag table lags YouTube's ladder — the 608×1080 Shorts tier (779/780 vp9, 787/788 av01)
     * and HDR VP9 are not in it — and a format it cannot describe would lose its DASH manifest and
     * fall back to the throttled progressive path. The response carries everything the table would
     * have said, so describe the format from it instead.
     */
    private fun syntheticItagItem(
        format: PlayerResponse.StreamingData.Format,
        isAudio: Boolean,
    ): ItagInfo? {
        if (isAudio) return null
        val mediaFormat = mapVideoMimeToMediaFormat(format.mimeType) ?: return null
        val height = format.height ?: return null
        val resolution = format.qualityLabel?.takeIf { it.isNotBlank() } ?: "${height}p"
        return ItagInfo(format.itag, ItagInfo.ItagType.VIDEO_ONLY, mediaFormat, resolution, format.fps ?: 30)
    }
}
