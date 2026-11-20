package io.github.aedev.flow.innertube.pages.reel

import io.github.aedev.flow.innertube.pages.ReelCommentsPanel
import io.github.aedev.flow.innertube.pages.reelCommentsPanel
import kotlinx.serialization.json.JsonObject

/** What one WEB `reel_item_watch` response holds for a reel: its overlay and its comment panel. */
data class ReelItem(
    val overlay: ReelOverlay? = null,
    val comments: ReelCommentsPanel? = null,
)

/** Null when the response holds nothing readable, so a failed parse is not remembered as an answer. */
fun JsonObject.toReelItem(): ReelItem? {
    val item = ReelItem(overlay = toReelOverlay(), comments = reelCommentsPanel())
    return item.takeIf { it.overlay != null || it.comments != null }
}
