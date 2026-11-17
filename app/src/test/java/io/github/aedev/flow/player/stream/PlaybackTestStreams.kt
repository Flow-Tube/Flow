package io.github.aedev.flow.player.stream

import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format.AudioTrack
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format.ColorInfo
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format.Range
import io.github.aedev.flow.player.stream.model.AudioStream
import io.github.aedev.flow.player.stream.model.MediaFormat
import io.github.aedev.flow.player.stream.model.StreamType
import io.github.aedev.flow.player.stream.model.VideoStream

/*
 * The one place the quality, media source and player bookkeeping tests touch the extractor's stream
 * types. Every stream is built from an InnerTube format through InnerTubeStreamBridge, as the app
 * builds them, and read back through the accessors below; when the app moves to its own stream model
 * only this file changes.
 */
internal typealias TestVideoStream = VideoStream
internal typealias TestAudioStream = AudioStream
internal typealias TestStreamType = StreamType

internal object PlaybackTestStreams {
    const val DURATION_MS = 212_000L

    val VOD: TestStreamType = StreamType.VIDEO_STREAM
    val LIVE: TestStreamType = StreamType.LIVE_STREAM
    val POST_LIVE: TestStreamType = StreamType.POST_LIVE_STREAM

    private val H264_ITAGS = mapOf(144 to 160, 240 to 133, 360 to 134, 480 to 135, 720 to 136, 1080 to 137, 1440 to 264, 2160 to 266)
    private val VP9_ITAGS = mapOf(144 to 278, 240 to 242, 360 to 243, 480 to 244, 720 to 247, 1080 to 248, 1440 to 271, 2160 to 313)
    private val VP9_HDR_ITAGS = mapOf(360 to 332, 480 to 333, 720 to 334, 1080 to 335, 1440 to 336, 2160 to 337)
    private val AV1_ITAGS = mapOf(144 to 394, 240 to 395, 360 to 396, 480 to 397, 720 to 398, 1080 to 399, 1440 to 400, 2160 to 401)

    fun videoFormat(
        itag: Int,
        height: Int,
        mimeType: String,
        bitrate: Int,
        fps: Int = 30,
        ranged: Boolean = true,
        qualityLabel: String = if (fps >= 50) "${height}p$fps" else "${height}p",
        width: Int = height * 16 / 9,
        hdr: Boolean = false,
        url: String = "https://rr1---sn-test.googlevideo.com/videoplayback?itag=$itag&h=$height&b=$bitrate",
        durationMs: Long? = DURATION_MS,
    ) = Format(
        itag = itag,
        url = url,
        mimeType = mimeType,
        bitrate = bitrate,
        width = width,
        height = height,
        contentLength = 9_000_000L,
        quality = "hd$height",
        fps = fps,
        qualityLabel = qualityLabel,
        averageBitrate = bitrate,
        audioQuality = null,
        approxDurationMs = durationMs?.toString(),
        audioSampleRate = null,
        audioChannels = null,
        loudnessDb = null,
        lastModified = 1_700_000_000_000_000L,
        signatureCipher = null,
        initRange = Range("0", "740").takeIf { ranged },
        indexRange = Range("741", "1560").takeIf { ranged },
        colorInfo = if (hdr) ColorInfo("COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084") else null,
    )

    fun audioFormat(
        itag: Int,
        mimeType: String,
        bitrate: Int,
        trackId: String? = null,
        trackName: String? = null,
        isDefault: Boolean? = null,
        isAutoDubbed: Boolean? = null,
        url: String = "https://rr1---sn-test.googlevideo.com/videoplayback?itag=$itag&b=$bitrate&t=${trackId.orEmpty()}",
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
        averageBitrate = bitrate,
        audioQuality = "AUDIO_QUALITY_MEDIUM",
        approxDurationMs = DURATION_MS.toString(),
        audioSampleRate = 48_000,
        audioChannels = 2,
        loudnessDb = null,
        lastModified = 1_700_000_000_000_001L,
        signatureCipher = null,
        audioTrack = trackId?.let { AudioTrack(displayName = trackName, id = it, isAutoDubbed = isAutoDubbed, audioIsDefault = isDefault) },
        initRange = Range("0", "640"),
        indexRange = Range("641", "1200"),
    )

    fun h264Format(
        height: Int,
        bitrate: Int,
        fps: Int = 30,
        ranged: Boolean = true,
    ) = videoFormat(H264_ITAGS.getValue(height), height, "video/mp4; codecs=\"avc1.4d401f\"", bitrate, fps, ranged)

    fun vp9Format(
        height: Int,
        bitrate: Int,
        fps: Int = 30,
        hdr: Boolean = false,
    ) = if (hdr) {
        videoFormat(
            VP9_HDR_ITAGS.getValue(height),
            height,
            "video/webm; codecs=\"vp09.02.51.10.01.09.16.09.00\"",
            bitrate,
            fps,
            qualityLabel = "${height}p$fps HDR",
            hdr = true,
        )
    } else {
        videoFormat(VP9_ITAGS.getValue(height), height, "video/webm; codecs=\"vp09.00.40.08\"", bitrate, fps)
    }

    fun av1Format(
        height: Int,
        bitrate: Int,
        fps: Int = 30,
    ) = videoFormat(AV1_ITAGS.getValue(height), height, "video/mp4; codecs=\"av01.0.08M.08\"", bitrate, fps)

    fun opusFormat(
        bitrate: Int = 130_000,
        trackId: String? = null,
        trackName: String? = null,
        isDefault: Boolean? = null,
        isAutoDubbed: Boolean? = null,
    ) = audioFormat(251, "audio/webm; codecs=\"opus\"", bitrate, trackId, trackName, isDefault, isAutoDubbed)

    fun m4aFormat(
        bitrate: Int = 129_000,
        trackId: String? = null,
        trackName: String? = null,
        isDefault: Boolean? = null,
    ) = audioFormat(140, "audio/mp4; codecs=\"mp4a.40.2\"", bitrate, trackId, trackName, isDefault)

    fun video(format: Format): TestVideoStream = InnerTubeStreamBridge.convertVideoFormats(listOf(format)).single()

    fun audio(format: Format): TestAudioStream = InnerTubeStreamBridge.convertAudioFormats(listOf(format)).single()

    fun h264(
        height: Int,
        bitrate: Int,
        fps: Int = 30,
        ranged: Boolean = true,
    ) = video(h264Format(height, bitrate, fps, ranged))

    fun vp9(
        height: Int,
        bitrate: Int,
        fps: Int = 30,
        hdr: Boolean = false,
    ) = video(vp9Format(height, bitrate, fps, hdr))

    fun av1(
        height: Int,
        bitrate: Int,
        fps: Int = 30,
    ) = video(av1Format(height, bitrate, fps))

    fun opus(
        bitrate: Int = 130_000,
        trackId: String? = null,
        trackName: String? = null,
        isDefault: Boolean? = null,
        isAutoDubbed: Boolean? = null,
    ) = audio(opusFormat(bitrate, trackId, trackName, isDefault, isAutoDubbed))

    fun m4a(
        bitrate: Int = 129_000,
        trackId: String? = null,
        trackName: String? = null,
        isDefault: Boolean? = null,
    ) = audio(m4aFormat(bitrate, trackId, trackName, isDefault))

    /** A muxed (audio and video) progressive stream; the InnerTube bridge never builds one. */
    fun muxed(height: Int): TestVideoStream =
        VideoStream(
            id = "18",
            content = "https://rr1---sn-test.googlevideo.com/videoplayback?itag=18&h=$height",
            format = MediaFormat.MPEG_4,
            itagItem = null,
            resolution = "${height}p",
            isVideoOnly = false,
        )
}

internal val TestVideoStream.testUrl: String get() = content

internal val TestVideoStream.testItag: Int get() = itagItem?.id ?: id.toInt()

internal val TestVideoStream.testHeight: Int get() = VideoCodecUtils.qualityHeightFromStream(this)

internal val TestVideoStream.testCodec: String get() = VideoCodecUtils.codecKeyFromStream(this)

internal val TestAudioStream.testUrl: String get() = content

internal val TestAudioStream.testItag: Int get() = itagItem?.id ?: id.toInt()

internal val TestAudioStream.testTrackId: String? get() = audioTrackId
