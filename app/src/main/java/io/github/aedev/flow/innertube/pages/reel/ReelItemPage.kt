package io.github.aedev.flow.innertube.pages.reel

import io.github.aedev.flow.innertube.pages.ReelCommentsPanel
import io.github.aedev.flow.innertube.pages.VideoDescriptionPage
import io.github.aedev.flow.innertube.pages.reelCommentsPanel
import io.github.aedev.flow.innertube.pages.toReelDescriptionPage
import kotlinx.serialization.json.JsonObject

/** What one WEB `reel_item_watch` response holds for a reel: its overlay, comment panel and description. */
data class ReelItem(
    val overlay: ReelOverlay? = null,
    val comments: ReelCommentsPanel? = null,
    val description: VideoDescriptionPage? = null,
)

/** Null when the response holds nothing readable, so a failed parse is not remembered as an answer. */
fun JsonObject.toReelItem(videoId: String): ReelItem? {
    val item =
        ReelItem(
            overlay = toReelOverlay(),
            comments = reelCommentsPanel(),
            description = toReelDescriptionPage(videoId),
        )
    return item.takeIf { it.overlay != null || it.comments != null || it.description != null }
}
