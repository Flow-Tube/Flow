package io.github.aedev.flow.data.images

import io.github.aedev.flow.data.local.ThumbnailQuality

/**
 * A YouTube video's image as an image model: [DrawnSizeImageInterceptor] turns it into the
 * i.ytimg URL for the size it is drawn at, never above [ceiling]. [frames] asks for YouTube's
 * automatic middle frame instead of the uploader's thumbnail.
 */
data class YouTubeThumbnail(
    val videoId: String,
    val ceiling: ThumbnailQuality,
    val portrait: Boolean = false,
    val frames: Boolean = false,
) : DrawnSizeImage {
    /**
     * The URLs to try for an image drawn at [widthPx] by [heightPx], best first; zero means unknown.
     * Each tier is asked for as WebP first, which is smaller for most videos, then as JPEG for the
     * uploads that have no WebP copy.
     */
    override fun urls(
        widthPx: Int,
        heightPx: Int,
        crop: Boolean,
    ): List<String> {
        val tier = ThumbnailTierPolicy.pick(widthPx, heightPx, ceiling, portrait) ?: return emptyList()
        val names = if (frames) ThumbnailTierPolicy.frameNames(tier) else ThumbnailTierPolicy.thumbnailNames(tier)
        return names.flatMap { name ->
            listOf("https://i.ytimg.com/vi_webp/$videoId/$name.webp", "https://i.ytimg.com/vi/$videoId/$name.jpg")
        }
    }
}
