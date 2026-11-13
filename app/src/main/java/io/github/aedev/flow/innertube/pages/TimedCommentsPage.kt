package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.Comment
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val WELCOME_SYNC_ID = "timed_comments_welcome_sync_id"

/**
 * YouTube's Timed list, in playback order, each comment carrying the time YouTube shows it at. The
 * order and the times come from the list's sync entity; the comments are ordinary comment entities,
 * found by their id. The rows themselves are server-drawn elements Flow doesn't read.
 */
internal fun JsonElement.toTimedComments(ownVideoId: String?): List<Comment> {
    val mutations = commentMutations()
    val keyById =
        mutations.entries
            .mapNotNull { (key, payload) ->
                payload
                    .objectOrNull()
                    ?.get("commentEntityPayload")
                    .objectOrNull()
                    ?.get("properties")
                    .objectOrNull()
                    ?.get("commentId")
                    .stringOrNull()
                    ?.let { id -> id to key }
            }.toMap()
    val entries =
        mutations.values
            .firstNotNullOfOrNull { payload -> payload.objectOrNull()?.get("timedMarkersListSyncEntity").objectOrNull() }
            ?.get("timedListData")
            .objectOrNull()
            ?.get("sections")
            .arrayOrNull()
            .orEmpty()
            .flatMap { section ->
                section
                    .objectOrNull()
                    ?.get("timedSyncDataList")
                    .arrayOrNull()
                    .orEmpty()
            }
    return entries
        .mapNotNull { entry ->
            val item = entry.objectOrNull() ?: return@mapNotNull null
            val id = item["syncItemId"].stringOrNull()?.takeIf { it != WELCOME_SYNC_ID } ?: return@mapNotNull null
            val atMs = item["videoTimeMs"].stringOrNull()?.toLongOrNull() ?: return@mapNotNull null
            val key = keyById[id] ?: return@mapNotNull null
            buildJsonObject { put("commentKey", key) }
                .toModernComment(mutations, repliesRenderer = null, ownVideoId = ownVideoId)
                ?.copy(timedAtMs = atMs)
        }.distinctBy(Comment::id)
}
