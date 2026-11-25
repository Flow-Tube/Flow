package io.github.aedev.flow.data.music.artwork

/**
 * How sharp an album loop may get, capped on its width: the square loops and the 3:4 cuts for tall
 * screens share it as their short side. Apple's loops run from about 400 px up to 2160 px wide, and
 * the largest ones cost over 20 Mbps for a picture behind the controls.
 */
enum class ArtworkLoopQuality(
    internal val maxWidth: Int,
) {
    /** The smallest loop that covers the artwork as shown, up to [HIGH]. */
    AUTO(HIGH_WIDTH),
    LOW(LOW_WIDTH),
    MEDIUM(MEDIUM_WIDTH),
    HIGH(HIGH_WIDTH),
    ;

    /** Only Auto sizes the loop to the space it plays in; the others take the best loop under their cap. */
    internal val followsViewport: Boolean get() = this == AUTO
}

internal fun resolveArtworkLoopQuality(stored: String?): ArtworkLoopQuality =
    stored?.let { value -> ArtworkLoopQuality.entries.firstOrNull { it.name == value } } ?: ArtworkLoopQuality.AUTO

private const val LOW_WIDTH = 500
private const val MEDIUM_WIDTH = 800
private const val HIGH_WIDTH = 1080
