package io.github.aedev.flow.innertube.pages.search

import io.github.aedev.flow.innertube.pages.arrayOrNull
import io.github.aedev.flow.innertube.pages.objectOrNull
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.innertube.pages.renderer.distinctKey
import io.github.aedev.flow.innertube.pages.renderer.toFeedItem
import io.github.aedev.flow.innertube.pages.renderer.toFeedShelf
import io.github.aedev.flow.innertube.pages.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Reads a search response into [SearchResultsPage].
 *
 * Structural walking against the shared renderer registry, not typed DTOs: YouTube reshapes
 * renderers constantly, and an entry with no parser is skipped rather than failing the response.
 * The walk is bounded to the feed's own containers, so the registry never sees a token or a
 * thumbnail belonging to something else.
 */
fun JsonObject.toSearchResultsPage(): SearchResultsPage {
    val sections = mutableListOf<SearchSection>()
    val seen = mutableSetOf<String>()
    var continuation: String? = null
    var correction: SearchCorrection? = null

    feedEntries().forEach { entry ->
        entry.continuationToken()?.let {
            continuation = it
            return@forEach
        }
        entry.toSearchCorrection()?.let {
            if (correction == null) correction = it
            return@forEach
        }
        entry.toFeedShelf(FeedItemOwner(), sections.size)?.let {
            sections += SearchSection.Strip(it)
            return@forEach
        }
        entry.toFeedItem(FeedItemOwner())?.let { item ->
            if (seen.add(item.distinctKey())) sections += SearchSection.Result(item)
        }
    }

    return SearchResultsPage(
        header = toSearchHeader().copy(correction = correction),
        sections = sections,
        estimatedResults = this["estimatedResults"].stringOrNull()?.toLongOrNull(),
        continuation = continuation,
    )
}

/**
 * The first page nests the feed under `twoColumnSearchResultsRenderer`; a continuation returns it
 * flat under `appendContinuationItemsAction`. Both end in the same list of entries.
 */
private fun JsonObject.feedEntries(): List<JsonObject> {
    val sectionList =
        this["contents"]
            .objectOrNull()
            ?.get("twoColumnSearchResultsRenderer")
            .objectOrNull()
            ?.get("primaryContents")
            .objectOrNull()
            ?.get("sectionListRenderer")
            .objectOrNull()
    if (sectionList != null) return sectionList["contents"].flattenItemSections()

    return this["onResponseReceivedCommands"]
        .arrayOrNull()
        .orEmpty()
        .mapNotNull {
            it
                .objectOrNull()
                ?.get("appendContinuationItemsAction")
                .objectOrNull()
                ?.get("continuationItems")
        }.flatMap { it.flattenItemSections() }
}

private fun JsonElement?.flattenItemSections(): List<JsonObject> =
    arrayOrNull()
        .orEmpty()
        .mapNotNull { it.objectOrNull() }
        .flatMap { entry ->
            entry["itemSectionRenderer"]
                .objectOrNull()
                ?.get("contents")
                .arrayOrNull()
                ?.mapNotNull { it.objectOrNull() }
                ?: listOf(entry)
        }

private fun JsonObject.continuationToken(): String? =
    this["continuationItemRenderer"]
        .objectOrNull()
        ?.get("continuationEndpoint")
        .objectOrNull()
        ?.get("continuationCommand")
        .objectOrNull()
        ?.get("token")
        .stringOrNull()
        ?.takeIf(String::isNotBlank)

/**
 * `showingResultsForRenderer` (results already corrected, with the exact-search way back) or
 * `didYouMeanRenderer` (results for what was typed, with a suggestion). Both lead the first page.
 */
private fun JsonObject.toSearchCorrection(): SearchCorrection? {
    val (kind, renderer) =
        this["showingResultsForRenderer"].objectOrNull()?.let { SearchCorrection.Kind.SHOWING_RESULTS_FOR to it }
            ?: this["didYouMeanRenderer"].objectOrNull()?.let { SearchCorrection.Kind.DID_YOU_MEAN to it }
            ?: return null
    val runs =
        renderer["correctedQuery"]
            .objectOrNull()
            ?.get("runs")
            .arrayOrNull()
            .orEmpty()
            .mapNotNull { run ->
                val obj = run.objectOrNull() ?: return@mapNotNull null
                val text = obj["text"].stringOrNull()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                SearchCorrectionRun(text, changed = obj["italics"].stringOrNull() == "true")
            }
    val corrected =
        renderer["correctedQueryEndpoint"].searchQuery()
            ?: runs.joinToString("") { it.text }.takeIf(String::isNotBlank)
            ?: return null
    val original =
        if (kind == SearchCorrection.Kind.SHOWING_RESULTS_FOR) {
            renderer["originalQueryEndpoint"].searchQuery() ?: renderer["originalQuery"].searchText() ?: return null
        } else {
            null
        }
    return SearchCorrection(
        kind = kind,
        correctedQuery = corrected,
        correctedRuns = runs.ifEmpty { listOf(SearchCorrectionRun(corrected, changed = false)) },
        originalQuery = original,
    )
}

private fun JsonElement?.searchQuery(): String? =
    objectOrNull()
        ?.get("searchEndpoint")
        .objectOrNull()
        ?.get("query")
        .stringOrNull()
        ?.takeIf(String::isNotBlank)

private fun JsonObject.toSearchHeader(): SearchHeader {
    val header = this["header"].objectOrNull()?.get("searchHeaderRenderer").objectOrNull()
    return SearchHeader(
        chips = header?.get("chipBar").toSearchChips(),
        filterGroups = header?.get("searchFilterButton").toSearchFilterGroups(),
    )
}

private fun JsonElement?.toSearchChips(): List<SearchChip> =
    objectOrNull()
        ?.get("chipCloudRenderer")
        .objectOrNull()
        ?.get("chips")
        .arrayOrNull()
        .orEmpty()
        .mapNotNull { it.objectOrNull()?.get("chipCloudChipRenderer").objectOrNull() }
        .mapNotNull { chip ->
            val label = chip["text"].searchText() ?: return@mapNotNull null
            SearchChip(
                label = label,
                selected = chip["isSelected"].stringOrNull() == "true",
                continuation =
                    chip["navigationEndpoint"]
                        .objectOrNull()
                        ?.get("continuationCommand")
                        .objectOrNull()
                        ?.get("token")
                        .stringOrNull(),
            )
        }

private fun JsonElement?.toSearchFilterGroups(): List<SearchFilterGroup> =
    objectOrNull()
        ?.get("buttonRenderer")
        .objectOrNull()
        ?.get("command")
        .objectOrNull()
        ?.get("openPopupAction")
        .objectOrNull()
        ?.get("popup")
        .objectOrNull()
        ?.get("searchFilterOptionsDialogRenderer")
        .objectOrNull()
        ?.get("groups")
        .arrayOrNull()
        .orEmpty()
        .mapNotNull { it.objectOrNull()?.get("searchFilterGroupRenderer").objectOrNull() }
        .mapNotNull { group ->
            val title = group["title"].searchText() ?: return@mapNotNull null
            val options =
                group["filters"]
                    .arrayOrNull()
                    .orEmpty()
                    .mapNotNull { it.objectOrNull()?.get("searchFilterRenderer").objectOrNull() }
                    .mapNotNull { filter ->
                        val label = filter["label"].searchText() ?: return@mapNotNull null
                        SearchFilterOption(
                            label = label,
                            selected = filter["status"].stringOrNull() == "FILTER_STATUS_SELECTED",
                            params =
                                filter["navigationEndpoint"]
                                    .objectOrNull()
                                    ?.get("searchEndpoint")
                                    .objectOrNull()
                                    ?.get("params")
                                    .stringOrNull(),
                        )
                    }
            SearchFilterGroup(title, options).takeIf { options.isNotEmpty() }
        }

private fun JsonElement?.searchText(): String? {
    objectOrNull()
        ?.get("simpleText")
        .stringOrNull()
        ?.takeIf(String::isNotBlank)
        ?.let { return it }
    val runs = objectOrNull()?.get("runs") as? JsonArray ?: return null
    return runs
        .mapNotNull { it.objectOrNull()?.get("text").stringOrNull() }
        .joinToString("")
        .takeIf(String::isNotBlank)
}
