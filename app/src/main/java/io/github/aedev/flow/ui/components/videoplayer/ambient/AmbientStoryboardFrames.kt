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
    private var sheetPixels = IntArray(0)
    private var paddedSheet: Bitmap? = null
    private val padding = IntArray(4)

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
        val bitmap = crop.bitmap
        if (bitmap !== paddedSheet) {
            val size = bitmap.width * bitmap.height
            if (sheetPixels.size < size) sheetPixels = IntArray(size)
            bitmap.getPixels(sheetPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            storyboardPadding(sheetPixels, bitmap.width, bitmap.height, crop.rect.width(), crop.rect.height(), padding)
            paddedSheet = bitmap
        }
        val rect =
            Rect(
                crop.rect.left + padding[2],
                crop.rect.top + padding[0],
                crop.rect.right - padding[3],
                crop.rect.bottom - padding[1],
            )
        val needed = rect.width() * rect.height()
        if (pixels.size < needed) pixels = IntArray(needed)
        bitmapAreaToGrid(bitmap, rect, pixels, dst, scratch)
    }
}

/** A row or column is padding when it stays under this share of the tile's middle in every tile. */
private const val PADDING_SHARE = 0.4f

/** Padding is looked for only this close to a tile's edge, as a share of its size. */
private const val PADDING_REACH = 5

/**
 * The dark rows and columns YouTube pads every storyboard frame with, as top, bottom, left and
 * right counts written into [out]. They sit on the frame's outer rows, exactly the ones the status
 * bar strip and the page glow read, so left in they turn the light grey. A line counts as padding
 * only when it is dark in every tile of the sheet, so a dark scene is never cut.
 */
internal fun storyboardPadding(
    sheet: IntArray,
    sheetWidth: Int,
    sheetHeight: Int,
    tileWidth: Int,
    tileHeight: Int,
    out: IntArray,
) {
    out.fill(0)
    if (tileWidth <= 0 || tileHeight <= 0) return
    val tilesX = sheetWidth / tileWidth
    val tilesY = sheetHeight / tileHeight
    if (tilesX <= 0 || tilesY <= 0) return

    fun level(
        along: Int,
        horizontal: Boolean,
    ): Float {
        var brightest = 0f
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                var sum = 0
                val count = if (horizontal) tileWidth else tileHeight
                for (i in 0 until count) {
                    val x = tx * tileWidth + if (horizontal) i else along
                    val y = ty * tileHeight + if (horizontal) along else i
                    val c = sheet[y * sheetWidth + x]
                    sum += maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
                }
                brightest = maxOf(brightest, sum.toFloat() / count)
            }
        }
        return brightest
    }

    fun count(
        size: Int,
        horizontal: Boolean,
        fromEnd: Boolean,
    ): Int {
        val limit = PADDING_SHARE * level(size / 2, horizontal)
        var n = 0
        while (n < size / PADDING_REACH && level(if (fromEnd) size - 1 - n else n, horizontal) < limit) n++
        return n
    }
    out[0] = count(tileHeight, horizontal = true, fromEnd = false)
    out[1] = count(tileHeight, horizontal = true, fromEnd = true)
    out[2] = count(tileWidth, horizontal = false, fromEnd = false)
    out[3] = count(tileWidth, horizontal = false, fromEnd = true)
}

internal class StoryboardCrop(
    val bitmap: Bitmap,
    val rect: Rect,
)
