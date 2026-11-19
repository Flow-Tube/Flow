package io.github.aedev.flow.ui.components.videoplayer.ambient

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlin.math.abs

private const val THUMBNAIL_DECODE_PX = 128

/** A software bitmap the light can read pixels from; Coil's disk cache is shared with the rest of the app. */
internal suspend fun loadAmbientBitmap(
    context: Context,
    url: String,
    thumbnail: Boolean,
): Bitmap? {
    val request =
        ImageRequest
            .Builder(context)
            .data(url)
            .allowHardware(false)
            .apply { if (thumbnail) size(THUMBNAIL_DECODE_PX) else size(Size.ORIGINAL) }
            .build()
    val result = SingletonImageLoader.get(context).execute(request)
    return (result as? SuccessResult)?.image?.toBitmap()
}

/**
 * The picture inside a YouTube thumbnail. The 4:3 sizes hold a 16:9 picture between black bands,
 * which would otherwise darken the top and bottom of the light.
 */
internal fun thumbnailContentRect(
    width: Int,
    height: Int,
): Rect {
    if (height <= 0 || abs(width.toFloat() / height - 4f / 3f) > 0.05f) return Rect(0, 0, width, height)
    val picture = width * 9 / 16
    val top = (height - picture) / 2
    return Rect(0, top, width, top + picture)
}

/**
 * Averages [rect] of [bitmap] into the light grid in linear light, then blurs it like a sampled
 * frame. [pixels] is scratch at least as large as [rect]; [scratch] is grid sized.
 */
internal fun bitmapAreaToGrid(
    bitmap: Bitmap,
    rect: Rect,
    pixels: IntArray,
    dst: FloatArray,
    scratch: FloatArray,
) {
    val w = rect.width()
    val h = rect.height()
    bitmap.getPixels(pixels, 0, w, rect.left, rect.top, w, h)
    areaToGrid(pixels, w, h, dst)
    boxBlurLinear(dst, scratch, DISPLAY_W, DISPLAY_H, BLUR_RADIUS_PX, BLUR_PASSES)
}
