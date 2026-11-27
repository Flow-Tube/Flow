package io.github.aedev.flow.innertube.pages.renderer

import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.CollectionLabels
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

private const val DEFAULT_METADATA_DELIMITER = " • "

// A playlist page's browse id is the playlist id behind this prefix.
private const val PLAYLIST_PAGE_BROWSE_PREFIX = "VL"

private const val LOCKUP_CONTENT_TYPE_COURSE = "LOCKUP_CONTENT_TYPE_COURSE"

internal fun JsonObject.toLockupItem(owner: FeedItemOwner): FeedItem? {
    val contentId = this["contentId"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    val metadata = this["metadata"].objectOrNull()?.get("lockupMetadataViewModel").objectOrNull()
    val title = metadata?.get("title").youtubeText()?.takeIf(String::isNotBlank) ?: return null
    // Outside a channel's own tabs (a playlist, for one) each lockup names its channel as the
    // first metadata part, linked to it; that part is the byline, not an upload date.
    val collaborators = metadata?.get("image").collaboratorDialog()
    val byline = metadata.lockupByline() ?: collaborators.bylineOwner(metadata.metadataParts())
    val itemOwner = byline?.let { owner.copy(id = it.id, name = it.name, avatarUrl = it.avatarUrl, handle = it.handle) } ?: owner
    val parts = metadata.metadataParts().filterNot { it == byline?.name }
    val badges = lockupBadges()
    val signals = ContentSignals.of(this)

    val contentType = this["contentType"].stringOrNull()
    return when (contentType) {
        "LOCKUP_CONTENT_TYPE_PLAYLIST",
        "LOCKUP_CONTENT_TYPE_STATION",
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
                lockupVideo(contentId, title, parts, badges, signals, itemOwner, collaborators).copy(isShort = true, duration = 0),
            )
        }

        else -> {
            FeedItem.VideoItem(
                lockupVideo(contentId, title, parts, badges, signals, itemOwner, collaborators).copy(
                    membersOnlyText = signals.membersOnlyLabel,
                    isShort = signals.isShort,
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
    signals: ContentSignals,
    owner: FeedItemOwner,
    collaborators: List<VideoCollaborator>,
): Video {
    val hl = YouTube.locale.hl
    val (viewsText, uploadText, uploadTimestamp) = lockupDateAndViews(parts, hl)
    val duration = badges.firstNotNullOfOrNull(::parseDurationText) ?: 0
    val isLive =
        signals.isLive(hl) {
            viewsText?.contains("watching", ignoreCase = true) == true || badges.any { it.marksLive() }
        }
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
        channelHandle = owner.handle ?: avatarHandle(owner.id),
    )
}

/**
 * Some lockups (related videos, parts of a channel's Home) put the handle only on the avatar's tap
 * target, not on the byline. It counts only when that avatar is the owner's own channel.
 */
private fun JsonObject.avatarHandle(ownerId: String): String? {
    if (ownerId.isBlank()) return null
    val command =
        this["metadata"]
            .objectOrNull()
            ?.get("lockupMetadataViewModel")
            .objectOrNull()
            ?.get("image")
            .objectOrNull()
            ?.get("decoratedAvatarViewModel")
            .objectOrNull()
            ?.get("rendererContext")
            .objectOrNull()
            ?.get("commandContext")
            .objectOrNull()
            ?.get("onTap")
            .objectOrNull()
            ?.get("innertubeCommand")
            .objectOrNull() ?: return null
    val browseId = command["browseEndpoint"].objectOrNull()?.get("browseId").stringOrNull()
    return command.browseHandle().takeIf { browseId == ownerId }
}

/** A collaboration's byline links to no channel, so it is found by the lead collaborator's name instead. */
private fun List<VideoCollaborator>.bylineOwner(parts: List<String>): FeedItemOwner? {
    val lead = firstOrNull() ?: return null
    val name = parts.firstOrNull { lead.name in it } ?: return null
    return FeedItemOwner(id = lead.channelId, name = name, avatarUrl = lead.thumbnailUrl)
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
            val command =
                text["commandRuns"]
                    .arrayOrNull()
                    ?.firstOrNull()
                    .objectOrNull()
                    ?.get("onTap")
                    .objectOrNull()
                    ?.get("innertubeCommand")
                    .objectOrNull()
            val channelId =
                command
                    ?.get("browseEndpoint")
                    .objectOrNull()
                    ?.get("browseId")
                    .stringOrNull()
                    ?.takeIf { it.startsWith("UC") } ?: return@mapNotNull null
            FeedItemOwner(id = channelId, name = name, handle = command?.browseHandle())
        }.firstOrNull()

private fun JsonObject?.metadataParts(): List<String> = metadataPartTexts().mapNotNull { it.youtubeText()?.takeIf(String::isNotBlank) }

private fun JsonObject?.contentMetadata(): JsonObject? =
    this
        ?.get("metadata")
        .objectOrNull()
        ?.get("contentMetadataViewModel")
        .objectOrNull()

private fun JsonObject?.metadataPartTexts(): List<JsonObject> =
    contentMetadata()
        ?.get("metadataRows")
        .arrayOrNull()
        .orEmpty()
        .flatMap { row ->
            row
                .objectOrNull()
                ?.get("metadataParts")
                .arrayOrNull()
                .orEmpty()
                .mapNotNull { it.objectOrNull()?.get("text").objectOrNull() }
        }

/**
 * The labels a playlist or Mix lockup prints. Its "View full playlist" part is a link to the
 * playlist page, which the whole card already opens, so it is left out whatever the language.
 */
internal fun JsonObject.lockupCollectionLabels(): CollectionLabels {
    val metadata = this["metadata"].objectOrNull()?.get("lockupMetadataViewModel").objectOrNull()
    val parts =
        metadata
            .metadataPartTexts()
            .filterNot { it.linksToPlaylistPage() }
            .mapNotNull { it.youtubeText()?.takeIf(String::isNotBlank) }
    val delimiter = metadata.contentMetadata()?.get("delimiter").stringOrNull() ?: DEFAULT_METADATA_DELIMITER
    return CollectionLabels(
        badge = lockupBadgeModels().firstNotNullOfOrNull { it["text"].youtubeText()?.takeIf(String::isNotBlank) },
        byline = parts.firstOrNull(),
        details = parts.drop(1).joinToString(delimiter).takeIf(String::isNotEmpty),
    )
}

private fun JsonObject.linksToPlaylistPage(): Boolean =
    this["commandRuns"]
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
        ?.startsWith(PLAYLIST_PAGE_BROWSE_PREFIX) == true

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
    lockupBadgeModels().mapNotNull { model ->
        model["text"].youtubeText()?.takeIf(String::isNotBlank) ?: model["badgeStyle"].stringOrNull()
    }

private fun JsonObject.lockupBadgeModels(): List<JsonObject> =
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
            badges.mapNotNull { it.objectOrNull()?.get("thumbnailBadgeViewModel").objectOrNull() }
        }

private fun String.mentionsSubscribers(): Boolean = contains("subscriber", ignoreCase = true)

private fun String.marksLive(): Boolean = equals("LIVE", ignoreCase = true) || contains("LIVE_NOW", ignoreCase = true)

private fun String.marksUpcoming(): Boolean = parseDurationText(this) == null && !marksLive()
