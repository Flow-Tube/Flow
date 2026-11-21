package io.github.aedev.flow.data.images

import io.github.aedev.flow.utils.ThumbnailUrlResolver

/** A track's cover in a list row, fetched at the size the row draws it. */
data class MusicArtwork(
    val videoId: String,
    val thumbnailUrl: String,
) : DrawnSizeImage {
    override fun urls(
        widthPx: Int,
        heightPx: Int,
        crop: Boolean,
    ): List<String> {
        val size = ImageSizeSteps.roundUp(maxOf(widthPx, heightPx), unknown = LIST_SIZE)
        return listOfNotNull(ThumbnailUrlResolver.resolveMusicThumbnail(videoId, thumbnailUrl, size).takeIf { it.isNotEmpty() })
    }

    companion object {
        const val LIST_SIZE = 256
    }
}
