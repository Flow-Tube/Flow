package io.github.aedev.flow.player.stream

import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import java.util.Base64

/** InnerTube formats shaped like the ones `/player` returns, one per family the app plays. */
internal object GoldenFormats {
    const val LAST_MODIFIED = 1_700_000_000_000_000L

    fun url(itag: Int) = "https://rr3---sn-golden.googlevideo.com/videoplayback?itag=$itag&id=golden"

    fun video(
        itag: Int,
        mimeType: String,
        width: Int?,
        height: Int?,
        fps: Int?,
        qualityLabel: String?,
        quality: String,
        bitrate: Int = 2_000_000,
        averageBitrate: Int? = 1_500_000,
        contentLength: Long? = 40_000_000L,
        approxDurationMs: String? = "212000",
        initRange: Format.Range? = Format.Range("0", "740"),
        indexRange: Format.Range? = Format.Range("741", "1560"),
        colorInfo: Format.ColorInfo? = null,
        url: String? = url(itag),
    ) = Format(
        itag = itag,
        url = url,
        mimeType = mimeType,
        bitrate = bitrate,
        width = width,
        height = height,
        contentLength = contentLength,
        quality = quality,
        fps = fps,
        qualityLabel = qualityLabel,
        averageBitrate = averageBitrate,
        audioQuality = null,
        approxDurationMs = approxDurationMs,
        audioSampleRate = null,
        audioChannels = null,
        loudnessDb = null,
        lastModified = LAST_MODIFIED,
        signatureCipher = null,
        initRange = initRange,
        indexRange = indexRange,
        colorInfo = colorInfo,
    )

    fun audio(
        itag: Int,
        mimeType: String,
        sampleRate: Int?,
        channels: Int?,
        bitrate: Int = 140_000,
        averageBitrate: Int? = 129_000,
        track: Format.AudioTrack? = null,
        isDrc: Boolean? = null,
        xtags: String? = null,
        url: String? = url(itag),
    ) = Format(
        itag = itag,
        url = url,
        mimeType = mimeType,
        bitrate = bitrate,
        width = null,
        height = null,
        contentLength = 3_400_000L,
        quality = "tiny",
        fps = null,
        qualityLabel = null,
        averageBitrate = averageBitrate,
        audioQuality = "AUDIO_QUALITY_MEDIUM",
        approxDurationMs = "212041",
        audioSampleRate = sampleRate,
        audioChannels = channels,
        loudnessDb = -6.2,
        lastModified = LAST_MODIFIED,
        signatureCipher = null,
        audioTrack = track,
        isDrc = isDrc,
        xtags = xtags,
        initRange = Format.Range("0", "631"),
        indexRange = Format.Range("632", "1003"),
    )

    /** The `xtags` blob YouTube sends: repeated {key, value} submessages, base64url. */
    fun xtags(vararg tags: Pair<String, String>): String {
        val out = java.io.ByteArrayOutputStream()
        for ((key, value) in tags) {
            val k = key.toByteArray()
            val v = value.toByteArray()
            val inner = byteArrayOf(0x0a, k.size.toByte()) + k + byteArrayOf(0x12, v.size.toByte()) + v
            out.write(0x0a)
            out.write(inner.size)
            out.write(inner)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    val H264_1080 =
        video(137, "video/mp4; codecs=\"avc1.640028\"", 1920, 1080, 30, "1080p", "hd1080", bitrate = 4_400_000, averageBitrate = 2_600_000)
    val VP9_1080_60 =
        video(303, "video/webm; codecs=\"vp9\"", 1920, 1080, 60, "1080p60", "hd1080", bitrate = 4_100_000, averageBitrate = 2_900_000)
    val VP9_HDR_1080_60 =
        video(
            335,
            "video/webm; codecs=\"vp09.02.51.10.01.09.16.09.00\"",
            1920,
            1080,
            60,
            "1080p60 HDR",
            "hd1080",
            bitrate = 5_600_000,
            averageBitrate = 4_000_000,
            colorInfo = Format.ColorInfo("COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084"),
        )
    val AV1_1080 =
        video(
            399,
            "video/mp4; codecs=\"av01.0.08M.08\"",
            1920,
            1080,
            30,
            "1080p",
            "hd1080",
            bitrate = 2_100_000,
            averageBitrate = 1_400_000,
        )
    val GPP_144 =
        video(
            17,
            "video/3gpp; codecs=\"mp4v.20.3, mp4a.40.2\"",
            176,
            144,
            12,
            "144p",
            "small",
            bitrate = 80_000,
            averageBitrate = 78_000,
            initRange = null,
            indexRange = null,
        )
    val SHORTS_779 = video(779, "video/webm; codecs=\"vp9\"", 608, 1080, 30, "480p", "large", bitrate = 900_000, averageBitrate = 700_000)
    val SHORTS_780 =
        video(780, "video/webm; codecs=\"vp9\"", 608, 1080, 60, "480p60", "large", bitrate = 1_300_000, averageBitrate = 950_000)
    val SHORTS_787 =
        video(787, "video/mp4; codecs=\"av01.0.05M.08\"", 608, 1080, 30, "480p", "large", bitrate = 800_000, averageBitrate = 600_000)
    val SHORTS_788 =
        video(788, "video/mp4; codecs=\"av01.0.05M.08\"", 608, 1080, 60, "480p60", "large", bitrate = 1_100_000, averageBitrate = 820_000)

    val M4A_140 = audio(140, "audio/mp4; codecs=\"mp4a.40.2\"", 44_100, 2, bitrate = 130_000, averageBitrate = 129_500)
    val OPUS_251 = audio(251, "audio/webm; codecs=\"opus\"", 48_000, 2, bitrate = 150_000, averageBitrate = 135_000)
    val OPUS_250 = audio(250, "audio/webm; codecs=\"opus\"", 48_000, 2, bitrate = 80_000, averageBitrate = 70_000)
    val VORBIS_171 = audio(171, "audio/webm; codecs=\"vorbis\"", 44_100, 2, bitrate = 128_000, averageBitrate = 117_000)
}
