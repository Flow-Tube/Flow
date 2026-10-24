package io.github.aedev.flow.innertube.pages.renderer

import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.VideoCollaborator
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.arrayOrNull
import io.github.aedev.flow.innertube.pages.objectOrNull
import io.github.aedev.flow.innertube.pages.parseYouTubeViewCount
import io.github.aedev.flow.innertube.pages.stringOrNull
import io.github.aedev.flow.innertube.pages.youtubeText
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.serialization.json.JsonObject

private const val LOCKUP_CONTENT_TYPE_COURSE = "LOCKUP_CONTENT_TYPE_COURSE"

// A channel's Home tab lists the reels it collaborated on as plain video lockups of this type.
private const val LOCKUP_CONTENT_TYPE_SHORT = "LOCKUP_CONTENT_TYPE_SHORT"

internal fun JsonObject.toLockupItem(owner: FeedItemOwner): FeedItem? {
    val contentId = this["contentId"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    val metadata = this["metadata"].objectOrNull()?.get("lockupMetadataViewModel").objectOrNull()
    val title = metadata?.get("title").youtubeText()?.takeIf(String::isNotBlank) ?: return null
    // Outside a channel's own tabs (a playlist, for one) each lockup names its channel as the
    // first metadata part, linked to it; that part is the byline, not an upload date.
    val collaborators = metadata?.get("image").collaboratorDialog()
    val byline = metadata.lockupByline() ?: collaborators.bylineOwner(metadata.metadataParts())
    val itemOwner = byline?.let { owner.copy(id = it.id, name = it.name, avatarUrl = it.avatarUrl) } ?: owner
    val parts = metadata.metadataParts().filterNot { it == byline?.name }
    val badges = lockupBadges()
    val membersOnly = metadata.membersOnlyBadge()

    val contentType = this["contentType"].stringOrNull()
    return when (contentType) {
        "LOCKUP_CONTENT_TYPE_PLAYLIST",
        "LOCKUP_CONTENT_TYPE_PODCAST",
        "LOCKUP_CONTENT_TYPE_ALBUM",
        LOCKUP_CONTENT_TYPE_COURSE,
        -> {
            FeedItem.PlaylistItem(
                Playlist(
                    id = contentId,
                    name = title,
                    thumbnailUrl = lockupThumbnailUrl().orEmpty(),
                    videoCount = badges.firstNotNullOfOrNull { it.leadingCount() } ?: 0,
                    isLocal = false,
                    isCourse = contentType == LOCKUP_CONTENT_TYPE_COURSE,
                ),
            )
        }

        "LOCKUP_CONTENT_TYPE_CHANNEL" -> {
            FeedItem.RelatedChannelItem(
                Channel(
                    id = contentId,
                    name = title,
                    thumbnailUrl = lockupThumbnailUrl().orEmpty(),
                    subscriberCount = parts.firstOrNull { it.mentionsSubscribers() }?.let(::parseYouTubeViewCount) ?: 0L,
                ),
            )
        }

        "LOCKUP_CONTENT_TYPE_SHORTS" -> {
            FeedItem.ShortItem(
                lockupVideo(contentId, title, parts, badges, itemOwner, collaborators).copy(isShort = true, duration = 0),
            )
        }

        else -> {
            FeedItem.VideoItem(
                lockupVideo(contentId, title, parts, badges, itemOwner, collaborators).copy(
                    membersOnlyText = membersOnly,
                    isShort = contentType == LOCKUP_CONTENT_TYPE_SHORT,
                ),
            )
        }
    }
}

private fun JsonObject.lockupVideo(
    videoId: String,
    title: String,
    parts: List<String>,
    badges: List<String>,
    owner: FeedItemOwner,
    collaborators: List<VideoCollaborator>,
): Video {
    val (viewsText, uploadText, uploadTimestamp) = lockupDateAndViews(parts, YouTube.locale.hl)
    val duration = badges.firstNotNullOfOrNull(::parseDurationText) ?: 0
    val isLive = viewsText?.contains("watching", ignoreCase = true) == true || badges.any { it.marksLive() }
    // A stream that has not started carries a badge that is neither a duration nor LIVE ("Upcoming"),
    // and its date row is the scheduled start, already rendered by the server.
    val isUpcoming = !isLive && duration == 0 && badges.any { it.marksUpcoming() }
    return Video(
        id = videoId,
        title = title,
        channelName = owner.name,
        channelId = owner.id,
        thumbnailUrl = ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, lockupThumbnailUrl()),
        duration = duration,
        viewCount = if (isUpcoming) 0L else parseYouTubeViewCount(viewsText),
        uploadDate = uploadText,
        timestamp = if (isUpcoming) 0L else uploadTimestamp ?: 0L,
        channelThumbnailUrl = owner.avatarUrl,
        channelThumbnailUrls = collaborators.avatarUrls(),
        collaborators = collaborators,
        isLive = isLive,
        isUpcoming = isUpcoming,
    )
}

/** A collaboration's byline links to no channel, so it is found by the lead collaborator's name instead. */
private fun List<VideoCollaborator>.bylineOwner(parts: List<String>): FeedItemOwner? {
    val lead = firstOrNull() ?: return null
    val name = parts.firstOrNull { lead.name in it } ?: return null
    return FeedItemOwner(id = lead.channelId, name = name, avatarUrl = lead.thumbnailUrl)
}

/** Members-only videos carry the badge instead of a view count, which is why their views row is bare. */
private fun JsonObject?.membersOnlyBadge(): String? {
    var label: String? = null
    this?.forEachObject { node ->
        if (label != null) return@forEachObject
        val badge = node["badgeViewModel"].objectOrNull() ?: return@forEachObject
        if (badge["badgeStyle"].stringOrNull() != "BADGE_MEMBERS_ONLY") return@forEachObject
        label = badge["badgeText"].youtubeText()?.takeIf(String::isNotBlank)
    }
    return label
}

/** The first metadata part that links to a channel, as the owner it names. */
private fun JsonObject?.lockupByline(): FeedItemOwner? =
    this
        ?.get("metadata")
        .objectOrNull()
        ?.get("contentMetadataViewModel")
        .objectOrNull()
        ?.get("metadataRows")
        .arrayOrNull()
        .orEmpty()
        .asSequence()
        .flatMap { row ->
            row
                .objectOrNull()
                ?.get("metadataParts")
                .arrayOrNull()
                .orEmpty()
        }.mapNotNull { part ->
            val text = part.objectOrNull()?.get("text").objectOrNull() ?: return@mapNotNull null
            val name = text.youtubeText()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val channelId =
                text["commandRuns"]
                    .arrayOrNull()
                    ?.firstOrNull()
                    .objectOrNull()
                    ?.get("onTap")
                    .objectOrNull()
                    ?.get("innertubeCommand")
                    .objectOrNull()
                    ?.get("browseEndpoint")
                    .objectOrNull()
                    ?.get("browseId")
                    .stringOrNull()
                    ?.takeIf { it.startsWith("UC") } ?: return@mapNotNull null
            FeedItemOwner(id = channelId, name = name)
        }.firstOrNull()

private fun JsonObject?.metadataParts(): List<String> =
    this
        ?.get("metadata")
        .objectOrNull()
        ?.get("contentMetadataViewModel")
        .objectOrNull()
        ?.get("metadataRows")
        .arrayOrNull()
        .orEmpty()
        .flatMap { row ->
            row
                .objectOrNull()
                ?.get("metadataParts")
                .arrayOrNull()
                .orEmpty()
                .mapNotNull {
                    it
                        .objectOrNull()
                        ?.get("text")
                        .youtubeText()
                        ?.takeIf(String::isNotBlank)
                }
        }

private fun JsonObject.thumbnailViewModel(): JsonObject? =
    this["contentImage"]
        .objectOrNull()
        ?.let { image ->
            image["thumbnailViewModel"].objectOrNull()
                ?: image["collectionThumbnailViewModel"]
                    .objectOrNull()
                    ?.get("primaryThumbnail")
                    .objectOrNull()
                    ?.get("thumbnailViewModel")
                    .objectOrNull()
        }

private fun JsonObject.lockupThumbnailUrl(): String? = thumbnailViewModel()?.get("image").largestImageUrl()

/** Duration, "12 videos" and the LIVE marker all arrive as thumbnail badges. */
private fun JsonObject.lockupBadges(): List<String> =
    thumbnailViewModel()
        ?.get("overlays")
        .arrayOrNull()
        .orEmpty()
        .flatMap { overlay ->
            val badges =
                overlay
                    .objectOrNull()
                    ?.get("thumbnailOverlayBadgeViewModel")
                    .objectOrNull()
                    ?.get("thumbnailBadges")
                    .arrayOrNull()
                    ?: overlay
                        .objectOrNull()
                        ?.get("thumbnailBottomOverlayViewModel")
                        .objectOrNull()
                        ?.get("badges")
                        .arrayOrNull()
                    ?: return@flatMap emptyList()
            badges.mapNotNull { badge ->
                val model = badge.objectOrNull()?.get("thumbnailBadgeViewModel").objectOrNull()
                model?.get("text").youtubeText()?.takeIf(String::isNotBlank)
                    ?: model?.get("badgeStyle").stringOrNull()
            }
        }

private fun String.mentionsSubscribers(): Boolean = contains("subscriber", ignoreCase = true)

private fun String.marksLive(): Boolean = equals("LIVE", ignoreCase = true) || contains("LIVE_NOW", ignoreCase = true)

private fun String.marksUpcoming(): Boolean = parseDurationText(this) == null && !marksLive()
