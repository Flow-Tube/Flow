package io.github.aedev.flow.data.images

import io.github.aedev.flow.utils.ThumbnailUrlResolver

/**
 * A post image on Google's image CDN, fetched only as wide as it is drawn. [uncropped] drops the
 * crop YouTube made for the feed and serves the whole image.
 */
data class PostImage(
    val url: String,
    val uncropped: Boolean = false,
) : DrawnSizeImage {
    override fun urls(
        widthPx: Int,
        heightPx: Int,
        crop: Boolean,
    ): List<String> {
        val needed = if (crop) maxOf(widthPx, (heightPx * WIDEST_EXPECTED_RATIO).toInt()) else widthPx
        val width = ImageSizeSteps.roundUp(needed, unknown = FULL_WIDTH)
        return listOfNotNull(ThumbnailUrlResolver.resolveCommunityPostImage(url, width, uncropped).takeIf { it.isNotEmpty() })
    }

    private companion object {
        // A crop fills the box's height too, so a wide image needs more width than the box has.
        const val WIDEST_EXPECTED_RATIO = 16f / 9f
        const val FULL_WIDTH = 2048
    }
}
