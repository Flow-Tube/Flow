package io.github.aedev.flow.ui.components.videoplayer.ambient

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.exp

private const val CAPTURE_MS_ASYNC = 750L
private const val CAPTURE_MS_BLOCKING = 1800L

/** How often the frame is sampled: the bars and the glow change slowly, a lit page follows the picture. */
internal enum class AmbientCadence { STANDARD, LIVELY }

private const val LIVELY_CHANGING_MS = 250L
private const val LIVELY_STILL_MS = 1000L

/** How often a surface that is not ready yet is checked again, and how often a polled player is asked. */
internal const val IDLE_MS = 1500L

/**
 * Temporal smoothing for a lit page, which samples every [LIVELY_CHANGING_MS]. One exponential filter over the pixel buffer replaces what used to be three
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

/**
 * Live frames at the standard pace, storyboard frames and thumbnails arrive hundreds of ms or
 * seconds apart, so they crossfade instead of going through the filter. The fade steps on its own 15 fps clock and only moves an alpha read in draw, so it
 * never drives the display at its refresh rate; at the glow's brightness a step is about one sRGB
 * level. One change per [MIN_STORYBOARD_STEP_MS] stays far under WCAG 2.3.1's three flashes a second.
 */
private const val FADE_TICK_MS = 66L

/**
 * A live frame at the standard pace is current when it is read, so it fades in quickly and the
 * light trails the picture by under a second without ever running ahead of it. Captures are at
 * least [CAPTURE_MS_ASYNC] apart, so the fades stay far under three flashes a second.
 */
private const val LIVE_FADE_MS = 450L

/** A seek, a resume or a paused frame is a deliberate jump, so it settles sooner. */
private const val JUMP_FADE_MS = 1_000L

/** How often a warm device samples the live surface. */
private const val HOT_CAPTURE_MS = 4_500L

/** The first light of a video, from its thumbnail. */
private const val SEED_FADE_MS = 500L

/** Below this mean change (linear units) a new still is not worth a fade. */
private const val STILL_CHANGE_EPS = 0.006f

/** Output images in flight: two on screen during a fade, two more being written. */
internal const val OUTPUT_RING = 4

/**
 * Owns every buffer the light needs for as long as its holder lives. Everything is allocated once:
 * the loops below must not allocate in steady state.
 *
 * Sources ([runSources]) hand targets to one presenter ([runPresenter]). Both run on the caller's
 * dispatcher (Main), which keeps the buffers they share free of locks; every per-pixel pass hops
 * to [pixelDispatcher] and is joined before anything is published.
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
    private val stillSourceGrid = FloatArray(cells * 3)
    private val stillGrid = FloatArray(cells * 3)
    private val lastStillGrid = FloatArray(cells * 3)
    private val fromGrid = FloatArray(cells * 3)
    private val toGrid = FloatArray(cells * 3)
    private val renderScratch = FloatArray(cells * 3)
    private val outPixels = IntArray(cells)

    // The render thread may still be uploading images handed over earlier, so writes rotate.
    private val buffers = Array(OUTPUT_RING) { Bitmap.createBitmap(DISPLAY_W, DISPLAY_H, Bitmap.Config.ARGB_8888) }
    private var bufferIndex = 0

    private var hasPreviousSample = false
    private var supported = true
    private var consecutiveFailures = 0

    private var shown = false
    private var fadeMode = false
    private var fadeProgress = 1f
    private var filtering = false
    private var filterPending = false
    private var stillPending = false
    private var stillFadeMs = 0L
    private var hasLastStill = false

    /**
     * Signalled whenever there is something new to publish. Conflated: the presenter only needs to
     * know that it should look, not how many times it was told.
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var restyled = false
    private val pageRenderer by lazy { AmbientPageRenderer(DISPLAY_W, DISPLAY_H) }

    /** Publishes the current light again, for a page whose style changed while the frame did not. */
    fun requestPublish() {
        restyled = true
        wake.trySend(Unit)
    }

    /** Forgets what is on screen, for a holder whose light was cleared. */
    fun reset() {
        shown = false
        fadeMode = false
        filtering = false
        filterPending = false
        stillPending = false
        hasLastStill = false
    }

    /**
     * Feeds the presenter for one video: its [thumbnail] first, then the live surface, which keeps
     * the light on the frame being shown. [storyboard] frames stand in where reading the surface is
     * impossible, blocks Main, or [hot] asks for less work; see [ambientUsesStoryboard].
     */
    suspend fun runSources(
        playerView: PlayerView,
        isPlaying: Flow<Boolean>,
        storyboard: AmbientStoryboardFrames?,
        thumbnail: (suspend () -> AmbientStill?)?,
        hot: () -> Boolean,
    ) {
        supported = true
        consecutiveFailures = 0
        hasPreviousSample = false
        var board = storyboard
        thumbnail?.invoke()?.let { offerStill(it, SEED_FADE_MS) }
        var jumped = true
        while (currentCoroutineContext().isActive) {
            val useBoard = ambientUsesStoryboard(board != null, supported, hot(), playerView.videoSurfaceView is TextureView)
            if (!useBoard && !supported) return
            val playing = isPlaying.first()
            if (board != null && useBoard) {
                val player = playerView.player
                val position = player?.currentPosition ?: 0L
                val step = storyboardStepMs(board.intervalMs)
                val fade = storyboardFadeMs(step)
                val target = if (playing) storyboardTargetMs(position, fade, player?.duration ?: 0L) else position
                val crop = board.sheetAt(target)
                if (crop == null) {
                    board = null
                    continue
                }
                val frames = board
                withContext(pixelDispatcher) { frames.toGrid(crop, stillSourceGrid, renderScratch) }
                offerStill(stillSourceGrid, if (jumped || !playing) JUMP_FADE_MS else fade)
                if (playing && player != null) {
                    val wait = storyboardDelayMs(position, step, fade, player.playbackParameters.speed)
                    jumped = withTimeoutOrNull(wait) { player.renderedFrames().first() } != null
                } else {
                    awaitPlayingOrNewFrame(playerView, isPlaying)
                    jumped = true
                }
            } else {
                val cadence = capturePass(playerView, hot())
                if (playing) delay(cadence) else awaitPlayingOrNewFrame(playerView, isPlaying)
            }
        }
    }

    /** One readback of the live surface; returns how long to wait before the next. */
    private suspend fun capturePass(
        playerView: PlayerView,
        hot: Boolean,
    ): Long {
        val surface = playerView.videoSurfaceView
        if (surface == null || surface.width <= 0 || surface.height <= 0) return IDLE_MS
        val base = if (surface is TextureView) CAPTURE_MS_BLOCKING else CAPTURE_MS_ASYNC
        return when (captureSurface(surface, sample, handler)) {
            CAPTURE_OK -> {
                consecutiveFailures = 0
                // Heavy work off Main; withContext joins before anything is published, so the
                // staging buffers are never read concurrently.
                val accepted = withContext(pixelDispatcher) { computeTarget() }
                if (accepted && capturePace() == AmbientCadence.STANDARD) {
                    offerStill(stagingGrid, LIVE_FADE_MS)
                } else if (accepted) {
                    stagingGrid.copyInto(targetGrid)
                    filterPending = true
                    hasLastStill = false
                    wake.trySend(Unit)
                }
                // A TextureView readback blocks Main, so it keeps its slow pace whatever is asked.
                when {
                    hot -> maxOf(base, HOT_CAPTURE_MS)
                    capturePace() == AmbientCadence.STANDARD || surface is TextureView -> base
                    accepted -> LIVELY_CHANGING_MS
                    else -> LIVELY_STILL_MS
                }
            }

            CAPTURE_UNSUPPORTED -> {
                supported = false
                0L
            }

            else -> {
                consecutiveFailures++
                base * (1L shl consecutiveFailures.coerceAtMost(3)).coerceAtMost(MAX_BACKOFF_MULTIPLIER)
            }
        }
    }

    private suspend fun awaitPlayingOrNewFrame(
        playerView: PlayerView,
        isPlaying: Flow<Boolean>,
    ) {
        val playing = isPlaying.filter { it }.map { }
        val player = playerView.player
        (if (player == null) playing else merge(playing, player.renderedFrames())).first()
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
        boostChroma(stagingGrid, AMBIENT_CHROMA_BOOST)
        return true
    }

    /** Turns a decoded still into a grid, off Main. */
    private suspend fun gridOf(still: AmbientStill): FloatArray =
        withContext(pixelDispatcher) {
            val pixels = IntArray(still.rect.width() * still.rect.height())
            bitmapAreaToGrid(still.bitmap, still.rect, pixels, stillSourceGrid, renderScratch)
            boostChroma(stillSourceGrid, AMBIENT_CHROMA_BOOST)
            stillSourceGrid
        }

    private suspend fun offerStill(
        still: AmbientStill,
        fadeMs: Long,
    ) = offerStill(gridOf(still), fadeMs)

    private fun offerStill(
        grid: FloatArray,
        fadeMs: Long,
    ) {
        if (hasLastStill && gridDifference(grid, lastStillGrid) < STILL_CHANGE_EPS) return
        grid.copyInto(lastStillGrid)
        hasLastStill = true
        grid.copyInto(stillGrid)
        stillFadeMs = fadeMs
        stillPending = true
        wake.trySend(Unit)
    }

    /**
     * Shows each target: stills crossfade from what is on screen, live frames walk the filter.
     * [progress] receives the crossfade position, read only in draw.
     */
    suspend fun runPresenter(
        emit: (AmbientFrameState) -> Unit,
        progress: (Float) -> Unit,
    ) {
        var last = SystemClock.elapsedRealtime()
        while (currentCoroutineContext().isActive) {
            when {
                stillPending -> {
                    stillPending = false
                    crossfade(emit, progress)
                    last = SystemClock.elapsedRealtime()
                }

                filterPending -> {
                    filterPending = false
                    if (!shown) {
                        targetGrid.copyInto(currentGrid)
                        shown = true
                        fadeMode = false
                        show(render(currentGrid, null), 1f, emit, progress)
                        delay(SMOOTH_TICK_MS)
                    } else {
                        if (fadeMode) displayedGrid(currentGrid)
                        fadeMode = false
                        filtering = true
                    }
                    last = SystemClock.elapsedRealtime()
                }

                filtering -> {
                    val now = SystemClock.elapsedRealtime()
                    val dt = (now - last).coerceIn(1L, 500L)
                    last = now
                    val alpha = 1f - exp(-dt.toFloat() / SMOOTH_TAU_MS)
                    filtering = step(currentGrid, targetGrid, alpha)
                    if (filtering || restyled) {
                        show(render(currentGrid, null), 1f, emit, progress)
                        delay(SMOOTH_TICK_MS)
                    }
                }

                restyled && shown -> {
                    displayedGrid(scratchGrid)
                    show(render(scratchGrid, null), 1f, emit, progress)
                }

                else -> {
                    restyled = false
                    wake.receive()
                    last = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    private suspend fun crossfade(
        emit: (AmbientFrameState) -> Unit,
        progress: (Float) -> Unit,
    ) {
        if (!shown) {
            stillGrid.copyInto(toGrid)
            shown = true
            fadeMode = true
            fadeProgress = 1f
            show(render(toGrid, null), 1f, emit, progress)
            return
        }
        displayedGrid(fromGrid)
        stillGrid.copyInto(toGrid)
        fadeMode = true
        filtering = false
        fadeProgress = 0f
        show(render(toGrid, fromGrid), 0f, emit, progress)
        val start = SystemClock.elapsedRealtime()
        val duration = stillFadeMs.coerceAtLeast(FADE_TICK_MS).toFloat()
        while (fadeProgress < 1f && currentCoroutineContext().isActive) {
            delay(FADE_TICK_MS)
            if (stillPending || filterPending) return
            if (restyled) emit(render(toGrid, fromGrid))
            fadeProgress = smoothStep((SystemClock.elapsedRealtime() - start) / duration)
            progress(fadeProgress)
        }
    }

    /** Hands over a rendered state and the fade position it belongs to in one step. */
    private fun show(
        state: AmbientFrameState,
        at: Float,
        emit: (AmbientFrameState) -> Unit,
        progress: (Float) -> Unit,
    ) {
        progress(at)
        emit(state)
    }

    /** What is on screen now, as a grid. */
    private fun displayedGrid(out: FloatArray) {
        if (fadeMode) lerpGrid(fromGrid, toGrid, fadeProgress, out) else currentGrid.copyInto(out)
    }

    /** The light for the current grid alone, for callers that only need one image. */
    internal suspend fun publish(): AmbientFrameState = render(currentGrid, null)

    /**
     * The encode and the page light are per-pixel work, so they run on [pixelDispatcher]. Nothing
     * else touches the grids, [outPixels] or [buffers] while this is awaited: the presenter is one
     * sequential coroutine. [supported] is read on the caller, because the sources write it.
     */
    private suspend fun render(
        grid: FloatArray,
        previous: FloatArray?,
    ): AmbientFrameState {
        restyled = false
        val style = pageStyle()
        val isSupported = supported
        return withContext(pixelDispatcher) {
            val previousFrame = previous?.let { encodeFrame(it) }
            val previousPage = previous?.let { g -> style?.let { pageRenderer.render(g, it) } }
            AmbientFrameState(
                frame = encodeFrame(grid),
                supported = isSupported,
                page = style?.let { pageRenderer.render(grid, it) },
                previousFrame = previousFrame,
                previousPage = previousPage,
            )
        }
    }

    /** Runs on [pixelDispatcher]. */
    private fun encodeFrame(grid: FloatArray): ImageBitmap {
        encodeToPixels(grid, outPixels)
        bufferIndex = (bufferIndex + 1) % OUTPUT_RING
        val bitmap = buffers[bufferIndex]
        bitmap.setPixels(outPixels, 0, DISPLAY_W, 0, 0, DISPLAY_W, DISPLAY_H)
        return bitmap.asImageBitmap()
    }
}

/**
 * Whether storyboard frames light the video instead of the live surface: only when there are
 * some and the surface cannot be read, its readback blocks Main (a TextureView), or the device is
 * warm. Elsewhere the live frame is cheap to read and is the one on screen.
 */
internal fun ambientUsesStoryboard(
    hasStoryboard: Boolean,
    captureSupported: Boolean,
    hot: Boolean,
    blockingSurface: Boolean,
): Boolean = hasStoryboard && (!captureSupported || hot || blockingSurface)

/** A decoded picture and the part of it that is the video. */
internal class AmbientStill(
    val bitmap: Bitmap,
    val rect: Rect,
)

/** One emission per frame the player draws after a seek, a new item or a new surface. */
internal fun Player.renderedFrames(): Flow<Unit> =
    callbackFlow {
        val listener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    trySend(Unit)
                }
            }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }
