package io.github.aedev.flow.innertube.pages.renderer

import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.VideoCollaborator
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.arrayOrNull
import io.github.aedev.flow.innertube.pages.objectOrNull
import io.github.aedev.flow.innertube.pages.parseYouTubeViewCount
import io.github.aedev.flow.innertube.pages.reel.parseReelLockup
import io.github.aedev.flow.innertube.pages.stringOrNull
import io.github.aedev.flow.innertube.pages.youtubeText
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import io.github.aedev.flow.utils.premiereDateText
import io.github.aedev.flow.utils.relativedate.RelativeUploadDateParser
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal fun interface FeedItemParser {
    fun parse(
        node: JsonObject,
        owner: FeedItemOwner,
    ): FeedItem?
}

/**
 * Every renderer a channel tab can put in a list, keyed by the field it arrives under.
 *
 * Supporting a new tab is an entry here plus a parser — nothing above this file changes. A renderer
 * with no entry yields null, so an unrecognised item is skipped rather than aborting the tab it sits
 * in.
 */
internal val FEED_ITEM_PARSERS: Map<String, FeedItemParser> =
    mapOf(
        "lockupViewModel" to FeedItemParser { node, owner -> node.toLockupItem(owner) },
        "shortsLockupViewModel" to FeedItemParser { node, owner -> node.toShortItem("shortsLockupViewModel", owner) },
        "reelItemRenderer" to FeedItemParser { node, owner -> node.toShortItem("reelItemRenderer", owner) },
        "videoRenderer" to FeedItemParser { node, owner -> node.toVideoRendererItem(owner) },
        "gridVideoRenderer" to FeedItemParser { node, owner -> node.toVideoRendererItem(owner) },
        "playlistRenderer" to FeedItemParser { node, _ -> node.toPlaylistRendererItem() },
        "gridPlaylistRenderer" to FeedItemParser { node, _ -> node.toPlaylistRendererItem() },
        "gridChannelRenderer" to FeedItemParser { node, _ -> node.toChannelRendererItem() },
        "channelRenderer" to FeedItemParser { node, _ -> node.toChannelRendererItem() },
        "channelVideoPlayerRenderer" to FeedItemParser { node, owner -> node.toTrailerItem(owner) },
        "gridShowRenderer" to FeedItemParser { node, _ -> node.toShowItem() },
        "postRenderer" to FeedItemParser { node, owner -> node.toPostItem(owner) },
        "backstagePostThreadRenderer" to FeedItemParser { node, owner -> node.toPostThreadItem(owner) },
        "showRenderer" to FeedItemParser { node, _ -> node.toShowItem() },
    )

/**
 * A list entry is either the renderer itself or a `richItemRenderer` wrapping it, and either shape
 * can carry the continuation instead of an item.
 */
internal fun JsonElement?.toFeedItem(owner: FeedItemOwner): FeedItem? {
    val node = objectOrNull() ?: return null
    val content =
        node["richItemRenderer"]
            .objectOrNull()
            ?.get("content")
            .objectOrNull()
            ?: node
    return FEED_ITEM_PARSERS.firstNotNullOfOrNull { (key, parser) ->
        content[key].objectOrNull()?.let { parser.parse(it, owner) }
    }
}

internal fun JsonElement?.toFeedItems(owner: FeedItemOwner): List<FeedItem> =
    arrayOrNull()
        .orEmpty()
        .mapNotNull { it.toFeedItem(owner) }
        .distinctBy { it.distinctKey() }

private fun JsonObject.toVideoRendererItem(owner: FeedItemOwner): FeedItem? {
    val videoId = this["videoId"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    val title = this["title"].youtubeText()?.takeIf(String::isNotBlank) ?: return null
    val viewsText = this["viewCountText"].youtubeText()
    val uploadText = this["publishedTimeText"].youtubeText().orEmpty()
    val upcomingStartMs =
        this["upcomingEventData"]
            .objectOrNull()
            ?.get("startTime")
            .stringOrNull()
            ?.toLongOrNull()
            ?.times(1000L)
    val timeStatus = this["thumbnailOverlays"].timeStatusStyle()
    val badges = this["badges"].metadataBadges()
    val (snippet, highlights) = this["detailedMetadataSnippets"].matchedSnippet()
    val isLive = timeStatus == TIME_STATUS_LIVE || this["badges"].hasLiveBadge() || viewsText.mentionsWatching()
    val isUpcoming = upcomingStartMs != null
    val collaborators = videoRendererCollaborators()
    val isShort = timeStatus == TIME_STATUS_SHORTS || opensReelPlayer()
    val video =
        Video(
            id = videoId,
            title = title,
            channelName = bylineName() ?: owner.name,
            channelId =
                this["ownerText"].bylineChannelId()
                    ?: this["shortBylineText"].bylineChannelId()
                    ?: this["longBylineText"].bylineChannelId()
                    ?: owner.id.takeIf(String::isNotBlank)
                    ?: collaborators.firstOrNull()?.channelId.orEmpty(),
            thumbnailUrl = ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, this["thumbnail"].largestImageUrl()),
            duration = parseDurationText(this["lengthText"].youtubeText()) ?: 0,
            // A live row's count is its concurrent viewers, which the card renders in place of the
            // date it has none of. An upcoming row's "1 waiting" is nobody's view count, so it goes.
            viewCount = if (isUpcoming) 0L else parseYouTubeViewCount(viewsText),
            uploadDate = upcomingStartMs?.let(::premiereDateText) ?: uploadText,
            timestamp = upcomingStartMs ?: RelativeUploadDateParser.parse(uploadText, YouTube.locale.hl) ?: 0L,
            channelThumbnailUrl = bylineAvatarUrl() ?: owner.avatarUrl,
            channelThumbnailUrls = collaborators.avatarUrls(),
            collaborators = collaborators,
            isLive = isLive,
            isUpcoming = isUpcoming,
            isShort = isShort,
            isVerifiedChannel = this["ownerBadges"].hasVerifiedBadge(),
            badges = badges,
            snippet = snippet,
            snippetHighlights = highlights,
            channelHandle =
                this["ownerText"].bylineChannelHandle()
                    ?: this["shortBylineText"].bylineChannelHandle()
                    ?: this["longBylineText"].bylineChannelHandle()
                    ?: this["channelThumbnailSupportedRenderers"]
                        .objectOrNull()
                        ?.get("channelThumbnailWithLinkRenderer")
                        .objectOrNull()
                        ?.get("navigationEndpoint")
                        .objectOrNull()
                        ?.browseHandle(),
        )
    return if (isShort) FeedItem.ShortItem(video) else FeedItem.VideoItem(video)
}

/** A Short can arrive as a plain video row, and then tapping it opens the reel player, not the watch page. */
private fun JsonObject.opensReelPlayer(): Boolean {
    val endpoint = this["navigationEndpoint"].objectOrNull() ?: return false
    return endpoint["reelWatchEndpoint"] != null ||
        endpoint["commandMetadata"]
            .objectOrNull()
            ?.get("webCommandMetadata")
            .objectOrNull()
            ?.get("webPageType")
            .stringOrNull() == "WEB_PAGE_TYPE_SHORTS"
}

/**
 * The explore destinations ship live rows with no `badges` array at all, so the overlay is the only
 * signal that survives a locale where "watching" is not the word.
 */
private fun JsonElement?.timeStatusStyle(): String? =
    arrayOrNull()
        .orEmpty()
        .firstNotNullOfOrNull { it.objectOrNull()?.get("thumbnailOverlayTimeStatusRenderer").objectOrNull() }
        ?.get("style")
        .stringOrNull()

private fun String?.mentionsWatching(): Boolean = this?.contains("watching", ignoreCase = true) == true

private fun JsonObject.videoRendererCollaborators(): List<VideoCollaborator> =
    listOf("ownerText", "shortBylineText", "longBylineText", "avatar")
        .firstNotNullOfOrNull { key -> this[key].collaboratorDialog().takeIf { it.isNotEmpty() } }
        .orEmpty()

/** `ownerText` is the watch-page byline; a grid row only ever carries the short or long one. */
private fun JsonObject.bylineName(): String? =
    listOf("ownerText", "shortBylineText", "longBylineText")
        .firstNotNullOfOrNull { this[it].youtubeText()?.takeIf(String::isNotBlank) }

private const val TIME_STATUS_LIVE = "LIVE"
private const val TIME_STATUS_SHORTS = "SHORTS"

private fun JsonObject.toPlaylistRendererItem(): FeedItem? {
    val playlistId = this["playlistId"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    val title = this["title"].youtubeText()?.takeIf(String::isNotBlank) ?: return null
    return FeedItem.PlaylistItem(
        Playlist(
            id = playlistId,
            name = title,
            thumbnailUrl = this["thumbnail"].largestImageUrl().orEmpty(),
            videoCount =
                this["videoCount"].stringOrNull()?.toIntOrNull()
                    ?: this["videoCountText"].youtubeText()?.leadingCount()
                    ?: this["videoCountShortText"].youtubeText()?.leadingCount()
                    ?: 0,
            isLocal = false,
            isCourse = marksCourse(),
        ),
    )
}

/** A channel's Courses tab lists plain playlist rows; only the thumbnail's graduation-cap panel says "course". */
private fun JsonObject.marksCourse(): Boolean =
    this["thumbnailOverlays"].arrayOrNull().orEmpty().any { overlay ->
        overlay
            .objectOrNull()
            ?.get("thumbnailOverlayBottomPanelRenderer")
            .objectOrNull()
            ?.get("icon")
            .objectOrNull()
            ?.get("iconType")
            .stringOrNull() == "COURSE"
    }

private fun JsonObject.toChannelRendererItem(): FeedItem? {
    val channelId = this["channelId"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    val title = this["title"].youtubeText()?.takeIf(String::isNotBlank) ?: return null
    val labels = listOfNotNull(this["subscriberCountText"].youtubeText(), this["videoCountText"].youtubeText())
    val canonicalUrl = this["navigationEndpoint"].objectOrNull()?.canonicalBaseUrl()
    return FeedItem.RelatedChannelItem(
        Channel(
            id = channelId,
            name = title,
            thumbnailUrl = this["thumbnail"].largestImageUrl().orEmpty(),
            subscriberCount = parseYouTubeViewCount(labels.firstOrNull { it.isSubscriberLabel() }),
            description = this["descriptionSnippet"].youtubeText().orEmpty(),
            // Always the /channel/<id> form: an @handle is not a valid browseId, and the channel
            // screen browses whatever this url resolves to.
            url = "https://www.youtube.com/channel/$channelId",
            handle = labels.firstOrNull { it.startsWith("@") } ?: canonicalUrl?.takeIf { it.startsWith("/@") }?.drop(1).orEmpty(),
            videoCount = labels.firstOrNull { it.isVideoCountLabel() }?.leadingCount() ?: 0,
            isVerified = this["ownerBadges"].hasVerifiedBadge(),
        ),
    )
}

/**
 * The two count labels are not reliably in the field their name implies — a live channelRenderer
 * puts "@handle" under subscriberCountText and "16.9M subscribers" under videoCountText — so each
 * label is classified by what it says.
 */
private fun String.isSubscriberLabel(): Boolean = !startsWith("@") && !isVideoCountLabel()

private fun String.isVideoCountLabel(): Boolean = contains("video", ignoreCase = true)

private fun JsonObject.canonicalBaseUrl(): String? =
    this["browseEndpoint"]
        .objectOrNull()
        ?.get("canonicalBaseUrl")
        .stringOrNull()
        ?.takeIf(String::isNotBlank)

/** The `/@handle` a navigation endpoint's browse target carries, or null for a non-handle URL. */
internal fun JsonObject.browseHandle(): String? = canonicalBaseUrl()?.takeIf { it.startsWith("/@") }

private fun JsonElement?.bylineChannelHandle(): String? =
    arrayOrNull()
        ?.firstNotNullOfOrNull { it.objectOrNull()?.bylineChannelHandle() }
        ?: objectOrNull()?.let { node ->
            node["navigationEndpoint"].objectOrNull()?.browseHandle() ?: node["runs"].bylineChannelHandle()
        }

private fun JsonElement?.bylineChannelId(): String? =
    arrayOrNull()
        ?.firstNotNullOfOrNull { it.objectOrNull()?.bylineChannelId() }
        ?: objectOrNull()?.let { node ->
            node["navigationEndpoint"]
                .objectOrNull()
                ?.get("browseEndpoint")
                .objectOrNull()
                ?.get("browseId")
                .stringOrNull()
                ?: node["runs"].bylineChannelId()
        }

/**
 * The avatar a search result already carries. Reading it here is what removes the per-video
 * `next` request the old search path issued to fetch the same image.
 */
private fun JsonObject.bylineAvatarUrl(): String? =
    this["channelThumbnail"].largestImageUrl()
        ?: this["channelThumbnailSupportedRenderers"]
            .objectOrNull()
            ?.get("channelThumbnailWithLinkRenderer")
            .objectOrNull()
            ?.get("thumbnail")
            .largestImageUrl()
        ?: this["avatar"]
            .objectOrNull()
            ?.get("decoratedAvatarViewModel")
            .objectOrNull()
            ?.get("avatar")
            .objectOrNull()
            ?.get("avatarViewModel")
            .objectOrNull()
            ?.get("image")
            .largestImageUrl()

private fun JsonElement?.metadataBadges(): List<String> =
    arrayOrNull()
        .orEmpty()
        .mapNotNull { it.objectOrNull()?.get("metadataBadgeRenderer").objectOrNull() }
        .mapNotNull { it["label"].stringOrNull()?.takeIf(String::isNotBlank) }

private fun JsonElement?.hasVerifiedBadge(): Boolean =
    arrayOrNull()
        .orEmpty()
        .mapNotNull { it.objectOrNull()?.get("metadataBadgeRenderer").objectOrNull() }
        .any { it["style"].stringOrNull()?.contains("VERIFIED") == true }

private fun JsonElement?.hasLiveBadge(): Boolean =
    arrayOrNull()
        .orEmpty()
        .mapNotNull { it.objectOrNull()?.get("metadataBadgeRenderer").objectOrNull() }
        .any { it["style"].stringOrNull() == "BADGE_STYLE_TYPE_LIVE_NOW" }

/** The runs YouTube marked `bold` are the query terms it matched; the UI emphasises those ranges. */
private fun JsonElement?.matchedSnippet(): Pair<String, List<IntRange>> {
    val runs =
        arrayOrNull()
            ?.firstNotNullOfOrNull {
                it
                    .objectOrNull()
                    ?.get("snippetText")
                    .objectOrNull()
                    ?.get("runs")
                    .arrayOrNull()
            }
            ?: return "" to emptyList()
    val text = StringBuilder()
    val highlights = mutableListOf<IntRange>()
    runs.forEach { run ->
        val node = run.objectOrNull() ?: return@forEach
        val part = node["text"].stringOrNull().orEmpty()
        if (part.isEmpty()) return@forEach
        val start = text.length
        text.append(part)
        if (node["bold"].stringOrNull() == "true") highlights += start until text.length
    }
    return text.toString() to highlights
}

private fun JsonObject.toTrailerItem(owner: FeedItemOwner): FeedItem? {
    val videoId = this["videoId"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    val title = this["title"].youtubeText()?.takeIf(String::isNotBlank) ?: return null
    return FeedItem.VideoItem(
        Video(
            id = videoId,
            title = title,
            channelName = owner.name,
            channelId = owner.id,
            thumbnailUrl = ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, null),
            duration = 0,
            viewCount = parseYouTubeViewCount(this["viewCountText"].youtubeText()),
            uploadDate = this["publishedTimeText"].youtubeText().orEmpty(),
            channelThumbnailUrl = owner.avatarUrl,
            description = this["description"].youtubeText().orEmpty(),
        ),
    )
}

/** Delegated so the channel Shorts tab and search Shorts stay on one parser. */
private fun JsonObject.toShortItem(
    key: String,
    owner: FeedItemOwner,
): FeedItem? {
    val item = JsonObject(mapOf(key to this)).parseReelLockup() ?: return null
    return FeedItem.ShortItem(
        Video(
            id = item.id,
            title = item.title,
            channelName = owner.name,
            channelId = owner.id,
            thumbnailUrl = ThumbnailUrlResolver.normalizeVideoThumbnail(item.id, item.thumbnailUrl),
            duration = 0,
            viewCount = item.viewCount,
            uploadDate = "",
            channelThumbnailUrl = owner.avatarUrl,
            isShort = true,
        ),
    )
}

/**
 * A show is a playlist wearing a different renderer. Its browseId is the playlist id behind a "VL"
 * prefix, and its thumbnail hides one level deeper than every other grid item's.
 */
private fun JsonObject.toShowItem(): FeedItem? {
    val browseId =
        this["navigationEndpoint"]
            .objectOrNull()
            ?.get("browseEndpoint")
            .objectOrNull()
            ?.get("browseId")
            .stringOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return null
    val title = this["title"].youtubeText()?.takeIf(String::isNotBlank) ?: return null
    val thumbnail =
        this["thumbnailRenderer"]
            .objectOrNull()
            ?.get("showCustomThumbnailRenderer")
            .objectOrNull()
            ?.get("thumbnail")
            .largestImageUrl()
    return FeedItem.PlaylistItem(
        Playlist(
            id = browseId.removePrefix("VL"),
            name = title,
            thumbnailUrl = thumbnail.orEmpty(),
            videoCount = this["thumbnailOverlays"].episodeCount() ?: 0,
            isLocal = false,
        ),
    )
}

private fun JsonElement?.episodeCount(): Int? {
    var count: Int? = null
    forEachObject { node ->
        if (count != null) return@forEachObject
        count = node["text"].youtubeText()?.leadingCount()
    }
    return count
}

private fun JsonObject.toPostItem(owner: FeedItemOwner): FeedItem? =
    toCommunityPost(owner.name, owner.avatarUrl, owner)?.let(FeedItem::PostItem)

private fun JsonObject.toPostThreadItem(owner: FeedItemOwner): FeedItem? =
    this["post"]
        .objectOrNull()
        ?.get("backstagePostRenderer")
        .objectOrNull()
        ?.toPostItem(owner)

internal fun parseDurationText(text: String?): Int? {
    if (text.isNullOrBlank() || !text.contains(':')) return null
    val parts = text.trim().split(':').map { it.toIntOrNull() ?: return null }
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        else -> null
    }
}

internal fun String.leadingCount(): Int? =
    Regex("""[\d,.]+""")
        .find(this)
        ?.value
        ?.replace(",", "")
        ?.replace(".", "")
        ?.toIntOrNull()

internal fun List<VideoCollaborator>.avatarUrls(): List<String> = map { it.thumbnailUrl }.filter(String::isNotBlank)
