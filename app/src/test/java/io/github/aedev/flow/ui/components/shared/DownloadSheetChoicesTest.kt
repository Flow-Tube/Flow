package io.github.aedev.flow.ui.components.shared

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.video.DownloadStreamPolicy
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import org.junit.Test

class DownloadSheetChoicesTest {
    private fun video(
        itag: Int,
        codec: String,
        height: Int,
        fps: Int = 30,
        hdr: Boolean = false,
    ) = Format(
        itag = itag,
        url = "https://example.invalid/$itag",
        mimeType =
            when (codec) {
                "av1" -> "video/mp4; codecs=\"av01.0.08M.08\""
                "vp9" -> "video/webm; codecs=\"vp9\""
                else -> "video/mp4; codecs=\"avc1.640028\""
            },
        bitrate = 1_000_000,
        width = height * 16 / 9,
        height = height,
        contentLength = null,
        quality = "medium",
        fps = fps,
        qualityLabel = "${height}p",
        averageBitrate = null,
        audioQuality = null,
        approxDurationMs = null,
        audioSampleRate = null,
        audioChannels = null,
        loudnessDb = null,
        lastModified = null,
        signatureCipher = null,
        colorInfo = if (hdr) Format.ColorInfo(transferCharacteristics = "COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084") else null,
    )

    private fun qualities(vararg formats: Format) =
        DownloadSheetChoices.qualities(DownloadStreamPolicy.buildDownloadVideoFormats(formats.toList()))

    private val ladder =
        qualities(
            video(401, "av1", 2160),
            video(313, "vp9", 2160),
            video(399, "av1", 1080, fps = 60),
            video(299, "h264", 1080, fps = 60),
            video(248, "vp9", 1080),
            video(337, "vp9", 1080, hdr = true),
            video(136, "h264", 720),
            video(135, "h264", 480),
        )

    @Test
    fun `one quality per resolution, highest first`() {
        assertThat(ladder.map { it.height }).containsExactly(2160, 1080, 720, 480).inOrder()
        assertThat(ladder.first { it.height == 1080 }.fps).isEqualTo(60)
    }

    @Test
    fun `options keep codec priority and put SDR before HDR`() {
        val options = ladder.first { it.height == 1080 }.options
        assertThat(options)
            .containsExactly(
                DownloadFormatOption("vp9", hdr = false),
                DownloadFormatOption("vp9", hdr = true),
                DownloadFormatOption("h264", hdr = false),
                DownloadFormatOption("av1", hdr = false),
            ).inOrder()
    }

    @Test
    fun `an option downloads its highest frame rate`() {
        val q1080 = ladder.first { it.height == 1080 }
        assertThat(q1080.format(DownloadFormatOption("h264", hdr = false))?.itag).isEqualTo(299)
        assertThat(q1080.format(DownloadFormatOption("vp9", hdr = true))?.itag).isEqualTo(337)
    }

    @Test
    fun `the sheet opens on the download quality or the nearest below`() {
        assertThat(DownloadSheetChoices.preselect(ladder, 720)?.height).isEqualTo(720)
        assertThat(DownloadSheetChoices.preselect(ladder, 1440)?.height).isEqualTo(1080)
        assertThat(DownloadSheetChoices.preselect(ladder, 360)?.height).isEqualTo(480)
        assertThat(DownloadSheetChoices.preselect(ladder, 0)?.height).isEqualTo(2160)
        assertThat(DownloadSheetChoices.preselect(emptyList(), 720)).isNull()
    }

    @Test
    fun `a resolution keeps the chosen codec, else the default codec in SDR`() {
        val q1080 = ladder.first { it.height == 1080 }
        val q720 = ladder.first { it.height == 720 }
        val av1 = DownloadFormatOption("av1", hdr = false)
        assertThat(DownloadSheetChoices.option(q1080, current = av1, preferredCodec = null)).isEqualTo(av1)
        assertThat(DownloadSheetChoices.option(q720, current = av1, preferredCodec = "vp9"))
            .isEqualTo(DownloadFormatOption("h264", hdr = false))
        assertThat(DownloadSheetChoices.option(q1080, current = null, preferredCodec = "h264"))
            .isEqualTo(DownloadFormatOption("h264", hdr = false))
        assertThat(DownloadSheetChoices.option(q1080, current = null, preferredCodec = null))
            .isEqualTo(DownloadFormatOption("vp9", hdr = false))
    }

    private fun audio(
        itag: Int,
        trackId: String?,
        original: Boolean,
    ) = video(itag, "h264", 0).copy(
        mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
        height = null,
        width = null,
        audioTrack = trackId?.let { Format.AudioTrack(displayName = it, id = "$it.${if (original) 4 else 3}", audioIsDefault = original) },
    )

    @Test
    fun `audio opens on the original track and the viewer's language`() {
        val original = audio(140, "en", original = true)
        val arabic = audio(141, "ar", original = false)
        val bangla = audio(142, "bn", original = false)
        val all = listOf(original, arabic, bangla)

        assertThat(DownloadSheetChoices.primaryAudio(all, preferredTrackId = null)).containsExactly(original)
        assertThat(
            DownloadSheetChoices.primaryAudio(all, preferredTrackId = arabic.audioTrack?.id),
        ).containsExactly(original, arabic).inOrder()
        val noTracks = listOf(audio(140, null, original = true), audio(139, null, original = true))
        assertThat(DownloadSheetChoices.primaryAudio(noTracks, preferredTrackId = null)).isEqualTo(noTracks)
    }
}
