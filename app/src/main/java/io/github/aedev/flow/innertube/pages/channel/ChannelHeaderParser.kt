package io.github.aedev.flow.innertube.pages.channel

import io.github.aedev.flow.innertube.pages.arrayOrNull
import io.github.aedev.flow.innertube.pages.objectOrNull
import io.github.aedev.flow.innertube.pages.parseYouTubeViewCount
import io.github.aedev.flow.innertube.pages.renderer.findRenderers
import io.github.aedev.flow.innertube.pages.renderer.largestImageUrl
import io.github.aedev.flow.innertube.pages.stringOrNull
import io.github.aedev.flow.innertube.pages.youtubeText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The channel header, read from whichever of the three generations the response happens to use:
 * the modern `pageHeaderViewModel`, the legacy `c4TabbedHeaderRenderer`, and `channelMetadataRenderer`
 * as the floor. Any one of them can be absent, so each field falls through independently rather than
 * the whole header committing to one shape.
 */
internal fun JsonElement.toChannelHeader(requestedId: String): ChannelHeader {
    val renderers =
        findRenderers(
            "pageHeaderViewModel",
            "c4TabbedHeaderRenderer",
            "channelMetadataRenderer",
            "aboutChannelViewModel",
        )
    val page = renderers["pageHeaderViewModel"]
    val legacy = renderers["c4TabbedHeaderRenderer"]
    val metadata = renderers["channelMetadataRenderer"]
    val about = renderers["aboutChannelViewModel"]

    val parts = page?.metadataParts().orEmpty()
    val counts = parts.filterNot { it.text.startsWith("@") }
    val handle =
        parts.firstOrNull { it.text.startsWith("@") }?.text
            ?: metadata
                ?.get("vanityChannelUrl")
                .stringOrNull()
                ?.substringAfterLast('/')
                ?.takeIf { it.startsWith("@") }
    val subscriberPart = counts.subscriberPart()
    val subscriberText = subscriberPart?.text ?: legacy?.get("subscriberCountText").youtubeText()
    val videoText =
        counts.firstOrNull { it !== subscriberPart }?.text
            ?: legacy?.get("videosCountText").youtubeText()

    val title =
        page
            ?.get("title")
            .objectOrNull()
            ?.get("dynamicTextViewModel")
            .objectOrNull()
            ?.get("text")
            .youtubeText()
            ?: metadata?.get("title").youtubeText()
            ?: legacy?.get("title").youtubeText()
            ?: ""

    return ChannelHeader(
        id =
            metadata?.get("externalChannelId").stringOrNull()
                ?: metadata?.get("externalId").stringOrNull()
                ?: legacy?.get("channelId").stringOrNull()
                ?: requestedId,
        title = title,
        handle = handle,
        avatarUrl = page?.avatarUrl() ?: metadata?.get("avatar").largestImageUrl() ?: legacy?.get("avatar").largestImageUrl() ?: "",
        bannerUrl = page?.bannerUrl() ?: legacy?.get("banner").largestImageUrl(),
        subscriberCountText = subscriberText,
        subscriberCount = subscriberText?.let { parseYouTubeViewCount(it) }?.takeIf { it > 0L },
        videoCountText = videoText,
        description =
            about?.get("description").youtubeText()
                ?: metadata?.get("description").youtubeText()
                ?: page?.descriptionPreview(),
        links = emptyList(),
        isVerified = page?.isVerified() ?: legacy?.hasVerifiedBadge() ?: false,
        joinedDateText = about?.get("joinedDateText").youtubeText(),
        viewCountText = about?.get("viewCountText").youtubeText(),
        countryText = about?.get("country").youtubeText(),
        canonicalUrl =
            about?.get("canonicalChannelUrl").stringOrNull()
                ?: metadata?.get("vanityChannelUrl").stringOrNull()
                ?: metadata?.get("channelUrl").stringOrNull(),
    )
}

private class MetadataPart(
    val text: String,
    val hasAccessibilityLabel: Boolean,
)

/**
 * The header's count parts carry no field names and their words are in the app's language, so they
 * are told apart by shape: only the subscriber count has an accessibility label (seen in en, es, ja
 * and ar). Without one, two parts are subscribers then videos, and a lone part is the video count of
 * a channel that hides its subscribers.
 */
private fun List<MetadataPart>.subscriberPart(): MetadataPart? =
    firstOrNull { it.hasAccessibilityLabel }
        ?: firstOrNull { it.text.contains("subscriber", ignoreCase = true) }
        ?: takeIf { it.size >= 2 }?.first()

private fun JsonObject.metadataParts(): List<MetadataPart> =
    this["metadata"]
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
                .mapNotNull { element ->
                    val part = element.objectOrNull() ?: return@mapNotNull null
                    val text = part["text"].youtubeText()?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                    MetadataPart(text, hasAccessibilityLabel = part["accessibilityLabel"].stringOrNull() != null)
                }
        }

private fun JsonObject.avatarUrl(): String? =
    this["image"]
        .objectOrNull()
        ?.let { image ->
            image["decoratedAvatarViewModel"]
                .objectOrNull()
                ?.get("avatar")
                .objectOrNull()
                ?.get("avatarViewModel")
                .objectOrNull()
                ?.get("image")
                .largestImageUrl()
                ?: image["avatarViewModel"].objectOrNull()?.get("image").largestImageUrl()
                ?: image["contentPreviewImageViewModel"].objectOrNull()?.get("image").largestImageUrl()
        }

private fun JsonObject.bannerUrl(): String? =
    this["banner"]
        .objectOrNull()
        ?.get("imageBannerViewModel")
        .objectOrNull()
        ?.get("image")
        .largestImageUrl()

private fun JsonObject.descriptionPreview(): String? =
    this["description"]
        .objectOrNull()
        ?.get("descriptionPreviewViewModel")
        .objectOrNull()
        ?.get("description")
        .youtubeText()

/**
 * The badge is not a field — it only ever shows up in the accessibility label the title carries,
 * which is why a structural search for a verified badge comes back empty on modern payloads.
 */
private fun JsonObject.isVerified(): Boolean {
    val label =
        this["title"]
            .objectOrNull()
            ?.get("dynamicTextViewModel")
            .objectOrNull()
            ?.get("rendererContext")
            .objectOrNull()
            ?.get("accessibilityContext")
            .objectOrNull()
            ?.get("label")
            .stringOrNull()
            .orEmpty()
    return label.contains("verified", ignoreCase = true)
}

private fun JsonObject.hasVerifiedBadge(): Boolean =
    this["badges"]
        .arrayOrNull()
        .orEmpty()
        .any { badge ->
            badge
                .objectOrNull()
                ?.get("metadataBadgeRenderer")
                .objectOrNull()
                ?.get("style")
                .stringOrNull()
                ?.contains("VERIFIED", ignoreCase = true) == true
        }
