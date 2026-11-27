package io.github.aedev.flow.innertube.pages.channel

import io.github.aedev.flow.innertube.pages.arrayOrNull
import io.github.aedev.flow.innertube.pages.objectOrNull
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.innertube.pages.renderer.distinctKey
import io.github.aedev.flow.innertube.pages.renderer.findRenderers
import io.github.aedev.flow.innertube.pages.renderer.largestImageUrl
import io.github.aedev.flow.innertube.pages.renderer.toFeedItem
import io.github.aedev.flow.innertube.pages.renderer.toFeedShelves
import io.github.aedev.flow.innertube.pages.stringOrNull
import io.github.aedev.flow.innertube.pages.youtubeText
import kotlinx.serialization.json.JsonElement

/**
 * A grid tab, in any of the four shapes it arrives in — initial browse, sort switch, page append and
 * the older `richGridContinuation` — because [gridItemLists] already enumerates all four.
 *
 * Items come only from the grid's own lists, never from a walk of the whole response: the header
 * carries carousels of its own, and a document-order search picks those up as channel content.
 */
internal fun JsonElement.toChannelTabContent(
    kind: ChannelTabKind,
    fallbackOwner: FeedItemOwner = FeedItemOwner(),
): ChannelTabContent {
    val owner = resolveOwner(fallbackOwner)
    val sections = if (kind == ChannelTabKind.Home) selectedTabContent().toFeedShelves(owner) else emptyList()
    return ChannelTabContent(
        kind = kind,
        items =
            if (sections.isNotEmpty()) {
                emptyList()
            } else {
                gridItemLists()
                    .flatMap { list -> list.mapNotNull { it.toFeedItem(owner) } }
                    .distinctBy { it.distinctKey() }
            },
        sections = sections,
        filters = channelFilterGroups(),
        continuation = channelItemContinuation(),
        owner = owner,
    )
}

/** Only the first browse carries the header; a continuation is anonymous, so the caller's owner wins. */
internal fun JsonElement.resolveOwner(fallback: FeedItemOwner): FeedItemOwner {
    val metadata = findRenderers("channelMetadataRenderer")["channelMetadataRenderer"]
    return FeedItemOwner(
        id =
            metadata?.get("externalChannelId").stringOrNull()
                ?: metadata?.get("externalId").stringOrNull()
                ?: fallback.id,
        name = metadata?.get("title").youtubeText()?.takeIf(String::isNotBlank) ?: fallback.name,
        avatarUrl = metadata?.get("avatar").largestImageUrl() ?: fallback.avatarUrl,
    )
}

/** The selected tab's own content, so a Home shelf walk cannot wander into another tab's payload. */
private fun JsonElement.selectedTabContent(): JsonElement =
    objectOrNull()
        ?.get("contents")
        .objectOrNull()
        ?.get("twoColumnBrowseResultsRenderer")
        .objectOrNull()
        ?.get("tabs")
        .arrayOrNull()
        .orEmpty()
        .firstNotNullOfOrNull { tab ->
            val renderer = tab.objectOrNull()?.get("tabRenderer").objectOrNull() ?: return@firstNotNullOfOrNull null
            if (renderer["selected"].stringOrNull() != "true") return@firstNotNullOfOrNull null
            val content = renderer["content"].objectOrNull() ?: return@firstNotNullOfOrNull null
            // Topic channels such as Music send an empty section list beside a rich grid of shelves.
            content["sectionListRenderer"]?.takeIf { it.objectOrNull()?.get("contents") != null }
                ?: content["richGridRenderer"]
                ?: content["sectionListRenderer"]
        }
        ?: this
