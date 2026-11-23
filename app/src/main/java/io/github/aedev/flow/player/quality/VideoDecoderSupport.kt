package io.github.aedev.flow.player.quality

import android.content.Context
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import io.github.aedev.flow.player.stream.model.VideoStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/** One decoder's answer for a stream: whether it is hardware, and whether it takes the stream's profile, size and rate. */
internal data class DecoderAnswer(
    val hardware: Boolean,
    val supported: Boolean,
)

/**
 * Whether a stream should be offered to Auto: a hardware decoder takes it, or a software one does and the
 * stream is no bigger than 1080p at 30 fps. A software decoder at 1440p60 drops frames and heats the phone.
 */
internal fun decodableForAuto(
    answers: List<DecoderAnswer>,
    width: Int,
    height: Int,
    fps: Int,
): Boolean {
    val softwareCanKeepUp = max(width, height) <= 1920 && min(width, height) <= 1080 && fps <= 30
    return answers.any { it.supported && (it.hardware || softwareCanKeepUp) }
}

/**
 * The device's decoders, asked once per codec, size and rate. Auto only climbs to qualities that pass
 * [decodableForAuto]; a manual pick is never filtered, so any stream can still be forced.
 */
@UnstableApi
internal object VideoDecoderSupport {
    private const val TAG = "VideoDecoderSupport"
    private val PrewarmMimeTypes = listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_H265)

    private val answers = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** Loads the decoder lists off the main thread; the first query per MIME type walks MediaCodecList. */
    fun prewarm(context: Context) {
        attach(context)
        PrewarmMimeTypes.forEach { mime -> runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false) } }
    }

    fun canDecode(stream: VideoStream): Boolean {
        val itag = stream.itagItem ?: return true
        return canDecode(itag.codec, itag.width, itag.height, itag.fps)
    }

    /** The same answer for a stream known only by its RFC 6381 [codecs] string, size and rate. */
    fun canDecode(
        codecs: String?,
        width: Int,
        height: Int,
        fps: Int,
    ): Boolean {
        val context = appContext ?: return true
        val mime = MimeTypes.getVideoMediaMimeType(codecs)?.takeIf { it != MimeTypes.VIDEO_UNKNOWN } ?: return true
        if (width <= 0 || height <= 0) return true
        val rate = fps.coerceAtLeast(0)
        return answers.getOrPut("$mime|$codecs|${width}x$height@$rate") { query(context, mime, codecs, width, height, rate) }
    }

    private fun query(
        context: Context,
        mime: String,
        codecs: String?,
        width: Int,
        height: Int,
        fps: Int,
    ): Boolean =
        try {
            val format =
                Format
                    .Builder()
                    .setSampleMimeType(mime)
                    .setCodecs(codecs)
                    .setWidth(width)
                    .setHeight(height)
                    .apply { if (fps > 0) setFrameRate(fps.toFloat()) }
                    .build()
            val decoders = MediaCodecUtil.getDecoderInfos(mime, false, false)
            decodableForAuto(
                decoders.map { DecoderAnswer(it.hardwareAccelerated, it.isFormatSupported(context, format)) },
                width,
                height,
                fps,
            )
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            Log.w(TAG, "Decoder query failed for $mime", e)
            true
        }

    /** Auto's candidates: the decodable ones, or all of them when the device says none is. */
    fun forAuto(streams: List<VideoStream>): List<VideoStream> = streams.filter(::canDecode).ifEmpty { streams }
}
