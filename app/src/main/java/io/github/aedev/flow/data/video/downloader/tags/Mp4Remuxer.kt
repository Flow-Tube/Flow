package io.github.aedev.flow.data.video.downloader.tags

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import io.github.aedev.flow.data.video.downloader.transfer.DiskFull
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * Copies YouTube DASH streams (fragmented MP4 or WebM) into one plain MP4, its `moov` box first
 * when the reserved room holds it, interleaving samples by timestamp and embedding the Flow ids as
 * `mdta` entries.
 * Accepts H.264, HEVC, VP9 and AV1 video and AAC audio only; Opus/Vorbis in MP4 is refused
 * (Media3 mis-reads Opus pre-skip in MP4, androidx/media#3431), so callers must pick AAC.
 */
@OptIn(UnstableApi::class)
class Mp4Remuxer
    @Inject
    constructor() {
        sealed interface Result {
            data class Success(
                val containerMimeType: String,
                val videoMimeType: String?,
                val audioMimeType: String,
            ) : Result

            data class Failure(
                val reason: Reason,
                val message: String,
            ) : Result
        }

        enum class Reason { INPUT_MISSING, NO_TRACK, UNSUPPORTED_AUDIO, UNSUPPORTED_VIDEO, CANCELLED, MUX_FAILED, DISK_FULL }

        private class Input(
            val extractor: MediaExtractor,
            val format: MediaFormat,
            val mime: String,
            val isVideo: Boolean,
        ) {
            var muxerTrack = C.INDEX_UNSET
            var done = false
            val durationUs: Long =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else C.TIME_UNSET
        }

        private class Rejection(
            val failure: Result.Failure,
        ) : Exception(failure.message)

        fun remux(
            videoPath: String?,
            audioPath: String,
            outputPath: String,
            tags: DownloadTags? = null,
            tagRoomBytes: Int = 0,
            isCancelled: () -> Boolean = { false },
            onProgress: ((Float) -> Unit)? = null,
        ): Result {
            val inputs = mutableListOf<Input>()
            val output = File(outputPath)
            var completed = false
            return try {
                videoPath?.let { inputs += open(it, isVideo = true) }
                inputs += open(audioPath, isVideo = false)
                output.parentFile?.mkdirs()
                FileOutputStream(output).use { stream ->
                    // The index goes first when it fits, so a cast receiver or a network share can start
                    // playing without reading to the end; tagRoomBytes leaves the tag writer space beside it.
                    val muxer =
                        Mp4Muxer
                            .Builder(SeekableMuxerOutput.of(stream))
                            .setAttemptStreamableOutputEnabled(true)
                            .experimentalSetFreeSpaceAfterFileTypeBox(tagRoomBytes)
                            .build()
                    var closed = false
                    try {
                        inputs.forEach { it.muxerTrack = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(it.format)) }
                        tags?.let { FlowTagFields.encode(it) }?.forEach { (field, value) ->
                            muxer.addMetadataEntry(
                                MdtaMetadataEntry(
                                    FlowTagFields.mdtaKey(field),
                                    value.toByteArray(Charsets.UTF_8),
                                    MdtaMetadataEntry.TYPE_INDICATOR_STRING,
                                ),
                            )
                        }
                        val written = copyInterleaved(inputs, muxer, isCancelled, onProgress)
                        if (written == 0L) throw Rejection(Result.Failure(Reason.NO_TRACK, "No samples to write"))
                        muxer.close()
                        closed = true
                    } finally {
                        if (!closed) runCatching { muxer.close() }
                    }
                }
                onProgress?.invoke(1f)
                completed = true
                val audioMime = inputs.last().mime
                val videoMime = inputs.firstOrNull { it.isVideo }?.mime
                Result.Success(
                    containerMimeType = if (videoMime == null) MimeTypes.AUDIO_MP4 else MimeTypes.VIDEO_MP4,
                    videoMimeType = videoMime,
                    audioMimeType = audioMime,
                )
            } catch (rejection: Rejection) {
                rejection.failure
            } catch (e: Exception) {
                Log.e(TAG, "Remux failed", e)
                Result.Failure(if (DiskFull.isCause(e)) Reason.DISK_FULL else Reason.MUX_FAILED, e.message ?: e.javaClass.simpleName)
            } finally {
                inputs.forEach { runCatching { it.extractor.release() } }
                if (!completed) output.delete()
            }
        }

        private fun open(
            path: String,
            isVideo: Boolean,
        ): Input {
            val file = File(path)
            if (!file.isFile || file.length() == 0L) throw Rejection(Result.Failure(Reason.INPUT_MISSING, "Missing or empty input: $path"))
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(path)
                val prefix = if (isVideo) "video/" else "audio/"
                val index =
                    (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
                    } ?: throw Rejection(Result.Failure(Reason.NO_TRACK, "No $prefix track in $path"))
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (isVideo && mime !in SUPPORTED_VIDEO) {
                    throw Rejection(Result.Failure(Reason.UNSUPPORTED_VIDEO, "Video codec $mime cannot go into MP4"))
                }
                if (!isVideo && mime != MimeTypes.AUDIO_AAC) {
                    throw Rejection(Result.Failure(Reason.UNSUPPORTED_AUDIO, "Audio codec $mime is not AAC"))
                }
                extractor.selectTrack(index)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                return Input(extractor, format, mime, isVideo)
            } catch (e: Exception) {
                extractor.release()
                throw e
            }
        }

        private fun copyInterleaved(
            inputs: List<Input>,
            muxer: Mp4Muxer,
            isCancelled: () -> Boolean,
            onProgress: ((Float) -> Unit)?,
        ): Long {
            val totalUs = inputs.maxOf { it.durationUs }.takeIf { it > 0 }
            var buffer = ByteBuffer.allocateDirect(INITIAL_BUFFER_BYTES)
            var samples = 0L
            var lastReported = -1
            while (true) {
                if (isCancelled()) throw Rejection(Result.Failure(Reason.CANCELLED, "Remux cancelled"))
                var next: Input? = null
                var timeUs = Long.MAX_VALUE
                for (input in inputs) {
                    if (input.done) continue
                    val sampleTimeUs = input.extractor.sampleTime
                    if (sampleTimeUs == END_OF_STREAM) {
                        input.done = true
                    } else if (next == null || sampleTimeUs < timeUs) {
                        next = input
                        timeUs = sampleTimeUs
                    }
                }
                if (next == null) return samples
                val extractor = next.extractor
                buffer = ensureCapacity(buffer, next)
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    next.done = true
                    continue
                }
                buffer.position(0).limit(size)
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) C.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(next.muxerTrack, buffer, BufferInfo(timeUs, size, flags))
                samples++
                extractor.advance()
                if (onProgress != null && totalUs != null) {
                    val percent = (timeUs * 100 / totalUs).toInt().coerceIn(0, 99)
                    if (percent != lastReported) {
                        lastReported = percent
                        onProgress(percent / 100f)
                    }
                }
            }
        }

        private fun ensureCapacity(
            buffer: ByteBuffer,
            input: Input,
        ): ByteBuffer {
            val needed =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    input.extractor.sampleSize
                } else {
                    MediaFormatUtil.getInteger(input.format, MediaFormat.KEY_MAX_INPUT_SIZE, MAX_LEGACY_SAMPLE_BYTES).toLong()
                }
            if (needed <= buffer.capacity()) return buffer
            return ByteBuffer.allocateDirect((needed + needed / 4).toInt())
        }

        private companion object {
            const val TAG = "Mp4Remuxer"
            const val END_OF_STREAM = -1L
            const val INITIAL_BUFFER_BYTES = 1 shl 20
            const val MAX_LEGACY_SAMPLE_BYTES = 8 shl 20

            val SUPPORTED_VIDEO =
                setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_AV1)
        }
    }

/**
 * Room to reserve ahead of `mdat` so the tagged index can sit at the front: Mp4Muxer spends about
 * 20 bytes of `moov` per sample (measured 17.5 with a cover on a 10-minute 60 fps file), plus the
 * cover and text tags, less the 400,000 bytes the muxer already reserves for its own index.
 */
internal fun indexRoomBytes(
    durationMs: Long,
    videoFps: Int,
    coverBytes: Int,
): Int {
    val samples = durationMs / 1000.0 * (videoFps + AUDIO_FRAMES_PER_SECOND)
    val needed = (samples * BYTES_PER_SAMPLE).toLong() + coverBytes + TEXT_TAG_BYTES
    return (needed - MUXER_RESERVED_BYTES).coerceIn(0L, MAX_INDEX_ROOM_BYTES).toInt()
}

private const val AUDIO_FRAMES_PER_SECOND = 48
private const val BYTES_PER_SAMPLE = 20
private const val TEXT_TAG_BYTES = 64 * 1024
private const val MUXER_RESERVED_BYTES = 400_000L
private const val MAX_INDEX_ROOM_BYTES = 32L * 1024 * 1024
