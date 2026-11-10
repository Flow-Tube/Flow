package io.github.aedev.flow.ui.components.videoplayer.ambient

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

private const val SAMPLE_W = 96
private const val SAMPLE_H = 54
private const val DISPLAY_W = 32
private const val DISPLAY_H = 18

/** 96/32 == 54/18 == 3, so decimation is an exact 3x3 box and needs no resampler. */
private const val DECIMATION = 3

private const val CAPTURE_MS_ASYNC = 900L
private const val CAPTURE_MS_BLOCKING = 1800L

/** How often the frame is sampled: the bars and the glow change slowly, a lit page follows the picture. */
internal enum class AmbientCadence { STANDARD, LIVELY }

private const val LIVELY_CHANGING_MS = 250L
private const val LIVELY_STILL_MS = 1000L

/** How often a surface that is not ready yet is checked again, and how often a polled player is asked. */
internal const val IDLE_MS = 1500L

/**
 * Temporal smoothing. One exponential filter over the pixel buffer replaces what used to be three
 * overlapping Compose animations — `Crossfade(800ms)` on the frame plus `animateColorAsState(700ms)`
 * on base and accent.
 *
 * That mattered for more than tidiness. Compose animations run a frame every vsync, so the ambient
 * layer drove a full-screen redraw at the display's refresh rate for the whole of playback.
 * Stepping the filter at ~16 Hz and emitting only on change decouples the glow from the refresh
 * rate entirely.
 *
 * `alpha = 1 - exp(-dt / tau)` uses the *measured* elapsed time rather than a fixed per-tick
 * constant. The usual `current += (target - current) * 0.1` shortcut is a filter whose cutoff
 * depends on how often it happens to run, so it behaves differently on 60 Hz, on 120 Hz, and on a
 * thermally throttled device.
 *
 * SAFETY: tau also bounds how fast the glow can respond to strobing content. A first-order filter
 * at tau = 600 ms sits ~11x above its 0.265 Hz cutoff at 3 Hz, attenuating roughly 21 dB, which
 * keeps concert and animation footage under WCAG 2.3.1's three-flashes-per-second threshold. Do not
 * add a "snap on large delta" fast path to make cuts feel crisper — that is exactly the change that
 * reintroduces the hazard. If cuts feel soft, lower tau globally instead.
 */
private const val SMOOTH_TICK_MS = 60L
private const val SMOOTH_TAU_MS = 600f

/** Once every channel is within this of its target (linear units) the filter stops emitting. */
private const val CONVERGENCE_EPS = 0.0015f

private const val BLUR_RADIUS_PX = 2
private const val BLUR_PASSES = 3

private const val AMBIENT_GAIN = 0.28f

/**
 * Below this mean per-channel delta (0..255, sRGB) the frame is treated as unchanged and the
 * smoothing target is left alone. Static shots are most of a watch session.
 */
internal const val FRAME_CHANGE_THRESHOLD = 4

/** Every Nth pixel is compared for change detection; the sample is already tiny. */
internal const val CHANGE_SAMPLE_STEP = 7

/**
 * Only a permanent signal disables capture. `ERROR_SOURCE_INVALID` on a protected surface is a
 * platform guarantee that the readback will never succeed, so reissuing it every cadence for the
 * length of a video is pure waste.
 *
 * Everything else backs off but never latches. A surface can be legitimately unavailable for
 * seconds — during startup, after a fullscreen transition, or while the surface is re-created — and
 * a counter that disabled the effect after N of those would turn a transient hiccup into "ambient
 * is black for the rest of this video" with no way back.
 */
private const val MAX_BACKOFF_MULTIPLIER = 8L

private const val CAPTURE_OK = 0
private const val CAPTURE_RETRY = 1
private const val CAPTURE_UNSUPPORTED = 2

private const val LINEAR_LUT_SIZE = 4096

/** Exact sRGB EOTF; 256 entries covers every possible input byte. */
private val SRGB_TO_LINEAR =
    FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

/**
 * Inverse EOTF. 4096 entries rather than the ~2048 that would give half-LSB accuracy across most of
 * the range: the curve's slope is 12.92 near black, so a coarser table quantises visibly there —
 * and near-black is exactly where a dim glow lives.
 */
private val LINEAR_TO_SRGB =
    IntArray(LINEAR_LUT_SIZE + 1) { i ->
        val c = i / LINEAR_LUT_SIZE.toFloat()
        val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
        (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

/**
 * Owns every buffer the effect needs for the lifetime of one STARTED window. Everything is
 * allocated once: the loops below must not allocate in steady state.
 *
 * [pixelDispatcher] carries every per-pixel pass — the decimate/blur that builds a target and the
 * encode that turns the smoothed grid into a bitmap. Both loops themselves stay on the caller's
 * dispatcher (Main), which is what keeps the buffers they share free of locks.
 */
internal class AmbientPipeline(
    private val pixelDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val pageStyle: () -> AmbientPageStyle? = { null },
    private val capturePace: () -> AmbientCadence = { AmbientCadence.STANDARD },
) {
    private val sample = Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888)
    private val samplePixels = IntArray(SAMPLE_W * SAMPLE_H)
    private val previousPixels = IntArray(SAMPLE_W * SAMPLE_H)
    private val handler = Handler(Looper.getMainLooper())

    private val cells = DISPLAY_W * DISPLAY_H
    private val targetGrid = FloatArray(cells * 3)
    private val currentGrid = FloatArray(cells * 3)
    private val scratchGrid = FloatArray(cells * 3)
    private val stagingGrid = FloatArray(cells * 3)
    private val outPixels = IntArray(cells)

    // Double buffered: the render thread may still be uploading the bitmap handed over last tick.
    private val buffers =
        arrayOf(
            Bitmap.createBitmap(DISPLAY_W, DISPLAY_H, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(DISPLAY_W, DISPLAY_H, Bitmap.Config.ARGB_8888),
        )
    private var bufferIndex = 0

    private var hasPreviousSample = false
    private var hasTarget = false
    private var seeded = false
    private var supported = true
    private var consecutiveFailures = 0

    /**
     * Signalled whenever there is something new to publish. Conflated: the smoothing loop only
     * needs to know that it should look, not how many times it was told.
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var restyled = false
    private val pageRenderer by lazy { AmbientPageRenderer(DISPLAY_W, DISPLAY_H) }

    /** Publishes the current light again, for a page whose style changed while the frame did not. */
    fun requestPublish() {
        restyled = true
        wake.trySend(Unit)
    }

    suspend fun runCapture(
        playerView: PlayerView,
        isPlaying: Flow<Boolean>,
    ) {
        while (currentCoroutineContext().isActive && supported) {
            // Suspends while paused instead of polling, so a paused video costs nothing here.
            isPlaying.first { it }
            val surface = playerView.videoSurfaceView
            var cadence = IDLE_MS
            if (surface != null && surface.width > 0 && surface.height > 0) {
                val base = if (surface is TextureView) CAPTURE_MS_BLOCKING else CAPTURE_MS_ASYNC
                when (captureSurface(surface, sample, handler)) {
                    CAPTURE_OK -> {
                        consecutiveFailures = 0
                        // Heavy work off Main; withContext joins before anything is published, so
                        // the staging buffers are never read concurrently.
                        val accepted = withContext(pixelDispatcher) { computeTarget() }
                        // A TextureView readback blocks Main, so it keeps its slow pace whatever is asked.
                        cadence =
                            when {
                                capturePace() == AmbientCadence.STANDARD || surface is TextureView -> base
                                accepted -> LIVELY_CHANGING_MS
                                else -> LIVELY_STILL_MS
                            }
                        if (accepted) {
                            stagingGrid.copyInto(targetGrid)
                            hasTarget = true
                            wake.trySend(Unit)
                        }
                    }

                    CAPTURE_UNSUPPORTED -> {
                        supported = false
                    }

                    else -> {
                        consecutiveFailures++
                        val multiplier =
                            (1L shl consecutiveFailures.coerceAtMost(3))
                                .coerceAtMost(MAX_BACKOFF_MULTIPLIER)
                        cadence = base * multiplier
                    }
                }
            }
            delay(cadence)
        }
    }

    /** Runs on Default. Returns true when the frame differed enough to become a new target. */
    private fun computeTarget(): Boolean {
        sample.getPixels(samplePixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
        val changed =
            !hasPreviousSample ||
                meanAbsDiff(samplePixels, previousPixels) >= FRAME_CHANGE_THRESHOLD
        if (!changed) return false
        // previousPixels advances only on an accepted frame, so a slow fade accumulates against a
        // fixed reference instead of never clearing the threshold.
        samplePixels.copyInto(previousPixels)
        hasPreviousSample = true

        decimateToLinear(samplePixels, stagingGrid)
        boxBlurLinear(stagingGrid, scratchGrid, DISPLAY_W, DISPLAY_H, BLUR_RADIUS_PX, BLUR_PASSES)
        return true
    }

    suspend fun runSmoothing(emit: (AmbientFrameState) -> Unit) {
        var last = SystemClock.elapsedRealtime()
        while (currentCoroutineContext().isActive) {
            if (!hasTarget) {
                wake.receive()
                last = SystemClock.elapsedRealtime()
                continue
            }
            if (!seeded) {
                targetGrid.copyInto(currentGrid)
                seeded = true
                emit(publish())
                delay(SMOOTH_TICK_MS)
                last = SystemClock.elapsedRealtime()
                continue
            }

            val now = SystemClock.elapsedRealtime()
            val dt = (now - last).coerceIn(1L, 500L)
            last = now
            val alpha = 1f - exp(-dt.toFloat() / SMOOTH_TAU_MS)

            if (step(currentGrid, targetGrid, alpha) || restyled) {
                emit(publish())
                delay(SMOOTH_TICK_MS)
            } else {
                // Converged: nothing changes until capture sets a new target.
                wake.receive()
                last = SystemClock.elapsedRealtime()
            }
        }
    }

    /**
     * The encode is 576 cells of inverse-EOTF plus a bitmap upload, and it ran on Main once per
     * 60 ms tick for the whole of playback.
     *
     * Hopping it needs no copy of [currentGrid]: the smoothing loop is a single sequential
     * coroutine, so it cannot step the grid while it is awaiting this, and no other loop touches
     * [currentGrid], [outPixels] or [buffers]. [supported] is read on the caller instead, because
     * that one *is* written by the capture loop.
     */
    internal suspend fun publish(): AmbientFrameState {
        restyled = false
        val style = pageStyle()
        val isSupported = supported
        return withContext(pixelDispatcher) {
            AmbientFrameState(
                frame = encodeFrame(),
                supported = isSupported,
                page = style?.let { pageRenderer.render(currentGrid, it) },
            )
        }
    }

    /** Runs on [pixelDispatcher]. */
    private fun encodeFrame(): ImageBitmap {
        encodeToPixels(currentGrid, outPixels)
        bufferIndex = bufferIndex xor 1
        val bitmap = buffers[bufferIndex]
        bitmap.setPixels(outPixels, 0, DISPLAY_W, 0, 0, DISPLAY_W, DISPLAY_H)
        return bitmap.asImageBitmap()
    }
}

/** Advances [current] toward [target]; returns whether anything moved beyond the epsilon. */
internal fun step(
    current: FloatArray,
    target: FloatArray,
    alpha: Float,
): Boolean {
    var moved = false
    for (i in current.indices) {
        val delta = target[i] - current[i]
        if (abs(delta) > CONVERGENCE_EPS) {
            current[i] += delta * alpha
            moved = true
        } else {
            current[i] = target[i]
        }
    }
    return moved
}

/**
 * Fuses the 3x3 decimation with the sRGB to linear conversion in one pass.
 *
 * The previous implementation used `createScaledBitmap(..., filter = true)`, which is bilinear: it
 * samples 2x2 texels where a 3x reduction needs 3x3, so roughly half the source was discarded and
 * the aliasing the 96x54 capture existed to prevent came back as a crawling glow on fine detail.
 * Averaging in gamma-encoded sRGB was the other half — mixing saturated complementaries there lands
 * darker than either input.
 */
private fun decimateToLinear(
    src: IntArray,
    dst: FloatArray,
) {
    val n = (DECIMATION * DECIMATION).toFloat()
    var o = 0
    for (y in 0 until DISPLAY_H) {
        for (x in 0 until DISPLAY_W) {
            var r = 0f
            var g = 0f
            var b = 0f
            for (dy in 0 until DECIMATION) {
                var idx = (y * DECIMATION + dy) * SAMPLE_W + x * DECIMATION
                for (dx in 0 until DECIMATION) {
                    val c = src[idx++]
                    r += SRGB_TO_LINEAR[(c shr 16) and 0xFF]
                    g += SRGB_TO_LINEAR[(c shr 8) and 0xFF]
                    b += SRGB_TO_LINEAR[c and 0xFF]
                }
            }
            dst[o++] = r / n
            dst[o++] = g / n
            dst[o++] = b / n
        }
    }
}

internal fun boxBlurLinear(
    buf: FloatArray,
    scratch: FloatArray,
    w: Int,
    h: Int,
    radius: Int,
    passes: Int,
) {
    if (radius <= 0 || w <= 0 || h <= 0) return
    repeat(passes) {
        blurAxisLinear(buf, scratch, w, h, radius, horizontal = true)
        blurAxisLinear(scratch, buf, w, h, radius, horizontal = false)
    }
}

private fun blurAxisLinear(
    src: FloatArray,
    dst: FloatArray,
    w: Int,
    h: Int,
    radius: Int,
    horizontal: Boolean,
) {
    val lines = if (horizontal) h else w
    val span = if (horizontal) w else h
    for (line in 0 until lines) {
        for (i in 0 until span) {
            var r = 0f
            var g = 0f
            var b = 0f
            var n = 0
            for (k in -radius..radius) {
                val j = (i + k).coerceIn(0, span - 1)
                val o = (if (horizontal) line * w + j else j * w + line) * 3
                r += src[o]
                g += src[o + 1]
                b += src[o + 2]
                n++
            }
            val o = (if (horizontal) line * w + i else i * w + line) * 3
            dst[o] = r / n
            dst[o + 1] = g / n
            dst[o + 2] = b / n
        }
    }
}

private fun encodeToPixels(
    grid: FloatArray,
    out: IntArray,
) {
    for (i in out.indices) {
        val o = i * 3
        out[i] = (0xFF shl 24) or
            (linearToSrgb(grid[o] * AMBIENT_GAIN) shl 16) or
            (linearToSrgb(grid[o + 1] * AMBIENT_GAIN) shl 8) or
            linearToSrgb(grid[o + 2] * AMBIENT_GAIN)
    }
}

/**
 * Rounds rather than truncates into the table. Flooring costs up to a full table step, which near
 * black — where the EOTF slope is 12.92 — is enough to lose a whole output level and break the
 * round trip.
 */
internal fun linearToSrgb(v: Float): Int = LINEAR_TO_SRGB[(v.coerceIn(0f, 1f) * LINEAR_LUT_SIZE + 0.5f).toInt()]

internal fun srgbToLinear(byteValue: Int): Float = SRGB_TO_LINEAR[byteValue.coerceIn(0, 255)]

/** Mean absolute per-channel difference over a strided subset of the two buffers. */
internal fun meanAbsDiff(
    current: IntArray,
    previous: IntArray,
): Int {
    var sum = 0L
    var count = 0
    var i = 0
    while (i < current.size) {
        val a = current[i]
        val b = previous[i]
        sum += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        sum += abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        sum += abs((a and 0xFF) - (b and 0xFF))
        count += 3
        i += CHANGE_SAMPLE_STEP
    }
    return if (count == 0) 0 else (sum / count).toInt()
}

private suspend fun captureSurface(
    surface: View,
    dst: Bitmap,
    handler: Handler,
): Int =
    suspendCancellableCoroutine { cont ->
        try {
            when {
                surface is TextureView -> {
                    if (surface.isAvailable) {
                        surface.getBitmap(dst)
                        cont.resume(CAPTURE_OK)
                    } else {
                        cont.resume(CAPTURE_RETRY)
                    }
                }

                surface is SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                    val holderSurface = surface.holder?.surface
                    if (holderSurface != null && holderSurface.isValid) {
                        PixelCopy.request(
                            surface,
                            dst,
                            { result ->
                                cont.resume(
                                    when (result) {
                                        PixelCopy.SUCCESS -> CAPTURE_OK
                                        PixelCopy.ERROR_SOURCE_INVALID -> CAPTURE_UNSUPPORTED
                                        else -> CAPTURE_RETRY
                                    },
                                )
                            },
                            handler,
                        )
                    } else {
                        cont.resume(CAPTURE_RETRY)
                    }
                }

                else -> {
                    cont.resume(CAPTURE_RETRY)
                }
            }
        } catch (t: Throwable) {
            cont.resume(CAPTURE_RETRY)
        }
    }
