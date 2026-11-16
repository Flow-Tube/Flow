package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.CommentImage
import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.RichTextEmoji
import io.github.aedev.flow.data.model.RichTextHighlight
import io.github.aedev.flow.data.model.RichTextSpan
import io.github.aedev.flow.data.model.RichTextTarget
import io.github.aedev.flow.utils.YouTubeLink
import io.github.aedev.flow.utils.youTubeBrowseLink
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private const val HEART_STATE_HEARTED = "TOOLBAR_HEART_STATE_HEARTED"

/**
 * Entity payloads keyed by the key a `commentViewModel` points at.
 *
 * A comment response carries the renderers and their content in two separate places: the thread
 * list names keys, and `frameworkUpdates` holds the payloads those keys resolve to.
 */
internal fun JsonElement?.commentMutations(): Map<String, JsonElement?> =
    objectOrNull()
        ?.get("frameworkUpdates")
        .objectOrNull()
        ?.get("entityBatchUpdate")
        .objectOrNull()
        ?.get("mutations")
        .arrayOrNull()
        .orEmpty()
        .mapNotNull { mutation ->
            val objectValue = mutation.objectOrNull() ?: return@mapNotNull null
            val key = objectValue["entityKey"].stringOrNull() ?: return@mapNotNull null
            key to objectValue["payload"]
        }.toMap()

internal fun JsonObject.toModernComment(
    mutations: Map<String, JsonElement?>,
    repliesRenderer: JsonObject?,
    ownVideoId: String? = null,
    pinnedText: String? = null,
): Comment? {
    val commentKey = this["commentKey"].stringOrNull() ?: return null
    val entity =
        mutations[commentKey]
            .objectOrNull()
            ?.get("commentEntityPayload")
            .objectOrNull()
            ?: return null
    val properties = entity["properties"].objectOrNull() ?: return null
    val author = entity["author"].objectOrNull()
    val toolbar = entity["toolbar"].objectOrNull()
    val id =
        properties["commentId"].stringOrNull()
            ?: this["commentId"].stringOrNull()
            ?: return null
    // Post comments carry the avatar only as this flat author URL; the nested image/sources
    // objects below exist on video comments and are kept as fallbacks.
    val avatar =
        author?.get("avatarThumbnailUrl").stringOrNull()?.takeIf(String::isNotBlank)
            ?: entity["avatar"]
                .objectOrNull()
                ?.get("image")
                .objectOrNull()
                ?.get("sources")
                .bestThumbnailUrl()
            ?: author
                ?.get("avatar")
                .objectOrNull()
                ?.get("image")
                .objectOrNull()
                ?.get("sources")
                .bestThumbnailUrl()
            ?: ""
    val toolbarState =
        properties["toolbarStateKey"]
            .stringOrNull()
            ?.let { mutations[it] }
            .objectOrNull()
            ?.get("engagementToolbarStateEntityPayload")
            .objectOrNull()
    val content = properties["content"]
    val richText = content.toRichText(ownVideoId)
    val resolvedPinnedText =
        pinnedText?.takeIf(String::isNotBlank)
            ?: this["pinnedText"].youtubeText()?.takeIf(String::isNotBlank)
            ?: properties["pinnedText"].youtubeText()?.takeIf(String::isNotBlank)
    val likeCountText = toolbar?.get("likeCountNotliked").youtubeText().orEmpty()
    return Comment(
        id = id,
        author = author?.get("displayName").stringOrNull().orEmpty(),
        authorThumbnail = normalizeImageUrl(avatar),
        text = richText?.text ?: content.youtubeText().orEmpty(),
        likeCount = parseCount(toolbar?.get("likeCountNotliked")),
        publishedTime = properties["publishedTime"].stringOrNull().orEmpty(),
        replyCount = parseCount(toolbar?.get("replyCount")),
        isPinned = resolvedPinnedText != null,
        continuationToken = repliesRenderer?.findReplyContinuation(),
        authorChannelId =
            author?.get("channelId").stringOrNull()
                ?: author
                    ?.get("navigationEndpoint")
                    .objectOrNull()
                    ?.get("browseEndpoint")
                    .objectOrNull()
                    ?.get("browseId")
                    .stringOrNull()
                ?: "",
        richText = richText,
        likeCountText = likeCountText,
        pinnedByText = resolvedPinnedText,
        isHearted = toolbarState?.get("heartState").stringOrNull() == HEART_STATE_HEARTED,
        heartedByText = toolbar?.get("heartActiveTooltip").stringOrNull(),
        isVerified = author?.get("isVerified").booleanOrFalse(),
        isCreator = author?.get("isCreator").booleanOrFalse(),
        isArtist = author?.get("isArtist").booleanOrFalse(),
        image =
            this["commentSurfaceKey"]
                .stringOrNull()
                ?.let { mutations[it] }
                .objectOrNull()
                ?.get("commentSurfaceEntityPayload")
                .objectOrNull()
                ?.get("imageAttachmentViewModel")
                .toCommentImage(),
    )
}

/** The GIF (or picture) YouTube shows under a comment's text; its size comes with it. */
internal fun JsonElement?.toCommentImage(): CommentImage? {
    val attachment = objectOrNull()?.get("commentImageAttachmentViewModel").objectOrNull() ?: return null
    val url =
        attachment["image"]
            .objectOrNull()
            ?.get("sources")
            .bestThumbnailUrl()
            ?.takeIf(String::isNotBlank) ?: return null
    val width = attachment["width"].intOrZero()
    val height = attachment["height"].intOrZero()
    if (width <= 0 || height <= 0) return null
    return CommentImage(url = normalizeImageUrl(url), width = width, height = height)
}

private fun JsonElement?.intOrZero(): Int = (this as? JsonPrimitive)?.intOrNull ?: 0

internal fun JsonObject.toLegacyComment(repliesRenderer: JsonObject?): Comment? {
    val id = this["commentId"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    return Comment(
        id = id,
        author = this["authorText"].youtubeText().orEmpty(),
        authorThumbnail = normalizeImageUrl(this["authorThumbnail"].bestThumbnailUrl().orEmpty()),
        text = this["contentText"].youtubeText().orEmpty(),
        likeCount = parseCount(this["voteCount"]),
        publishedTime = this["publishedTimeText"].youtubeText().orEmpty(),
        replyCount = parseCount(this["replyCount"]),
        isPinned = this["pinnedCommentBadge"] != null,
        continuationToken = repliesRenderer?.findReplyContinuation(),
        authorChannelId =
            this["authorEndpoint"]
                .objectOrNull()
                ?.get("browseEndpoint")
                .objectOrNull()
                ?.get("browseId")
                .stringOrNull()
                ?: "",
        likeCountText = this["voteCount"].youtubeText().orEmpty(),
        isVerified = this["authorCommentBadge"] != null,
        image = this["imageAttachmentViewModel"].toCommentImage(),
    )
}

/**
 * Builds the text and its typed spans from an attributed-text object.
 *
 * [ownVideoId] separates a timestamp — a watch endpoint pointing back at the video the text
 * belongs to — from a link to a different video, which is a navigation rather than a seek.
 */
internal fun JsonElement?.toRichText(ownVideoId: String?): RichText? {
    val value = objectOrNull() ?: return null
    val text = value["content"].stringOrNull() ?: return null
    val spans =
        value["commandRuns"]
            .arrayOrNull()
            .orEmpty()
            .mapNotNull { run -> run.objectOrNull()?.toRichTextSpan(text.length, ownVideoId) }
    val emojis =
        value["attachmentRuns"]
            .arrayOrNull()
            .orEmpty()
            .mapNotNull { run -> run.objectOrNull()?.toRichTextEmoji(text.length) }
    val highlights =
        value["decorationRuns"]
            .arrayOrNull()
            .orEmpty()
            .mapNotNull { run -> run.objectOrNull()?.toRichTextHighlight(text.length) }
    return RichText(text = text, spans = spans, emojis = emojis, highlights = highlights)
}

private fun JsonObject.toRichTextSpan(
    textLength: Int,
    ownVideoId: String?,
): RichTextSpan? {
    val start = this["startIndex"].intOrNull() ?: return null
    val length = this["length"].intOrNull() ?: return null
    if (start < 0 || length <= 0 || start + length > textLength) return null
    val command =
        this["onTap"]
            .objectOrNull()
            ?.get("innertubeCommand")
            .objectOrNull()
            ?: return null
    val target = command.toRichTextTarget(ownVideoId) ?: return null
    return RichTextSpan(start = start, length = length, target = target)
}

private fun JsonObject.toRichTextTarget(ownVideoId: String?): RichTextTarget? {
    this["watchEndpoint"].objectOrNull()?.let { watch ->
        val videoId = watch["videoId"].stringOrNull() ?: return@let
        val startSeconds = watch["startTimeSeconds"].longOrNull()
        return if (videoId == ownVideoId && startSeconds != null) {
            RichTextTarget.Timestamp(startSeconds)
        } else {
            RichTextTarget.Video(videoId = videoId, startSeconds = startSeconds)
        }
    }
    this["browseEndpoint"].objectOrNull()?.let { browse ->
        val browseId = browse["browseId"].stringOrNull() ?: return@let
        val canonical = browse["canonicalBaseUrl"].stringOrNull()
        if (canonical != null && canonical.startsWith("/hashtag/")) {
            return RichTextTarget.Hashtag(canonical.removePrefix("/hashtag/"))
        }
        return when (youTubeBrowseLink(browseId)) {
            null, is YouTubeLink.Channel -> RichTextTarget.Channel(browseId)
            else -> RichTextTarget.Url("https://www.youtube.com/browse/$browseId")
        }
    }
    this["urlEndpoint"].objectOrNull()?.let { url ->
        val raw = url["url"].stringOrNull() ?: return@let
        return RichTextTarget.Url(unwrapRedirectUrl(raw))
    }
    val webUrl =
        this["commandMetadata"]
            .objectOrNull()
            ?.get("webCommandMetadata")
            .objectOrNull()
            ?.get("url")
            .stringOrNull()
            ?: return null
    return RichTextTarget.Url(unwrapRedirectUrl(webUrl))
}

private fun JsonObject.toRichTextHighlight(textLength: Int): RichTextHighlight? {
    val highlight =
        this["textDecorator"]
            .objectOrNull()
            ?.get("highlightTextDecorator")
            .objectOrNull()
            ?: return null
    val start = highlight["startIndex"].intOrNull() ?: return null
    val length = highlight["length"].intOrNull() ?: return null
    if (start < 0 || length <= 0 || start + length > textLength) return null
    return RichTextHighlight(start = start, length = length)
}

private fun JsonObject.toRichTextEmoji(textLength: Int): RichTextEmoji? {
    val start = this["startIndex"].intOrNull() ?: return null
    val length = this["length"].intOrNull() ?: return null
    if (start < 0 || length < 0 || start + length > textLength) return null
    val element = this["element"].objectOrNull() ?: return null
    val image =
        element["type"]
            .objectOrNull()
            ?.get("imageType")
            .objectOrNull()
            ?.get("image")
            .objectOrNull()
            ?: return null
    val url = image["sources"].bestThumbnailUrl() ?: return null
    val label =
        element["properties"]
            .objectOrNull()
            ?.get("accessibilityProperties")
            .objectOrNull()
            ?.get("label")
            .stringOrNull()
            .orEmpty()
    return RichTextEmoji(
        start = start,
        length = length,
        imageUrl = normalizeImageUrl(url),
        label = label,
    )
}

/**
 * YouTube prints outbound links as `youtube.com/redirect?...&q=<encoded target>`. The wrapper is
 * what the user would otherwise see and copy, so the real destination is recovered here.
 */
internal fun unwrapRedirectUrl(url: String): String {
    if (!url.contains("/redirect?")) return url
    val query = url.substringAfter('?', "").split('&')
    val target = query.firstOrNull { it.startsWith("q=") }?.removePrefix("q=") ?: return url
    return runCatching { java.net.URLDecoder.decode(target, "UTF-8") }.getOrDefault(url)
}

internal fun JsonObject.continuationToken(): String? =
    this["continuationEndpoint"]
        .objectOrNull()
        ?.get("continuationCommand")
        .objectOrNull()
        ?.get("token")
        .stringOrNull()
        ?: this["button"]
            .objectOrNull()
            ?.get("buttonRenderer")
            .objectOrNull()
            ?.get("command")
            .objectOrNull()
            ?.get("continuationCommand")
            .objectOrNull()
            ?.get("token")
            .stringOrNull()

internal fun JsonObject.findReplyContinuation(): String? =
    this["contents"]
        .arrayOrNull()
        ?.firstNotNullOfOrNull { content ->
            content
                .objectOrNull()
                ?.get("continuationItemRenderer")
                .objectOrNull()
                ?.continuationToken()
        }

internal fun JsonElement?.bestThumbnailUrl(): String? {
    val thumbnails =
        objectOrNull()?.get("thumbnails").arrayOrNull()
            ?: objectOrNull()?.get("sources").arrayOrNull()
            ?: arrayOrNull()
            ?: return null
    return thumbnails
        .maxByOrNull { thumbnail ->
            val objectValue = thumbnail.objectOrNull()
            val width = (objectValue?.get("width") as? JsonPrimitive)?.intOrNull ?: 0
            val height = (objectValue?.get("height") as? JsonPrimitive)?.intOrNull ?: 0
            width.toLong() * height.toLong().coerceAtLeast(1)
        }?.objectOrNull()
        ?.let { thumbnail ->
            thumbnail["url"].stringOrNull() ?: thumbnail["uri"].stringOrNull()
        }
}

internal fun JsonObject.accessibilityLabel(): String? =
    this["accessibility"].objectOrNull()?.get("label").stringOrNull()
        ?: this["accessibilityData"]
            .objectOrNull()
            ?.get("accessibilityData")
            .objectOrNull()
            ?.get("label")
            .stringOrNull()

internal fun String.countTextFromAccessibilityLabel(): String = Regex("""[\d.,]+\s*[KkMmBb]?""").find(this)?.value?.trim() ?: this

internal fun parseCount(element: JsonElement?): Int {
    val primitive = element as? JsonPrimitive
    primitive?.intOrNull?.let { return it.coerceAtLeast(0) }
    primitive?.longOrNull?.let { return it.coerceIn(0, Int.MAX_VALUE.toLong()).toInt() }
    return parseYouTubeViewCount(element.youtubeText() ?: primitive?.content)
        .coerceIn(0, Int.MAX_VALUE.toLong())
        .toInt()
}

private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

private fun JsonElement?.longOrNull(): Long? = (this as? JsonPrimitive)?.longOrNull

internal fun JsonElement?.booleanOrFalse(): Boolean = (this as? JsonPrimitive)?.content?.toBoolean() == true
