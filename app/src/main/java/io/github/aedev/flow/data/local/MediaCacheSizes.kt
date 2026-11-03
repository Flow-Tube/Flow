package io.github.aedev.flow.data.local

/** How much each on-disk cache may hold, as read from the size settings. */
data class MediaCacheLimits(
    /** The video and Shorts cache; 0 means unlimited. */
    val videoBytes: Long,
    /** The song and animated artwork cache; 0 means unlimited. */
    val musicBytes: Long,
    /** The image cache; null leaves the size to Coil. */
    val artworkBytes: Long?,
) {
    companion object {
        val DEFAULT =
            MediaCacheLimits(
                videoBytes = MediaCacheSizes.mediaBytes(MediaCacheSizes.DEFAULT_MEDIA_MB),
                musicBytes = MediaCacheSizes.mediaBytes(MediaCacheSizes.DEFAULT_MEDIA_MB),
                artworkBytes = MediaCacheSizes.artworkBytes(MediaCacheSizes.ARTWORK_AUTOMATIC_MB),
            )
    }
}

/** The size settings of the media and image caches, in megabytes as stored. */
object MediaCacheSizes {
    const val UNLIMITED_MB = 0
    const val ARTWORK_AUTOMATIC_MB = 0
    const val DEFAULT_MEDIA_MB = 500

    val MEDIA_OPTIONS_MB = listOf(100, 200, 500, 1024, 2048, 5120, UNLIMITED_MB)

    /** The range a typed-in size may take (#1660). */
    const val CUSTOM_MIN_MB = 50
    const val CUSTOM_MAX_MB = 50 * 1024
    val ARTWORK_OPTIONS_MB = listOf(ARTWORK_AUTOMATIC_MB, 100, 200, 500, 1024)

    private const val BYTES_PER_MB = 1024L * 1024L

    /** 0 stays 0, which the media caches read as no limit. */
    fun mediaBytes(megabytes: Int): Long = if (megabytes <= UNLIMITED_MB) 0L else megabytes * BYTES_PER_MB

    fun artworkBytes(megabytes: Int): Long? = if (megabytes <= ARTWORK_AUTOMATIC_MB) null else megabytes * BYTES_PER_MB
}
