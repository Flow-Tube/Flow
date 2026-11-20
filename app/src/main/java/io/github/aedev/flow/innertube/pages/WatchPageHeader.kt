package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.RichTextSpan
import io.github.aedev.flow.data.model.RichTextTarget
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What the watch page prints around the title and the channel name, beyond the text itself. */
data class WatchPageHeader(
    /** The hashtags or the place YouTube prints above the title. */
    val superTitle: RichText? = null,
    val ownerBadge: ChannelBadge? = null,
    /** Present when the server wants the counts refreshed through `updated_metadata`. */
    val updatedMetadata: UpdatedMetadataEndpoint? = null,
)

data class UpdatedMetadataEndpoint(
    val videoId: String,
    val initialDelayMs: Long? = null,
)

/** The mark beside a channel name; an artist's channel carries a note where others carry a tick. */
enum class ChannelBadge { VERIFIED, ARTIST }

internal fun JsonElement.toWatchPageHeader(ownVideoId: String?): WatchPageHeader {
    val primary = watchInfoRenderer("videoPrimaryInfoRenderer")
    // Flow has a page for a hashtag but none for a place, so a location run stays plain text.
    val superTitle =
        primary?.get("superTitleLink").runsToRichText(ownVideoId)?.let { line ->
            line.copy(spans = line.spans.filter { it.target is RichTextTarget.Hashtag })
        }
    val badgeStyles =
        watchInfoRenderer("videoSecondaryInfoRenderer")
            ?.get("owner")
            .objectOrNull()
            ?.get("videoOwnerRenderer")
            .objectOrNull()
            ?.get("badges")
            .arrayOrNull()
            .orEmpty()
            .mapNotNull {
                it
                    .objectOrNull()
                    ?.get("metadataBadgeRenderer")
                    .objectOrNull()
                    ?.get("style")
                    .stringOrNull()
            }
    val ownerBadge =
        when {
            ARTIST_BADGE in badgeStyles -> ChannelBadge.ARTIST
            VERIFIED_BADGE in badgeStyles -> ChannelBadge.VERIFIED
            else -> null
        }
    val updatedMetadata =
        primary
            ?.get("updatedMetadataEndpoint")
            .objectOrNull()
            ?.get("updatedMetadataEndpoint")
            .objectOrNull()
            ?.let { endpoint ->
                val videoId = endpoint["videoId"].stringOrNull()?.takeIf { it.isNotBlank() } ?: return@let null
                UpdatedMetadataEndpoint(videoId, endpoint["initialDelayMs"].stringOrNull()?.toLongOrNull())
            }
    return WatchPageHeader(superTitle = superTitle, ownerBadge = ownerBadge, updatedMetadata = updatedMetadata)
}

private const val VERIFIED_BADGE = "BADGE_STYLE_TYPE_VERIFIED"
private const val ARTIST_BADGE = "BADGE_STYLE_TYPE_VERIFIED_ARTIST"

/** One of the info renderers the web watch response stacks under the player. */
internal fun JsonElement.watchInfoRenderer(name: String): JsonObject? =
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
        .firstNotNullOfOrNull { it.objectOrNull()?.get(name).objectOrNull() }

/** Runs joined into one text, each run that carries an endpoint Flow can follow kept as a span. */
internal fun JsonElement?.runsToRichText(ownVideoId: String?): RichText? {
    val runs = objectOrNull()?.get("runs").arrayOrNull() ?: return null
    val text = StringBuilder()
    val spans = mutableListOf<RichTextSpan>()
    runs.forEach { element ->
        val run = element.objectOrNull() ?: return@forEach
        val runText = run["text"].stringOrNull().orEmpty()
        if (runText.isEmpty()) return@forEach
        run["navigationEndpoint"].objectOrNull()?.toRichTextTarget(ownVideoId)?.let { target ->
            spans += RichTextSpan(start = text.length, length = runText.length, target = target)
        }
        text.append(runText)
    }
    return RichText(text = text.toString(), spans = spans).takeIf { it.text.isNotBlank() }
}
