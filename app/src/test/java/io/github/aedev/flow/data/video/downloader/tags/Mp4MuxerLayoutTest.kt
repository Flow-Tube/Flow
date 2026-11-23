package io.github.aedev.flow.data.video.downloader.tags

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/** The real muxer with the remuxer's settings, then the tag writer: the index must stay in front of the media. */
@androidx.annotation.OptIn(UnstableApi::class)
class Mp4MuxerLayoutTest {
    @get:Rule val folder = TemporaryFolder()

    private fun mux(
        file: File,
        samples: Int,
        tagRoom: Int,
        sampleBytes: Int = 300,
    ) {
        FileOutputStream(file).use { stream ->
            val muxer =
                Mp4Muxer
                    .Builder(SeekableMuxerOutput.of(stream))
                    .setAttemptStreamableOutputEnabled(true)
                    .experimentalSetFreeSpaceAfterFileTypeBox(tagRoom)
                    .build()
            val track =
                muxer.addTrack(
                    Format
                        .Builder()
                        .setSampleMimeType(MimeTypes.AUDIO_AAC)
                        .setSampleRate(44_100)
                        .setChannelCount(2)
                        .setInitializationData(listOf(byteArrayOf(0x12, 0x10)))
                        .build(),
                )
            repeat(samples) { i ->
                val sample = ByteBuffer.wrap(ByteArray(sampleBytes) { (it + i).toByte() })
                muxer.writeSampleData(track, sample, BufferInfo(i * 23_220L, sampleBytes, C.BUFFER_FLAG_KEY_FRAME))
            }
            muxer.close()
        }
    }

    @Test
    fun `a song keeps its index in front of the audio after tagging`() {
        val file = folder.newFile("song.m4a")
        mux(file, samples = 8_000, tagRoom = 64 * 1024)
        val mdatBefore = Mp4Fixtures.parse(file.readBytes()).first { it.type == "mdat" }.start

        Mp4TagWriter().write(file, DownloadTags(kind = DownloadKind.MUSIC, videoId = "v", title = "Song"), ByteArray(40_000) { 1 })

        val boxes = Mp4Fixtures.parse(file.readBytes())
        assertThat(boxes.indexOfFirst { it.type == "moov" }).isLessThan(boxes.indexOfFirst { it.type == "mdat" })
        assertThat(boxes.first { it.type == "mdat" }.start).isEqualTo(mdatBefore)
    }

    @Test
    fun `a long file whose index outgrows the muxer's own room still ends with it in front`() {
        val file = folder.newFile("long.m4a")
        val samples = 120_000
        val cover = ByteArray(40_000) { 1 }
        mux(file, samples, tagRoom = indexRoomBytes(samples * 23_220L / 1000, videoFps = 0, coverBytes = cover.size), sampleBytes = 50)
        val before = Mp4Fixtures.parse(file.readBytes())
        assertThat(before.last().type).isEqualTo("moov")

        Mp4TagWriter().write(file, DownloadTags(kind = DownloadKind.MUSIC, videoId = "v", title = "Long"), cover)

        val boxes = Mp4Fixtures.parse(file.readBytes())
        assertThat(boxes.indexOfFirst { it.type == "moov" }).isLessThan(boxes.indexOfFirst { it.type == "mdat" })
        assertThat(boxes.last().type).isEqualTo("mdat")
        assertThat(boxes.first { it.type == "mdat" }.start).isEqualTo(before.first { it.type == "mdat" }.start)
    }

    @Test
    fun `the reserved room grows with length and frame rate and stays zero for short songs`() {
        assertThat(indexRoomBytes(durationMs = 180_000, videoFps = 0, coverBytes = 40_000)).isEqualTo(0)
        val tenMinutes60 = indexRoomBytes(durationMs = 600_000, videoFps = 60, coverBytes = 100_000)
        assertThat(tenMinutes60).isGreaterThan(1_144_705 - 400_000)
        assertThat(indexRoomBytes(durationMs = 600_000, videoFps = 30, coverBytes = 100_000)).isLessThan(tenMinutes60)
    }
}
