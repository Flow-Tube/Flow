package io.github.aedev.flow.player.quality

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import io.github.aedev.flow.player.stream.VideoCodecUtils
import io.github.aedev.flow.player.stream.model.VideoStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 * A codec that [needsHardware] never counts a software decoder: software AV1 stutters even at 720p60.
 */
internal fun decodableForAuto(
    answers: List<DecoderAnswer>,
    width: Int,
    height: Int,
    fps: Int,
    needsHardware: Boolean = false,
): Boolean {
    val softwareCanKeepUp = !needsHardware && max(width, height) <= 1920 && min(width, height) <= 1080 && fps <= 30
    return answers.any { it.supported && (it.hardware || softwareCanKeepUp) }
}

/** Whether a quality in [codecKey] is listed and picked: AV1 only on a device that decodes it in hardware. */
internal fun codecOffered(
    codecKey: String?,
    hardwareAv1: () -> Boolean,
): Boolean = !codecKey.equals(AV1_CODEC_KEY, ignoreCase = true) || hardwareAv1()

private const val AV1_CODEC_KEY = "av1"

/**
 * Auto's candidates for a screen that does or does not show HDR. On an SDR screen an HDR stream is
 * tone-mapped down at best and washed out at worst, so it is left for a manual pick, unless the
 * video has nothing else.
 */
internal fun withoutHdrOnSdrScreen(
    streams: List<VideoStream>,
    screenShowsHdr: Boolean,
): List<VideoStream> = if (screenShowsHdr) streams else streams.filterNot(VideoCodecUtils::isHdr).ifEmpty { streams }

/** HDR10 or HLG among a display's HDR types: the two transfer curves YouTube's HDR formats use. */
internal fun showsYouTubeHdr(hdrTypes: IntArray): Boolean =
    hdrTypes.any { it == Display.HdrCapabilities.HDR_TYPE_HDR10 || it == Display.HdrCapabilities.HDR_TYPE_HLG }

/**
 * The device's decoders, asked once per codec, size and rate. Auto only climbs to qualities that pass
 * [decodableForAuto]; a manual pick is never filtered, so any stream can still be forced.
 */
@UnstableApi
internal object VideoDecoderSupport {
    private const val TAG = "VideoDecoderSupport"
    private val PrewarmMimeTypes = listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_H265)

    private val answers = ConcurrentHashMap<String, Boolean>()
    private val hardwareDecoders = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var screenHdr: Boolean? = null

    private val watchingDisplay = AtomicBoolean(false)

    fun attach(context: Context) {
        appContext = context.applicationContext
        if (watchingDisplay.compareAndSet(false, true)) watchDisplay(context.applicationContext)
    }

    /** A TV box can be moved to another screen while Flow runs, so the HDR answer is asked again after a change. */
    private fun watchDisplay(context: Context) {
        val displays = context.getSystemService(DisplayManager::class.java) ?: return
        displays.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit

                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) screenHdr = null
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    /** Loads the decoder lists off the main thread; the first query per MIME type walks MediaCodecList. */
    fun prewarm(context: Context) {
        attach(context)
        PrewarmMimeTypes.forEach { mime -> runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false) } }
    }

    /** Whether any decoder for [mime] is hardware; true when the device cannot be asked, so nothing is hidden on a guess. */
    fun hasHardwareDecoder(mime: String): Boolean =
        hardwareDecoders.getOrPut(mime) {
            try {
                MediaCodecUtil.getDecoderInfos(mime, false, false).any { it.hardwareAccelerated }
            } catch (e: MediaCodecUtil.DecoderQueryException) {
                Log.w(TAG, "Decoder query failed for $mime", e)
                true
            }
        }

    /** Whether qualities in [codecKey] are listed in the quality pickers and taken by a pick by height. */
    fun offers(codecKey: String?): Boolean = codecOffered(codecKey) { hasHardwareDecoder(MimeTypes.VIDEO_AV1) }

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
                needsHardware = mime == MimeTypes.VIDEO_AV1,
            )
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            Log.w(TAG, "Decoder query failed for $mime", e)
            true
        }

    /**
     * Auto's candidates: the decodable ones, or all of them when the device says none is, without
     * HDR when the screen cannot show it.
     */
    fun forAuto(streams: List<VideoStream>): List<VideoStream> =
        withoutHdrOnSdrScreen(streams.filter(::canDecode).ifEmpty { streams }, screenShowsHdr())

    /** Asked once per screen: true when the screen cannot be asked, so nothing is held back on a guess. */
    private fun screenShowsHdr(): Boolean {
        screenHdr?.let { return it }
        val context = appContext ?: return true
        val types = defaultDisplayHdrTypes(context) ?: return true
        return showsYouTubeHdr(types).also {
            screenHdr = it
            Log.i(TAG, "Screen HDR10/HLG: $it (types ${types.joinToString()}); HDR in Auto: $it")
        }
    }

    private fun defaultDisplayHdrTypes(context: Context): IntArray? {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            display.mode.supportedHdrTypes
        } else {
            @Suppress("DEPRECATION")
            display.hdrCapabilities?.supportedHdrTypes
        }
    }
}
