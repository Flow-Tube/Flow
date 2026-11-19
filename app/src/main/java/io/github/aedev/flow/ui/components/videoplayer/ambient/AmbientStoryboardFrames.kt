package io.github.aedev.flow.ui.components.videoplayer.ambient

import android.graphics.Bitmap
import android.graphics.Rect
import io.github.aedev.flow.player.stream.StoryboardLevel

/** Frames are taken this far ahead, so a fade is half done when playback reaches its frame. */
internal const val STORYBOARD_LOOKAHEAD_MS = 2_500L

/** The light changes at most this often, then waits for the storyboard's next frame. */
internal const val STORYBOARD_UPDATE_MS = 4_500L

/** A coarser level would leave the light on one picture for too long. */
internal const val MAX_LEVEL_INTERVAL_MS = 10_000L

private const val MIN_WALL_DELAY_MS = 1_000L

/**
 * The coarsest level that still changes at least every [MAX_LEVEL_INTERVAL_MS]. For most videos
 * that is the single-sheet level, so one request covers the whole video.
 */
internal fun ambientStoryboardLevel(levels: List<StoryboardLevel>): StoryboardLevel? {
    val usable = levels.filter { it.thumbnailWidth >= DISPLAY_W && it.thumbnailHeight >= DISPLAY_H && it.intervalMs > 0L }
    return usable.filter { it.intervalMs <= MAX_LEVEL_INTERVAL_MS }.minByOrNull { it.thumbnailWidth }
        ?: usable.minByOrNull { it.intervalMs }
}

internal fun storyboardTargetMs(
    positionMs: Long,
    durationMs: Long,
): Long {
    val ahead = positionMs.coerceAtLeast(0L) + STORYBOARD_LOOKAHEAD_MS
    return if (durationMs > 0L) minOf(ahead, durationMs) else ahead
}

/**
 * Wall-clock wait until the first storyboard frame that starts at least [STORYBOARD_UPDATE_MS]
 * after [targetMs], so the same frame is never fetched twice.
 */
internal fun storyboardDelayMs(
    targetMs: Long,
    intervalMs: Long,
    speed: Float,
): Long {
    val media =
        if (intervalMs > 0L) {
            ((STORYBOARD_UPDATE_MS + targetMs) / intervalMs + 1) * intervalMs - targetMs
        } else {
            STORYBOARD_UPDATE_MS
        }
    val rate = if (speed > 0f) speed else 1f
    return (media / rate).toLong().coerceAtLeast(MIN_WALL_DELAY_MS)
}

/**
 * Crops frames of one storyboard [level] into the light grid. The sheet in use is held, so a
 * sheet is asked of [load] once however many of its frames are read.
 */
internal class AmbientStoryboardFrames(
    private val level: StoryboardLevel,
    private val load: suspend (String) -> Bitmap?,
) {
    val intervalMs: Long get() = level.intervalMs

    private var sheetUrl: String? = null
    private var sheet: Bitmap? = null
    private var pixels = IntArray(0)

    /** The sheet holding the frame at [positionMs], or null when it cannot be loaded. */
    suspend fun sheetAt(positionMs: Long): StoryboardCrop? {
        val tile = level.tileAt(positionMs) ?: return null
        val bitmap =
            if (tile.sheetUrl == sheetUrl) {
                sheet
            } else {
                load(tile.sheetUrl)?.also {
                    sheetUrl = tile.sheetUrl
                    sheet = it
                }
            } ?: return null
        val scaleX = bitmap.width.toFloat() / tile.sheetWidth
        val scaleY = bitmap.height.toFloat() / tile.sheetHeight
        val rect =
            Rect(
                (tile.left * scaleX).toInt(),
                (tile.top * scaleY).toInt(),
                ((tile.left + tile.width) * scaleX).toInt(),
                ((tile.top + tile.height) * scaleY).toInt(),
            )
        if (!rect.intersect(0, 0, bitmap.width, bitmap.height) || rect.isEmpty) return null
        return StoryboardCrop(bitmap, rect)
    }

    /** Runs on the pixel dispatcher. */
    fun toGrid(
        crop: StoryboardCrop,
        dst: FloatArray,
        scratch: FloatArray,
    ) {
        val needed = crop.rect.width() * crop.rect.height()
        if (pixels.size < needed) pixels = IntArray(needed)
        bitmapAreaToGrid(crop.bitmap, crop.rect, pixels, dst, scratch)
    }
}

internal class StoryboardCrop(
    val bitmap: Bitmap,
    val rect: Rect,
)
