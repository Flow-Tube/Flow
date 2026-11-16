package io.github.aedev.flow.data.model

import kotlinx.serialization.Serializable

@Serializable
data class DeArrowTitle(
    val title: String = "",
    val votes: Int = 0,
    val locked: Boolean = false,
    val original: Boolean = false,
)

@Serializable
data class DeArrowThumbnail(
    val timestamp: Double? = null,
    val votes: Int = 0,
    val locked: Boolean = false,
    val original: Boolean = false,
)

/** The `/api/branding` answer. Submissions arrive best first, as the server ranks them. */
@Serializable
data class DeArrowContent(
    val titles: List<DeArrowTitle> = emptyList(),
    val thumbnails: List<DeArrowThumbnail> = emptyList(),
    val randomTime: Double? = null,
    val videoDuration: Double? = null,
)

/**
 * The resolved deArrow overrides for a video. Non-null fields should replace the originals.
 * [youTubeFrames] means DeArrow wants a frame here: until its own loads (or when it has no time to
 * place one), YouTube's automatic frames stand in rather than the uploader's thumbnail (#1633).
 */
data class DeArrowResult(
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val youTubeFrames: Boolean = false,
)
