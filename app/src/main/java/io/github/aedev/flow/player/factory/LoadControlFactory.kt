package io.github.aedev.flow.player.factory

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import io.github.aedev.flow.data.local.BufferDurations
import io.github.aedev.flow.data.local.BufferProfile
import io.github.aedev.flow.player.config.PlayerConfig

/**
 * The app's three `DefaultLoadControl` profiles — video, Shorts and music — in one place.
 *
 * `setBufferDurationsMs` asserts `max >= min`, `min >= playback` and `min >= rebuffer`, and it throws
 * while the player is being constructed rather than falling back, which is how #788 became a
 * bootloop. Routing every profile through [build] enforces that invariant once instead of trusting
 * three independently-maintained sets of numbers to keep satisfying it.
 */
@UnstableApi
object LoadControlFactory {
    private const val TAG = "LoadControlFactory"
    private const val BYTES_PER_MB = 1024L * 1024L
    private const val SKIP_TAIL_START_MS = 250
    private const val SKIP_TAIL_SPARE_MS = 3_000
    private const val MIN_NEXT_VIDEO_PRELOAD_MS = 2_000L
    private const val NEXT_VIDEO_PRELOAD_MARGIN_MS = 500L
    private val StockMaxBufferMs = BufferProfile.entries.maxOf { it.maxBuffer }

    /**
     * Main video player: stored user preferences, re-capped against what this device's heap can
     * afford. The caps are applied at read time because a combination saved as valid on one device
     * can exceed the budget on another.
     */
    fun forVideo(
        context: Context,
        minMs: Int,
        maxMs: Int,
        playbackMs: Int,
        rebufferMs: Int,
    ): DefaultLoadControl {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClassMb = activityManager?.memoryClass ?: 256
        val budget = videoBudget(memoryClassMb, isLowRamDevice = activityManager?.isLowRamDevice == true, requestedMaxMs = maxMs)

        val buffers =
            BufferDurations.sanitize(
                minMs = minMs,
                maxMs = maxMs,
                playbackMs = playbackMs,
                rebufferMs = rebufferMs,
                maxSafeMinMs = budget.maxSafeMinMs,
                maxSafeMaxMs = budget.maxSafeMaxMs,
            )

        Log.d(
            TAG,
            "Buffer config: min=${buffers.minMs}ms, max=${buffers.maxMs}ms, playback=${buffers.playbackMs}ms, " +
                "rebuffer=${buffers.rebufferMs}ms, target=${budget.targetBufferBytes / 1024 / 1024}MB, " +
                "back=${budget.backBufferMs}ms, heap=${memoryClassMb}MB",
        )

        return build(
            minMs = buffers.minMs,
            maxMs = buffers.maxMs,
            playbackMs = buffers.playbackMs,
            rebufferMs = buffers.rebufferMs,
            backBufferMs = budget.backBufferMs,
            retainBackBufferFromKeyframe = true,
            targetBufferBytes = budget.targetBufferBytes,
        )
    }

    /**
     * How much of the next queued video to buffer while the current one plays: enough to start it
     * without waiting, i.e. its start gate plus a margin, and never less than YouTube's 2 s. None while
     * Android's Data Saver is on.
     */
    fun nextVideoPreloadUs(
        bufferForPlaybackMs: Int,
        savingData: Boolean,
    ): Long = if (savingData) 0L else maxOf(MIN_NEXT_VIDEO_PRELOAD_MS, bufferForPlaybackMs + NEXT_VIDEO_PRELOAD_MARGIN_MS) * 1_000L

    /** What the main video player may buffer on this device, before the user's durations are applied. */
    internal data class VideoBudget(
        val maxSafeMinMs: Int,
        val maxSafeMaxMs: Int,
        val targetBufferBytes: Int,
        val backBufferMs: Int,
    )

    /**
     * The stock profiles keep the byte targets they always had, so default playback holds no more
     * memory than before. Only a Custom max longer than every profile raises the byte target to a
     * share of the heap, which is what lets a 120 s or 180 s choice take effect (#1438). A device
     * Android flags as low-RAM never gets more than 18 s.
     */
    internal fun videoBudget(
        memoryClassMb: Int,
        isLowRamDevice: Boolean,
        requestedMaxMs: Int,
    ): VideoBudget {
        val isLowMemoryDevice = isLowRamDevice || memoryClassMb < PlayerConfig.SMALL_HEAP_MB
        val isConstrainedHeap = isLowMemoryDevice || memoryClassMb <= 384
        val tierBytes =
            when {
                isLowMemoryDevice -> PlayerConfig.LOW_MEMORY_MAIN_TARGET_BUFFER_BYTES
                isConstrainedHeap -> PlayerConfig.MID_MEMORY_MAIN_TARGET_BUFFER_BYTES
                else -> PlayerConfig.MAIN_TARGET_BUFFER_BYTES
            }
        val wantsLongBuffer = !isLowRamDevice && requestedMaxMs > StockMaxBufferMs
        val longBufferBytes =
            (memoryClassMb.toLong() * BYTES_PER_MB / PlayerConfig.LONG_BUFFER_HEAP_DIVISOR)
                .coerceAtMost(PlayerConfig.MAX_LONG_BUFFER_BYTES.toLong())
                .toInt()
        return VideoBudget(
            maxSafeMinMs =
                if (isLowMemoryDevice) PlayerConfig.LOW_MEMORY_MAX_SAFE_MAIN_MIN_BUFFER_MS else PlayerConfig.MAX_SAFE_MAIN_MIN_BUFFER_MS,
            maxSafeMaxMs = if (isLowRamDevice) PlayerConfig.LOW_MEMORY_MAX_SAFE_MAIN_BUFFER_MS else PlayerConfig.MAX_SAFE_MAIN_BUFFER_MS,
            targetBufferBytes = if (wantsLongBuffer) maxOf(tierBytes, longBufferBytes) else tierBytes,
            backBufferMs = if (isConstrainedHeap) PlayerConfig.LOW_MEMORY_BACK_BUFFER_DURATION_MS else PlayerConfig.BACK_BUFFER_DURATION_MS,
        )
    }

    /**
     * Shorts pool: a deliberately small window so a swipe starts the next clip without a visible
     * wait. These are not user-tunable and stay below [BufferDurations]' product floors on purpose.
     */
    fun forShorts(): DefaultLoadControl =
        build(
            minMs = PlayerConfig.SHORTS_MIN_BUFFER_MS,
            maxMs = PlayerConfig.SHORTS_MAX_BUFFER_MS,
            playbackMs = PlayerConfig.SHORTS_BUFFER_FOR_PLAYBACK_MS,
            rebufferMs = PlayerConfig.SHORTS_BUFFER_FOR_REBUFFER_MS,
            backBufferMs = PlayerConfig.SHORTS_BACK_BUFFER_MS,
            retainBackBufferFromKeyframe = true,
            targetBufferBytes = PlayerConfig.SHORTS_TARGET_BUFFER_BYTES,
        )

    /**
     * Music: a low playback threshold gets the first note out quickly. With [wholeSong] the song loads
     * to its end and what has played is kept, both inside one byte cap, so a seek either way stays in
     * memory (#2167). Without it, the lean 30 s window with no back buffer.
     */
    fun forMusic(wholeSong: Boolean = false): DefaultLoadControl =
        build(
            minMs = PlayerConfig.MUSIC_MIN_BUFFER_MS,
            maxMs = if (wholeSong) PlayerConfig.MUSIC_WHOLE_SONG_BUFFER_MS else PlayerConfig.MUSIC_MAX_BUFFER_MS,
            playbackMs = PlayerConfig.MUSIC_BUFFER_FOR_PLAYBACK_MS,
            rebufferMs = PlayerConfig.MUSIC_BUFFER_FOR_REBUFFER_MS,
            backBufferMs = if (wholeSong) PlayerConfig.MUSIC_BACK_BUFFER_MS else 0,
            retainBackBufferFromKeyframe = wholeSong,
            targetBufferBytes = if (wholeSong) PlayerConfig.MUSIC_TARGET_BUFFER_BYTES else C.LENGTH_UNSET,
        )

    /** A skipped song's tail plays for moments, so it starts on little audio and loads no more than it will play. */
    fun forSkipTail(overlapMs: Long): DefaultLoadControl {
        val keepMs = overlapMs.toInt() + SKIP_TAIL_SPARE_MS
        return build(
            minMs = keepMs,
            maxMs = keepMs,
            playbackMs = SKIP_TAIL_START_MS,
            rebufferMs = PlayerConfig.MUSIC_BUFFER_FOR_REBUFFER_MS,
            backBufferMs = 0,
            retainBackBufferFromKeyframe = false,
            targetBufferBytes = C.LENGTH_UNSET,
        )
    }

    /**
     * @param targetBufferBytes `C.LENGTH_UNSET` lets `DefaultLoadControl` derive the cap from the
     *   duration window, which is what a profile wants when time, not memory, is its budget.
     */
    private fun build(
        minMs: Int,
        maxMs: Int,
        playbackMs: Int,
        rebufferMs: Int,
        backBufferMs: Int,
        retainBackBufferFromKeyframe: Boolean,
        targetBufferBytes: Int,
    ): DefaultLoadControl {
        // Widen rather than throw: a profile that violates the contract should cost buffer, not a crash loop.
        val resolvedMin = maxOf(minMs, playbackMs, rebufferMs)
        val resolvedMax = maxOf(maxMs, resolvedMin)

        return DefaultLoadControl
            .Builder()
            .setAllocator(DefaultAllocator(true, PlayerConfig.ALLOCATOR_BUFFER_SIZE))
            .setBufferDurationsMs(resolvedMin, resolvedMax, playbackMs, rebufferMs)
            .setBackBuffer(backBufferMs, retainBackBufferFromKeyframe)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(targetBufferBytes)
            .build()
    }
}
