package io.github.aedev.flow.player.quality

import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format.Range
import io.github.aedev.flow.player.stream.InnerTubeStreamBridge
import io.github.aedev.flow.player.stream.model.VideoStream

internal object LadderTestStreams {
    private val H264_ITAGS = mapOf(144 to 160, 240 to 133, 360 to 134, 480 to 135, 720 to 136, 1080 to 137)
    private val VP9_ITAGS = mapOf(144 to 278, 240 to 242, 360 to 243, 480 to 244, 720 to 247, 1080 to 248)
    private val VP9_HDR_ITAGS = mapOf(144 to 330, 240 to 331, 360 to 332, 480 to 333, 720 to 334, 1080 to 335)

    fun h264(
        height: Int,
        bitrate: Int,
        ranged: Boolean = true,
    ) = stream(height, bitrate, "video/mp4; codecs=\"avc1.4d401f\"", H264_ITAGS.getValue(height), ranged)

    fun vp9(
        height: Int,
        bitrate: Int,
        hdr: Boolean = false,
    ) = if (hdr) {
        stream(
            height,
            bitrate,
            "video/webm; codecs=\"vp09.02.51.10.01.09.16.09.00\"",
            VP9_HDR_ITAGS.getValue(height),
            label = "${height}p HDR",
        )
    } else {
        stream(height, bitrate, "video/webm; codecs=\"vp09.00.40.08\"", VP9_ITAGS.getValue(height))
    }

    fun stream(
        height: Int,
        bitrate: Int,
        mimeType: String,
        itag: Int,
        ranged: Boolean = true,
        label: String = "${height}p",
    ): VideoStream {
        val format =
            Format(
                itag = itag,
                url = "https://rr1---sn-test.googlevideo.com/videoplayback?itag=$itag&h=$height&b=$bitrate",
                mimeType = mimeType,
                bitrate = bitrate,
                width = height * 16 / 9,
                height = height,
                contentLength = 9_000_000L,
                quality = "q$height",
                fps = 30,
                qualityLabel = label,
                averageBitrate = bitrate,
                audioQuality = null,
                approxDurationMs = "212000",
                audioSampleRate = null,
                audioChannels = null,
                loudnessDb = null,
                lastModified = 1_700_000_000_000_000L,
                signatureCipher = null,
                initRange = Range("0", "740").takeIf { ranged },
                indexRange = Range("741", "1560").takeIf { ranged },
            )
        return InnerTubeStreamBridge.convertVideoFormats(listOf(format)).single()
    }
}
