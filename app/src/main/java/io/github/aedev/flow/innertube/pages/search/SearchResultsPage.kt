package io.github.aedev.flow.innertube.pages.search

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import io.github.aedev.flow.innertube.pages.renderer.FeedShelf
import io.github.aedev.flow.innertube.pages.renderer.videoOrNull

/**
 * One page of `/youtubei/v1/search`.
 *
 * The feed is not a flat list of videos: a creator query returns a channel card, a "Latest from"
 * shelf, inline Shorts strips and a community-posts shelf interleaved with the results, in the
 * order YouTube arranged them. [sections] preserves that order.
 */
data class SearchResultsPage(
    val header: SearchHeader = SearchHeader(),
    val sections: List<SearchSection> = emptyList(),
    val estimatedResults: Long? = null,
    val continuation: String? = null,
)

/** Present on the first page only; a continuation carries the chips but no filter dialog. */
data class SearchHeader(
    val chips: List<SearchChip> = emptyList(),
    val filterGroups: List<SearchFilterGroup> = emptyList(),
    val correction: SearchCorrection? = null,
)

/**
 * YouTube changed or questioned the spelling of the query. [Kind.SHOWING_RESULTS_FOR] means the
 * results are already for [correctedQuery] and [originalQuery] can be searched exactly instead;
 * [Kind.DID_YOU_MEAN] means the results are for what was typed and [correctedQuery] is offered.
 */
data class SearchCorrection(
    val kind: Kind,
    val correctedQuery: String,
    val correctedRuns: List<SearchCorrectionRun>,
    val originalQuery: String? = null,
) {
    enum class Kind {
        SHOWING_RESULTS_FOR,
        DID_YOU_MEAN,
    }
}

/** A stretch of the corrected query; [changed] marks the words YouTube corrected. */
data class SearchCorrectionRun(
    val text: String,
    val changed: Boolean,
)

/** A chip re-runs the search through a continuation token rather than through `params`. */
data class SearchChip(
    val label: String,
    val selected: Boolean = false,
    val continuation: String? = null,
)

data class SearchFilterGroup(
    val title: String,
    val options: List<SearchFilterOption> = emptyList(),
)

data class SearchFilterOption(
    val label: String,
    val selected: Boolean = false,
    val params: String? = null,
)

sealed interface SearchSection {
    data class Result(
        val item: FeedItem,
    ) : SearchSection

    data class Strip(
        val shelf: FeedShelf,
    ) : SearchSection
}

/**
 * The videos and Shorts YouTube ranked as results, in order. The strips between them are left out:
 * their Shorts carry no channel and no date, and the rest is YouTube's own suggestions, not a match.
 */
fun SearchResultsPage.resultVideos(): List<Video> = sections.mapNotNull { (it as? SearchSection.Result)?.item?.videoOrNull() }
