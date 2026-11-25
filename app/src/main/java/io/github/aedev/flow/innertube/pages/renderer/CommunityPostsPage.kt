package io.github.aedev.flow.innertube.pages.renderer

import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.innertube.pages.VideoCommentSort
import io.github.aedev.flow.innertube.pages.accessibilityLabel
import io.github.aedev.flow.innertube.pages.arrayOrNull
import io.github.aedev.flow.innertube.pages.bestThumbnailUrl
import io.github.aedev.flow.innertube.pages.commentMutations
import io.github.aedev.flow.innertube.pages.continuationToken
import io.github.aedev.flow.innertube.pages.countTextFromAccessibilityLabel
import io.github.aedev.flow.innertube.pages.findReplyContinuation
import io.github.aedev.flow.innertube.pages.normalizeImageUrl
import io.github.aedev.flow.innertube.pages.objectOrNull
import io.github.aedev.flow.innertube.pages.stringOrNull
import io.github.aedev.flow.innertube.pages.toLegacyComment
import io.github.aedev.flow.innertube.pages.toModernComment
import io.github.aedev.flow.innertube.pages.toSortOptions
import io.github.aedev.flow.innertube.pages.youtubeText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class CommunityPost(
    val id: String,
    val authorName: String,
    /** The author's channel, from the post's author endpoint. */
    val authorChannelId: String? = null,
    val authorAvatarUrl: String,
    val text: String,
    val attachment: PostAttachment?,
    val likeCountText: String,
    val commentCountText: String,
    val commentEndpointParams: String?,
    val publishedTimeText: String,
)

data class CommunityPostsPage(
    val posts: List<CommunityPost>,
    val continuation: String?,
)

data class CommunityCommentsPage(
    val comments: List<Comment>,
    val continuation: String?,
    val commentCountText: String? = null,
    val sortOptions: List<VideoCommentSort> = emptyList(),
)

internal fun JsonElement.toCommunityPostsPage(
    fallbackAuthorName: String,
    fallbackAuthorAvatarUrl: String,
    owner: FeedItemOwner = FeedItemOwner(name = fallbackAuthorName, avatarUrl = fallbackAuthorAvatarUrl),
): CommunityPostsPage {
    val posts = mutableListOf<CommunityPost>()

    fun collect(element: JsonElement?) {
        when (element) {
            is JsonArray -> {
                element.forEach(::collect)
            }

            is JsonObject -> {
                val thread = element["backstagePostThreadRenderer"].objectOrNull()
                val renderer =
                    thread
                        ?.get("post")
                        .objectOrNull()
                        ?.get("backstagePostRenderer")
                        .objectOrNull()
                        ?: element["postRenderer"].objectOrNull()

                if (renderer != null) {
                    renderer
                        .toCommunityPost(fallbackAuthorName, fallbackAuthorAvatarUrl, owner)
                        ?.let(posts::add)
                    return
                }
                element.values.forEach(::collect)
            }

            else -> {
                Unit
            }
        }
    }

    collect(this)
    return CommunityPostsPage(
        posts = posts.distinctBy(CommunityPost::id),
        continuation = postListContinuation(),
    )
}

/**
 * The next page's token, looked for only where the posts are. The header's About panel carries a
 * `continuationItemRenderer` of its own, and on the last page it is the only one in the response.
 */
private fun JsonElement.postListContinuation(): String? {
    val root = objectOrNull() ?: return null
    var token: String? = null
    POST_LIST_ROOTS.forEach { key ->
        root[key].forEachObject { node ->
            if (token == null) token = node["continuationItemRenderer"].objectOrNull()?.continuationToken()
        }
    }
    return token
}

private val POST_LIST_ROOTS = listOf("contents", "onResponseReceivedEndpoints", "onResponseReceivedActions", "continuationContents")

internal fun JsonElement.toCommunityCommentsPage(): CommunityCommentsPage {
    val root = objectOrNull()
    val mutations = commentMutations()

    val comments = mutableListOf<Comment>()
    var continuation: String? = null
    var sortOptions = emptyList<VideoCommentSort>()

    fun collect(element: JsonElement?) {
        when (element) {
            is JsonArray -> {
                element.forEach(::collect)
            }

            is JsonObject -> {
                val thread = element["commentThreadRenderer"].objectOrNull()
                if (thread != null) {
                    val rawViewModel = thread["commentViewModel"].objectOrNull()
                    val viewModel =
                        rawViewModel
                            ?.get("commentViewModel")
                            .objectOrNull()
                            ?: rawViewModel
                    val repliesRenderer =
                        thread["replies"]
                            .objectOrNull()
                            ?.get("commentRepliesRenderer")
                            .objectOrNull()
                    val comment =
                        viewModel?.toModernComment(mutations, repliesRenderer)
                            ?: thread["comment"]
                                .objectOrNull()
                                ?.get("commentRenderer")
                                .objectOrNull()
                                ?.toLegacyComment(repliesRenderer)
                    comment?.let(comments::add)
                    return
                }

                element["commentViewModel"]
                    .objectOrNull()
                    ?.toModernComment(mutations, null)
                    ?.let {
                        comments.add(it)
                        return
                    }

                element["commentRenderer"]
                    .objectOrNull()
                    ?.toLegacyComment(null)
                    ?.let {
                        comments.add(it)
                        return
                    }

                if (sortOptions.isEmpty()) {
                    sortOptions =
                        element["commentsHeaderRenderer"]
                            .objectOrNull()
                            ?.get("sortMenu")
                            .objectOrNull()
                            ?.toSortOptions()
                            .orEmpty()
                }
                if (continuation == null) {
                    continuation =
                        element["continuationItemRenderer"]
                            .objectOrNull()
                            ?.continuationToken()
                }
                element.values.forEach(::collect)
            }

            else -> {
                Unit
            }
        }
    }

    collect(this)
    return CommunityCommentsPage(
        comments = comments.distinctBy(Comment::id),
        continuation = continuation,
        commentCountText = findCommentCountText(root),
        sortOptions = sortOptions,
    )
}

internal fun JsonObject.toCommunityPost(
    fallbackAuthorName: String,
    fallbackAuthorAvatarUrl: String,
    owner: FeedItemOwner,
): CommunityPost? {
    val id = this["postId"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    val replyButton =
        this["actionButtons"]
            .objectOrNull()
            ?.get("commentActionButtonsRenderer")
            .objectOrNull()
            ?.get("replyButton")
            .objectOrNull()
            ?.get("buttonRenderer")
            .objectOrNull()
    val navigationEndpoint = replyButton?.get("navigationEndpoint").objectOrNull()
    val authorAvatar =
        this["authorThumbnail"].bestThumbnailUrl()
            ?: fallbackAuthorAvatarUrl
    return CommunityPost(
        id = id,
        authorName =
            this["authorText"]
                .youtubeText()
                ?.takeIf(String::isNotBlank)
                ?: fallbackAuthorName,
        authorChannelId = authorChannelId() ?: owner.id.takeIf(String::isNotBlank),
        authorAvatarUrl = normalizeImageUrl(authorAvatar),
        text = this["contentText"].youtubeText().orEmpty(),
        attachment = this["backstageAttachment"].toPostAttachment(owner),
        likeCountText = this["voteCount"].youtubeText().orEmpty(),
        commentCountText =
            replyButton?.get("text").youtubeText()
                ?: replyButton?.accessibilityLabel()?.countTextFromAccessibilityLabel()
                ?: "",
        commentEndpointParams =
            navigationEndpoint
                ?.get("browseEndpoint")
                .objectOrNull()
                ?.get("params")
                .stringOrNull()
                ?: navigationEndpoint
                    ?.get("signInEndpoint")
                    .objectOrNull()
                    ?.get("nextEndpoint")
                    .objectOrNull()
                    ?.get("browseEndpoint")
                    .objectOrNull()
                    ?.get("params")
                    .stringOrNull(),
        publishedTimeText = this["publishedTimeText"].youtubeText().orEmpty(),
    )
}

private fun JsonObject.authorChannelId(): String? =
    (
        this["authorEndpoint"]
            ?: this["authorText"]
                .objectOrNull()
                ?.get("runs")
                .arrayOrNull()
                ?.firstOrNull()
                .objectOrNull()
                ?.get("navigationEndpoint")
    ).objectOrNull()
        ?.get("browseEndpoint")
        .objectOrNull()
        ?.get("browseId")
        .stringOrNull()
        ?.takeIf(String::isNotBlank)

private fun findCommentCountText(root: JsonObject?): String? {
    val panels = root?.get("engagementPanels").arrayOrNull().orEmpty()
    panels.forEach { panel ->
        val section =
            panel
                .objectOrNull()
                ?.get("engagementPanelSectionListRenderer")
                .objectOrNull()
                ?: return@forEach
        val identifier = section["panelIdentifier"].stringOrNull()
        if (identifier == "comment-item-section" || identifier == "engagement-panel-comments-section") {
            section["header"]
                .objectOrNull()
                ?.get("engagementPanelTitleHeaderRenderer")
                .objectOrNull()
                ?.get("contextualInfo")
                .youtubeText()
                ?.let { return it }
        }
    }
    return null
}
