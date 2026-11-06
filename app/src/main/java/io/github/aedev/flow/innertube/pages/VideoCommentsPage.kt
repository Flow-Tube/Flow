package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.Comment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val COMMENT_SECTION_IDENTIFIER = "comment-item-section"
private const val PINNED_RENDERING_PRIORITY = "RENDERING_PRIORITY_PINNED_COMMENT"
private const val COMMENTS_PANEL_IDENTIFIER = "engagement-panel-comments-section"

/** One entry of the comment section's sort menu, with the continuation that loads that order. */
data class VideoCommentSort(
    val title: String,
    val token: String,
    val selected: Boolean,
)

data class VideoCommentsPage(
    val comments: List<Comment>,
    val continuation: String?,
    val sortOptions: List<VideoCommentSort> = emptyList(),
    val totalText: String? = null,
    val totalCount: Long? = null,
)

/** The sort menu and count a `next` response carries in its comments panel. */
data class VideoCommentsPanelHeader(
    val sortOptions: List<VideoCommentSort>,
    val totalText: String?,
    val totalCount: Long?,
)

/**
 * The continuation that loads a video's comment section, from a `next` response for that video.
 *
 * The section arrives as a placeholder the web player would only expand once the user scrolls to
 * it, which is why the first page costs a second request.
 */
internal fun JsonElement.videoCommentsContinuation(): String? {
    val contents =
        objectOrNull()
            ?.get("contents")
            .objectOrNull()
            ?.get("twoColumnWatchNextResults")
            .objectOrNull()
            ?.get("results")
            .objectOrNull()
            ?.get("results")
            .objectOrNull()
            ?.get("contents")
            .arrayOrNull()
            .orEmpty()
    return contents.firstNotNullOfOrNull { entry ->
        val section = entry.objectOrNull()?.get("itemSectionRenderer").objectOrNull() ?: return@firstNotNullOfOrNull null
        if (section["sectionIdentifier"].stringOrNull() != COMMENT_SECTION_IDENTIFIER) return@firstNotNullOfOrNull null
        section["contents"]
            .arrayOrNull()
            ?.firstNotNullOfOrNull { item ->
                item
                    .objectOrNull()
                    ?.get("continuationItemRenderer")
                    .objectOrNull()
                    ?.continuationToken()
            }
    }
}

/**
 * The comments panel's header, for a first page whose own header leaves out the menu and the count,
 * which YouTube does for some visitors.
 */
internal fun JsonElement.videoCommentsPanelHeader(): VideoCommentsPanelHeader? {
    val header =
        objectOrNull()
            ?.get("engagementPanels")
            .arrayOrNull()
            .orEmpty()
            .firstNotNullOfOrNull { entry ->
                entry
                    .objectOrNull()
                    ?.get("engagementPanelSectionListRenderer")
                    .objectOrNull()
                    ?.takeIf { it["panelIdentifier"].stringOrNull() == COMMENTS_PANEL_IDENTIFIER }
            }?.get("header")
            .objectOrNull()
            ?.get("engagementPanelTitleHeaderRenderer")
            .objectOrNull()
            ?: return null
    val totalText = header["contextualInfo"].youtubeText()
    return VideoCommentsPanelHeader(
        sortOptions = header["menu"].objectOrNull()?.toSortOptions().orEmpty(),
        totalText = totalText,
        totalCount = totalText?.let(::parseYouTubeViewCount)?.takeIf { it > 0L },
    )
}

/**
 * A page of a video's comments.
 *
 * [ownVideoId] is what lets a `1:57` in a comment be recognised as a seek into this video rather
 * than a link to another one.
 */
internal fun JsonElement.toVideoCommentsPage(ownVideoId: String?): VideoCommentsPage {
    val mutations = commentMutations()
    val items = continuationItems()
    val header =
        items.firstNotNullOfOrNull { item ->
            item.objectOrNull()?.get("commentsHeaderRenderer").objectOrNull()
        }
    val comments = mutableListOf<Comment>()
    var continuation: String? = null

    items.forEach { item ->
        val entry = item.objectOrNull() ?: return@forEach
        val thread = entry["commentThreadRenderer"].objectOrNull()
        if (thread != null) {
            thread.toVideoComment(mutations, ownVideoId)?.let(comments::add)
            return@forEach
        }
        if (continuation == null) {
            continuation = entry["continuationItemRenderer"].objectOrNull()?.continuationToken()
        }
    }

    return VideoCommentsPage(
        comments = comments.distinctBy(Comment::id),
        continuation = continuation,
        sortOptions =
            header
                ?.get("sortMenu")
                .objectOrNull()
                ?.toSortOptions()
                .orEmpty(),
        totalText = header?.get("commentsCount").youtubeText(),
        totalCount =
            header
                ?.get("countText")
                .youtubeText()
                ?.let(::parseYouTubeViewCount)
                ?.takeIf { it > 0L },
    )
}

/** A page of one comment's replies: the same threads one level down, with no header of their own. */
internal fun JsonElement.toCommentRepliesPage(ownVideoId: String?): VideoCommentsPage {
    val mutations = commentMutations()
    val comments = mutableListOf<Comment>()
    var continuation: String? = null

    continuationItems().forEach { item ->
        val entry = item.objectOrNull() ?: return@forEach
        val view = entry["commentViewModel"].objectOrNull()
        val model = view?.get("commentViewModel").objectOrNull() ?: view
        if (model != null) {
            model.toModernComment(mutations, null, ownVideoId)?.let(comments::add)
            return@forEach
        }
        entry["commentThreadRenderer"]
            .objectOrNull()
            ?.toVideoComment(mutations, ownVideoId)
            ?.let(comments::add)
        if (continuation == null) {
            continuation = entry["continuationItemRenderer"].objectOrNull()?.continuationToken()
        }
    }

    return VideoCommentsPage(
        comments = comments.distinctBy(Comment::id),
        continuation = continuation,
    )
}

private fun JsonObject.toVideoComment(
    mutations: Map<String, JsonElement?>,
    ownVideoId: String?,
): Comment? {
    val rawViewModel = this["commentViewModel"].objectOrNull()
    val model =
        rawViewModel
            ?.get("commentViewModel")
            .objectOrNull()
            ?: rawViewModel
    val repliesRenderer =
        this["replies"]
            .objectOrNull()
            ?.get("commentRepliesRenderer")
            .objectOrNull()
    val comment =
        model?.toModernComment(
            mutations = mutations,
            repliesRenderer = repliesRenderer,
            ownVideoId = ownVideoId,
            pinnedText = model["pinnedText"].youtubeText(),
        )
            ?: this["comment"]
                .objectOrNull()
                ?.get("commentRenderer")
                .objectOrNull()
                ?.toLegacyComment(repliesRenderer)
    return if (this["renderingPriority"].stringOrNull() == PINNED_RENDERING_PRIORITY) {
        comment?.copy(isPinned = true)
    } else {
        comment
    }
}

/** The entries of a menu object that holds a `sortFilterSubMenuRenderer`. */
private fun JsonObject.toSortOptions(): List<VideoCommentSort> =
    this["sortFilterSubMenuRenderer"]
        .objectOrNull()
        ?.get("subMenuItems")
        .arrayOrNull()
        .orEmpty()
        .mapNotNull { item ->
            val entry = item.objectOrNull() ?: return@mapNotNull null
            val token =
                entry["serviceEndpoint"]
                    .objectOrNull()
                    ?.get("continuationCommand")
                    .objectOrNull()
                    ?.get("token")
                    .stringOrNull()
                    ?: return@mapNotNull null
            VideoCommentSort(
                title = entry["title"].youtubeText().orEmpty(),
                token = token,
                selected = entry["selected"].booleanOrFalse(),
            )
        }

/**
 * The items of every reload and append command in the response.
 *
 * A first page splits its header and its threads across two commands; a later page carries one.
 */
private fun JsonElement.continuationItems(): List<JsonElement> =
    objectOrNull()
        ?.get("onResponseReceivedEndpoints")
        .arrayOrNull()
        .orEmpty()
        .flatMap { endpoint ->
            val entry = endpoint.objectOrNull() ?: return@flatMap emptyList<JsonElement>()
            val command =
                entry["reloadContinuationItemsCommand"].objectOrNull()
                    ?: entry["appendContinuationItemsAction"].objectOrNull()
                    ?: return@flatMap emptyList<JsonElement>()
            command["continuationItems"].arrayOrNull() ?: JsonArray(emptyList())
        }
