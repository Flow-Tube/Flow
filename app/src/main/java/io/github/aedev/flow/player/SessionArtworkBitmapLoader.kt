package io.github.aedev.flow.player

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.SizeLimitedBitmapLoader
import kotlin.math.roundToInt

private const val PLATFORM_METADATA_BITMAP_MAX_DP = 320f

/**
 * Artwork loader for the app's Media3 sessions, capped at the platform's own metadata bitmap
 * limit resolved through the same [Context] the framework session is created with.
 *
 * Media3 pre-scales artwork to that limit as well, but it resolves the dimen through
 * `Resources.getSystem()`, which can disagree with the app context on ROMs that apply a per-app
 * density. When the two disagree, `MediaSession.setMetadata` rescales the shared bitmap itself,
 * and at least one ROM recycles the source in that path, so the next metadata update crashes with
 * "cannot use a recycled source in createBitmap" (#1017). Matching the framework's own number
 * keeps the framework from ever touching the bitmap. Some of those ROMs (Infinity X, RisingOS on
 * Android 15) read a ROM-only `config_maxBitmapSizePx` instead, so the smaller of the two wins
 * (#1231).
 */
@OptIn(UnstableApi::class)
internal fun sessionArtworkBitmapLoader(context: Context): BitmapLoader {
    val limit = platformMetadataBitmapLimitPx(context)
    // The decoder only subsamples by powers of two, so let it land anywhere below twice the limit
    // and scale precisely from there instead of dropping to half the resolution.
    val decodeLimit = limit * 2 - 1
    val decoder =
        DataSourceBitmapLoader
            .Builder(context)
            .setMaximumOutputDimension(decodeLimit)
            .build()
    val loader = CoilArtworkBitmapLoader(context.applicationContext, decodeLimit, decoder)
    // Media3 wraps this loader in its own SizeLimitedBitmapLoader(makeShared = true); sharing here too
    // would just copy the pixels into ashmem twice.
    return SizeLimitedBitmapLoader(MediaStoreArtworkBitmapLoader(context.applicationContext, loader, limit), limit, false)
}

@SuppressLint("DiscouragedApi")
private fun platformMetadataBitmapLimitPx(context: Context): Int {
    val resources = context.resources
    val dimenId = resources.getIdentifier("config_mediaMetadataBitmapMaxSize", "dimen", "android")
    val limit =
        if (dimenId != 0) {
            resources.getDimensionPixelSize(dimenId)
        } else {
            TypedValue
                .applyDimension(TypedValue.COMPLEX_UNIT_DIP, PLATFORM_METADATA_BITMAP_MAX_DP, resources.displayMetrics)
                .roundToInt()
        }
    val romLimitId = resources.getIdentifier("config_maxBitmapSizePx", "integer", "android")
    val romLimit = if (romLimitId != 0) resources.getInteger(romLimitId) else 0
    return if (romLimit > 0) minOf(limit, romLimit) else limit
}
