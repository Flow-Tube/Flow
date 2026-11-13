package io.github.aedev.flow.innertube.models.body

import io.github.aedev.flow.innertube.models.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class NextBody(
    val context: Context,
    val videoId: String?,
    val playlistId: String?,
    val playlistSetVideoId: String?,
    val index: Int?,
    val params: String?,
    val continuation: String?,
    // Without these a video behind a content warning comes back with no secondaryResults at all,
    // so its related list and autoplay are empty.
    val contentCheckOk: Boolean = true,
    val racyCheckOk: Boolean = true,
    val formData: JsonObject? = null,
)
