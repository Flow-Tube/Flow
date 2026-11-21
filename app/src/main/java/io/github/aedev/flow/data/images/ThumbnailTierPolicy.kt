package io.github.aedev.flow.data.images

import io.github.aedev.flow.data.local.ThumbnailQuality

/**
 * The i.ytimg sizes a card can draw, smallest first, with the usable 16:9 area of each.
 * hqdefault and sddefault are 4:3 files with bars; a cropped card never shows the bars.
 */
enum class YouTubeThumbnailTier(
    val thumbnailName: String,
    val frameName: String,
    val width: Int,
    val height: Int,
) {
    MQ("mqdefault", "mq2", 320, 180),
    HQ("hqdefault", "hq2", 480, 270),
    SD("sddefault", "sd2", 640, 360),
    HQ720("hq720", "maxres2", 1280, 720),
}

/**
 * Picks the smallest YouTube thumbnail tier that still covers what a card draws, capped by the
 * viewer's thumbnail quality setting.
 */
object ThumbnailTierPolicy {
    private const val MIN_COVERAGE = 0.9f
    private const val WIDE_RATIO = 16f / 9f

    /** The largest tier [quality] allows, or null when thumbnails are off. */
    fun ceiling(
        quality: ThumbnailQuality,
        portrait: Boolean,
    ): YouTubeThumbnailTier? =
        when (quality) {
            ThumbnailQuality.HIGH -> YouTubeThumbnailTier.HQ720
            ThumbnailQuality.MEDIUM -> YouTubeThumbnailTier.SD
            ThumbnailQuality.LOW -> if (portrait) YouTubeThumbnailTier.HQ else YouTubeThumbnailTier.MQ
            ThumbnailQuality.OFF -> null
        }

    /**
     * The smallest tier at least 90% as wide as the drawn [widthPx], or as tall as [heightPx] for a
     * [portrait] crop, which shows only the middle of a 16:9 frame. A size of zero or less on both
     * sides means the drawn size is unknown, and the ceiling is used.
     */
    fun pick(
        widthPx: Int,
        heightPx: Int,
        ceiling: ThumbnailQuality,
        portrait: Boolean,
    ): YouTubeThumbnailTier? {
        val cap = ceiling(ceiling, portrait) ?: return null
        val needed = neededPixels(widthPx, heightPx, portrait) ?: return cap
        return YouTubeThumbnailTier.entries.firstOrNull { tier ->
            tier <= cap && tier >= floor(portrait) && usablePixels(tier, portrait) >= needed * MIN_COVERAGE
        } ?: cap
    }

    /** Thumbnail file names to try for [picked], best first, ending on hqdefault, which every video has. */
    fun thumbnailNames(picked: YouTubeThumbnailTier): List<String> =
        listOf(picked, YouTubeThumbnailTier.HQ).distinct().map { it.thumbnailName }

    /**
     * YouTube's automatic middle frame from [picked] down to hq2, then hqdefault: live and upcoming
     * videos have no frames yet.
     */
    fun frameNames(picked: YouTubeThumbnailTier): List<String> {
        val frames =
            (
                listOf(picked) +
                    YouTubeThumbnailTier.entries.filter { it in YouTubeThumbnailTier.HQ..picked }.reversed() +
                    YouTubeThumbnailTier.HQ
            ).distinct()
                .map { it.frameName }
        return frames + YouTubeThumbnailTier.HQ.thumbnailName
    }

    // A 9:16 crop keeps about a third of a 16:9 frame's width, so a portrait card never drops below hqdefault.
    private fun floor(portrait: Boolean): YouTubeThumbnailTier = if (portrait) YouTubeThumbnailTier.HQ else YouTubeThumbnailTier.MQ

    private fun usablePixels(
        tier: YouTubeThumbnailTier,
        portrait: Boolean,
    ): Int = if (portrait) tier.height else tier.width

    private fun neededPixels(
        widthPx: Int,
        heightPx: Int,
        portrait: Boolean,
    ): Float? {
        val width = widthPx.takeIf { it > 0 }?.toFloat()
        val height = heightPx.takeIf { it > 0 }?.toFloat()
        return if (portrait) {
            height ?: width?.times(WIDE_RATIO)
        } else {
            width ?: height?.times(WIDE_RATIO)
        }
    }
}
