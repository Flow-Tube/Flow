package io.github.aedev.flow.player.error

import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.player.stream.InnerTubeStreamBridge
import io.github.aedev.flow.player.stream.model.AudioStream
import io.github.aedev.flow.player.stream.model.VideoStream

/**
 * Streams for the recovery and cast tests, built from InnerTube formats through the bridge the
 * player uses. The only file of those tests that names the stream types, so a change of stream
 * model rewrites this file and leaves the tests alone.
 */
internal object RecoveryStreams {
    const val VALID_EXPIRE = 4_000_000_000L
    const val PASSED_EXPIRE = 1_000_000_000L

    fun url(
        itag: Int,
        client: String = "ANDROID_VR",
        expire: Long = VALID_EXPIRE,
        pot: String? = null,
    ): String =
        "https://rr1---sn-test.googlevideo.com/videoplayback?expire=$expire&itag=$itag&c=$client" +
            (pot?.let { "&pot=$it" } ?: "")

    fun video(
        itag: Int,
        height: Int,
        mimeType: String,
        url: String = url(itag),
        width: Int = height * 16 / 9,
        bitrate: Int = 2_000_000,
        ranged: Boolean = true,
    ): VideoStream =
        InnerTubeStreamBridge
            .convertVideoFormats(
                listOf(
                    format(itag, url, mimeType, bitrate, ranged).copy(
                        width = width,
                        height = height,
                        fps = 30,
                        qualityLabel = "${height}p",
                        quality = "hd$height",
                    ),
                ),
            ).single()

    fun h264(
        height: Int,
        itag: Int = 137,
        url: String = url(itag),
        ranged: Boolean = true,
    ) = video(itag, height, "video/mp4; codecs=\"avc1.640028\"", url, ranged = ranged)

    fun av1(
        height: Int,
        itag: Int = 401,
        url: String = url(itag),
    ) = video(itag, height, "video/mp4; codecs=\"av01.0.12M.08\"", url)

    fun vp9(
        height: Int,
        itag: Int = 248,
        url: String = url(itag),
    ) = video(itag, height, "video/webm; codecs=\"vp9\"", url)

    fun muxed(
        height: Int,
        itag: Int = 18,
        url: String = url(itag),
    ) = video(itag, height, "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"", url, ranged = false)

    fun audio(
        itag: Int,
        mimeType: String,
        bitrate: Int,
        url: String = url(itag),
        ranged: Boolean = true,
    ): AudioStream =
        InnerTubeStreamBridge
            .convertAudioFormats(
                listOf(
                    format(itag, url, mimeType, bitrate, ranged).copy(
                        audioQuality = "AUDIO_QUALITY_MEDIUM",
                        audioSampleRate = 44_100,
                        audioChannels = 2,
                    ),
                ),
            ).single()

    fun aac(
        itag: Int = 140,
        url: String = url(itag),
        bitrate: Int = 129_000,
        ranged: Boolean = true,
    ) = audio(itag, "audio/mp4; codecs=\"mp4a.40.2\"", bitrate, url, ranged)

    fun opus(
        itag: Int = 251,
        url: String = url(itag),
        bitrate: Int = 135_000,
    ) = audio(itag, "audio/webm; codecs=\"opus\"", bitrate, url)

    fun noVideo(): VideoStream? = null

    fun noAudio(): AudioStream? = null

    private fun format(
        itag: Int,
        url: String,
        mimeType: String,
        bitrate: Int,
        ranged: Boolean,
    ) = Format(
        itag = itag,
        url = url,
        mimeType = mimeType,
        bitrate = bitrate,
        width = null,
        height = null,
        contentLength = 9_000_000L,
        quality = "medium",
        fps = null,
        qualityLabel = null,
        averageBitrate = bitrate,
        audioQuality = null,
        approxDurationMs = "212000",
        audioSampleRate = null,
        audioChannels = null,
        loudnessDb = null,
        lastModified = 1_700_000_000_000_000L,
        signatureCipher = null,
        initRange = Format.Range("0", "740").takeIf { ranged },
        indexRange = Format.Range("741", "1560").takeIf { ranged },
    )
}
