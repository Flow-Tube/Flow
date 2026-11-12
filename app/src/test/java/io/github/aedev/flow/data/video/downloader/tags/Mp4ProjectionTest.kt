package io.github.aedev.flow.data.video.downloader.tags

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/** Muxes a real file with Media3's Mp4Muxer, marks it, and reads it back with Media3's Mp4Extractor. */
class Mp4ProjectionTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun configureMedia3ForJvm() {
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
    }

    @After
    fun restoreMedia3Defaults() {
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(null)
        Log.setLogLevel(Log.LOG_LEVEL_ALL)
    }

    private val equiPayload = ByteArray(20)
    private val mp4Proj = box("proj", fullBox("prhd", ByteArray(12)) + box("equi", equiPayload))

    @Test
    fun `an mp4 proj box reaches the player with its stereo mode and the samples stay readable`() {
        val file = mux()
        val boxes = Mp4Projection.boxes(mp4Proj, C.STEREO_MODE_TOP_BOTTOM)!!

        assertThat(mark(file, boxes)).isTrue()

        val read = extract(file)
        val video = read.formats.single { MimeTypes.isVideo(it.sampleMimeType) }
        assertThat(video.projectionData).isEqualTo(mp4Proj)
        assertThat(video.stereoMode).isEqualTo(C.STEREO_MODE_TOP_BOTTOM)
        assertThat(read.formats.single { MimeTypes.isAudio(it.sampleMimeType) }.projectionData).isNull()
        assertThat(read.sampleBytes).isEqualTo(SAMPLES.sumOf { it.size } * 2)
    }

    @Test
    fun `a webm equirectangular payload becomes an equi box`() {
        val file = mux()

        assertThat(mark(file, Mp4Projection.boxes(equiPayload, Format.NO_VALUE)!!)).isTrue()

        val video = extract(file).formats.single { MimeTypes.isVideo(it.sampleMimeType) }
        assertThat(video.projectionData).isEqualTo(mp4Proj)
        assertThat(video.stereoMode).isEqualTo(Format.NO_VALUE)
    }

    @Test
    fun `a webm mesh payload becomes an mshp box`() {
        val mesh = ByteArray(4) + ByteArray(4) + "dfl8".toByteArray(Charsets.ISO_8859_1) + ByteArray(40) { it.toByte() }
        val proj = Mp4Fixtures.parse(Mp4Projection.boxes(mesh, C.STEREO_MODE_MONO)!!).single { it.type == "sv3d" }.child("proj")!!

        assertThat(proj.children().map { it.type }).containsExactly("prhd", "mshp").inOrder()
        assertThat(proj.child("mshp")!!.payload()).isEqualTo(mesh)
    }

    @Test
    fun `an unknown projection payload is left alone`() {
        assertThat(Mp4Projection.boxes(ByteArray(7), C.STEREO_MODE_MONO)).isNull()
        assertThat(Mp4Projection.boxes(box("proj", ByteArray(4)) + ByteArray(3), C.STEREO_MODE_MONO)).isNull()
    }

    @Test
    fun `a file already marked is not marked twice`() {
        val file = mux()
        val boxes = Mp4Projection.boxes(mp4Proj, C.STEREO_MODE_MONO)!!
        mark(file, boxes)
        val once = file.readBytes()

        assertThat(mark(file, boxes)).isFalse()
        assertThat(file.readBytes()).isEqualTo(once)
    }

    @Test
    fun `an audio only file is left alone`() {
        val file = mux(withVideo = false)
        val before = file.readBytes()

        assertThat(mark(file, Mp4Projection.boxes(mp4Proj, C.STEREO_MODE_MONO)!!)).isFalse()
        assertThat(file.readBytes()).isEqualTo(before)
    }

    private fun mark(
        file: File,
        boxes: ByteArray,
    ): Boolean {
        var added = false
        Mp4MoovFile.edit(file) { moov, headerSize -> Mp4Projection.addToVideoTrack(moov, headerSize, boxes)?.also { added = true } }
        return added
    }

    private fun mux(withVideo: Boolean = true): File {
        val file = folder.newFile()
        FileOutputStream(file).use { stream ->
            val muxer = Mp4Muxer.Builder(SeekableMuxerOutput.of(stream)).setAttemptStreamableOutputEnabled(false).build()
            val audio =
                muxer.addTrack(
                    Format
                        .Builder()
                        .setSampleMimeType(MimeTypes.AUDIO_AAC)
                        .setSampleRate(44_100)
                        .setChannelCount(2)
                        .setInitializationData(listOf(byteArrayOf(0x12, 0x10)))
                        .build(),
                )
            val video =
                if (withVideo) {
                    muxer.addTrack(
                        Format
                            .Builder()
                            .setSampleMimeType(
                                MimeTypes.VIDEO_AV1,
                            ).setWidth(176)
                            .setHeight(144)
                            .setInitializationData(listOf(AV1C))
                            .build(),
                    )
                } else {
                    null
                }
            SAMPLES.forEachIndexed { index, sample ->
                val timeUs = index * 40_000L
                muxer.writeSampleData(audio, ByteBuffer.wrap(sample), BufferInfo(timeUs, sample.size, C.BUFFER_FLAG_KEY_FRAME))
                video?.let { muxer.writeSampleData(it, ByteBuffer.wrap(sample), BufferInfo(timeUs, sample.size, C.BUFFER_FLAG_KEY_FRAME)) }
            }
            muxer.close()
        }
        return file
    }

    private class Extracted(
        val formats: List<Format>,
        val sampleBytes: Int,
    )

    private fun extract(file: File): Extracted {
        val bytes = file.readBytes()
        val output = CapturingOutput()
        val extractor: Extractor = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED)
        extractor.init(output)
        val position = PositionHolder()
        var input = inputAt(bytes, 0)
        while (true) {
            when (extractor.read(input, position)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> input = inputAt(bytes, position.position)
            }
        }
        return Extracted(output.tracks.mapNotNull { it.format }, output.tracks.sumOf { it.bytes })
    }

    private fun inputAt(
        bytes: ByteArray,
        start: Long,
    ): DefaultExtractorInput {
        var position = start.toInt()
        val reader =
            DataReader { buffer, offset, length ->
                if (position >= bytes.size) {
                    C.RESULT_END_OF_INPUT
                } else {
                    val count = minOf(length, bytes.size - position)
                    System.arraycopy(bytes, position, buffer, offset, count)
                    position += count
                    count
                }
            }
        return DefaultExtractorInput(reader, start, bytes.size.toLong())
    }

    private class CapturingOutput : ExtractorOutput {
        val tracks = mutableListOf<CapturingTrack>()

        override fun track(
            id: Int,
            type: Int,
        ): TrackOutput = CapturingTrack().also { tracks += it }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class CapturingTrack : TrackOutput {
        var format: Format? = null
        var bytes = 0

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            val read = input.read(ByteArray(length), 0, length)
            if (read != C.RESULT_END_OF_INPUT) bytes += read
            return read
        }

        override fun sampleData(
            data: ParsableByteArray,
            length: Int,
            sampleDataPart: Int,
        ) {
            data.skipBytes(length)
            bytes += length
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) = Unit
    }

    private fun box(
        type: String,
        payload: ByteArray,
    ): ByteArray = Mp4Fixtures.box(type, payload)

    private fun fullBox(
        type: String,
        payload: ByteArray,
    ): ByteArray = box(type, ByteArray(4) + payload)

    private companion object {
        val AV1C = byteArrayOf(0x81.toByte(), 0x00, 0x0C, 0x00)
        val SAMPLES = List(6) { index -> ByteArray(64 + index * 16) { (it * 7 + index).toByte() } }
    }
}
