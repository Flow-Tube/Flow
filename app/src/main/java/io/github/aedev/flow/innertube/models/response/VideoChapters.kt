package io.github.aedev.flow.innertube.models.response

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One chapter of a video: where it starts, and the frame the chapter list shows for it. */
data class VideoChapter(
    val title: String,
    val startTimeSeconds: Int,
    val thumbnailUrl: String? = null,
)

/**
 * A video's chapters, read from the watch response.
 *
 * They arrive in an engagement panel rather than beside the description: the uploader's chapters in
 * [CHAPTERS_PANEL], or, for a video without any, YouTube's generated ones in [AUTO_CHAPTERS_PANEL].
 * Each item carries its start in `onTap.watchEndpoint.startTimeSeconds`, a whole number for uploader
 * chapters and a decimal such as 75.2 for generated ones. The "0:37" next to it is a display string in
 * the viewer's own locale and is never parsed.
 *
 * Parsed defensively and by field name: chapters are decoration, so a shape change should drop them
 * rather than fail the watch page.
 */
object VideoChaptersParser {
    private const val CHAPTERS_PANEL = "engagement-panel-macro-markers-description-chapters"
    private const val AUTO_CHAPTERS_PANEL = "engagement-panel-macro-markers-auto-chapters"

    fun parse(watchNext: JsonElement?): List<VideoChapter> {
        val panels =
            runCatching {
                watchNext
                    ?.jsonObjectOrNull()
                    ?.get("engagementPanels")
                    ?.jsonArrayOrNull()
            }.getOrNull() ?: return emptyList()
        return listOf(CHAPTERS_PANEL, AUTO_CHAPTERS_PANEL)
            .asSequence()
            .map { panelId ->
                runCatching { findChapterItems(panels, panelId) }
                    .getOrNull()
                    .orEmpty()
                    .mapNotNull { it.toChapter() }
                    .sortedBy { it.startTimeSeconds }
            }.firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    private fun findChapterItems(
        panels: List<JsonElement>,
        panelId: String,
    ): List<JsonElement>? =
        panels
            .firstNotNullOfOrNull { panel ->
                val renderer =
                    panel
                        .jsonObjectOrNull()
                        ?.get("engagementPanelSectionListRenderer")
                        ?.jsonObjectOrNull()
                        ?.takeIf { it["panelIdentifier"]?.stringOrNull() == panelId }
                renderer
                    ?.get("content")
                    ?.jsonObjectOrNull()
                    ?.get("macroMarkersListRenderer")
                    ?.jsonObjectOrNull()
                    ?.get("contents")
                    ?.jsonArrayOrNull()
            }

    private fun JsonElement.toChapter(): VideoChapter? {
        val item =
            jsonObjectOrNull()
                ?.get("macroMarkersListItemRenderer")
                ?.jsonObjectOrNull()
                ?: return null
        val title =
            item["title"]
                ?.jsonObjectOrNull()
                ?.textOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return null
        val start =
            item["onTap"]
                ?.jsonObjectOrNull()
                ?.get("watchEndpoint")
                ?.jsonObjectOrNull()
                ?.get("startTimeSeconds")
                ?.secondsOrNull()
                ?: return null
        return VideoChapter(
            title = title,
            startTimeSeconds = start,
            thumbnailUrl =
                item["thumbnail"]
                    ?.jsonObjectOrNull()
                    ?.get("thumbnails")
                    ?.jsonArrayOrNull()
                    ?.lastOrNull()
                    ?.jsonObjectOrNull()
                    ?.get("url")
                    ?.stringOrNull(),
        )
    }

    private fun Map<String, JsonElement>.textOrNull(): String? =
        this["simpleText"]?.stringOrNull()
            ?: this["runs"]
                ?.jsonArrayOrNull()
                ?.mapNotNull { it.jsonObjectOrNull()?.get("text")?.stringOrNull() }
                ?.joinToString("")
                ?.takeIf { it.isNotBlank() }

    private fun JsonElement.jsonObjectOrNull(): Map<String, JsonElement>? = runCatching { jsonObject }.getOrNull()

    private fun JsonElement.jsonArrayOrNull(): List<JsonElement>? = runCatching { jsonArray }.getOrNull()

    private fun JsonElement.stringOrNull(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()

    private fun JsonElement.secondsOrNull(): Int? =
        (runCatching { jsonPrimitive.doubleOrNull }.getOrNull() ?: stringOrNull()?.toDoubleOrNull())
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.toInt()
}
