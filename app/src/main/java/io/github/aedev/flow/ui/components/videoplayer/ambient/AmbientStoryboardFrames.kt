package io.github.aedev.flow.ui.components.videoplayer.ambient

import android.graphics.Bitmap
import android.graphics.Rect
import io.github.aedev.flow.player.stream.StoryboardLevel

/** The light moves to a new storyboard frame at most this often. */
internal const val MIN_STORYBOARD_STEP_MS = 2_000L

private const val MIN_FADE_MS = 1_000L
private const val MAX_FADE_MS = 2_500L
private const val MIN_WALL_DELAY_MS = 200L

/** A wake-up a little early must still pick the frame it woke for. */
private const val SCHEDULE_SLACK_MS = 150L

/**
 * Storyboard JPEGs are small and heavily compressed, which costs some colour; measured against
 * the live frame, the smallest tiles lose up to 18% of their chroma and 80 px ones up to 6%. This
 * is on top of [AMBIENT_CHROMA_BOOST], which every source gets.
 */
private const val SMALL_TILE_CHROMA_BOOST = 1.15f
private const val LARGE_TILE_CHROMA_BOOST = 1.06f
private const val SMALL_TILE_WIDTH = 64

/**
 * The level whose frames are closest together, so the light follows the picture; between levels
 * as frequent, the smallest tiles, which fit the most frames on one sheet.
 */
internal fun ambientStoryboardLevel(levels: List<StoryboardLevel>): StoryboardLevel? =
    levels
        .filter { it.thumbnailWidth >= DISPLAY_W && it.thumbnailHeight >= DISPLAY_H && it.intervalMs > 0L }
        .minWithOrNull(compareBy<StoryboardLevel> { it.intervalMs }.thenBy { it.thumbnailWidth })

/** How far apart the frames the light uses are: whole storyboard frames, at least [MIN_STORYBOARD_STEP_MS]. */
internal fun storyboardStepMs(intervalMs: Long): Long {
    if (intervalMs <= 0L) return MIN_STORYBOARD_STEP_MS
    return ((MIN_STORYBOARD_STEP_MS + intervalMs - 1) / intervalMs) * intervalMs
}

/** Half a step, so a fade never runs into the next one. */
internal fun storyboardFadeMs(stepMs: Long): Long = (stepMs / 2).coerceIn(MIN_FADE_MS, MAX_FADE_MS)

/**
 * The frame to fade to at [positionMs]: the one on screen half a fade from now, so the middle of
 * the fade lands where the frame starts.
 */
internal fun storyboardTargetMs(
    positionMs: Long,
    fadeMs: Long,
    durationMs: Long,
): Long {
    val target = positionMs.coerceAtLeast(0L) + fadeMs / 2 + SCHEDULE_SLACK_MS
    return if (durationMs > 0L) minOf(target, durationMs) else target
}

/** Wall-clock wait until the next fade should start: half a fade before the next step begins. */
internal fun storyboardDelayMs(
    positionMs: Long,
    stepMs: Long,
    fadeMs: Long,
    speed: Float,
): Long {
    val position = positionMs.coerceAtLeast(0L)
    val nextStep = ((position + fadeMs / 2 + SCHEDULE_SLACK_MS) / stepMs + 1) * stepMs
    val rate = if (speed > 0f) speed else 1f
    return ((nextStep - fadeMs / 2 - position) / rate).toLong().coerceAtLeast(MIN_WALL_DELAY_MS)
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
        val compression = if (level.thumbnailWidth < SMALL_TILE_WIDTH) SMALL_TILE_CHROMA_BOOST else LARGE_TILE_CHROMA_BOOST
        boostChroma(dst, AMBIENT_CHROMA_BOOST * compression)
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
