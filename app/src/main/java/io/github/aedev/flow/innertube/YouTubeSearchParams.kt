package io.github.aedev.flow.innertube

import io.github.aedev.flow.utils.protobuf.ProtobufWriter
import java.net.URLEncoder
import java.util.Base64

/**
 * Builds the `params` token `/youtubei/v1/search` filters on.
 *
 * The field numbers were recovered by decoding the tokens YouTube's own filter dialog ships, so a
 * combination the dialog cannot express ("videos, this week, over 20 minutes") encodes the same way
 * a single filter does. [YouTubeSearchParamsTest] pins every one of those tokens.
 *
 * [exactQuery] searches the text as typed, with no spelling correction, and combines with any filter.
 *
 * Only [SortBy.RELEVANCE] and [SortBy.VIEW_COUNT] actually reorder results — verified live on
 * 2026-09-14, and YouTube's dialog offers no others. Recency is an [UploadDate] filter, not a sort.
 */
internal object YouTubeSearchParams {
    enum class SortBy(
        val value: Int,
    ) {
        RELEVANCE(0),
        VIEW_COUNT(3),
    }

    enum class ContentType(
        val value: Int,
    ) {
        VIDEO(1),
        CHANNEL(2),
        PLAYLIST(3),
        MOVIE(4),
        SHORTS(9),
    }

    enum class Duration(
        val value: Int,
    ) {
        UNDER_3_MINUTES(4),
        THREE_TO_20_MINUTES(5),
        OVER_20_MINUTES(2),
    }

    enum class UploadDate(
        val value: Int,
    ) {
        LAST_HOUR(1),
        TODAY(2),
        THIS_WEEK(3),
        THIS_MONTH(4),
        THIS_YEAR(5),
    }

    /** Each feature is its own boolean field inside the filter message. */
    enum class Feature(
        val field: Int,
    ) {
        HD(4),
        SUBTITLES(5),
        CREATIVE_COMMONS(6),
        THREE_D(7),
        LIVE(8),
        PURCHASED(9),
        FOUR_K(14),
        THREE_SIXTY(15),
        LOCATION(23),
        HDR(25),
        VR180(26),
    }

    /** Null when nothing is selected — YouTube treats an absent `params` as unfiltered relevance. */
    fun build(
        sortBy: SortBy = SortBy.RELEVANCE,
        contentType: ContentType? = null,
        duration: Duration? = null,
        uploadDate: UploadDate? = null,
        features: Set<Feature> = emptySet(),
        exactQuery: Boolean = false,
    ): String? {
        val filters =
            ProtobufWriter.encode {
                uploadDate?.let { writeInt32(FILTER_DATE_FIELD, it.value) }
                contentType?.let { writeInt32(FILTER_TYPE_FIELD, it.value) }
                duration?.let { writeInt32(FILTER_DURATION_FIELD, it.value) }
                features.sortedBy { it.field }.forEach { writeBool(it.field, true) }
            }

        val request =
            ProtobufWriter.encode {
                if (sortBy.value != 0) writeInt32(SORT_FIELD, sortBy.value)
                if (filters.isNotEmpty()) writeBytes(FILTERS_FIELD, filters)
                if (exactQuery) writeBytes(QUERY_OPTIONS_FIELD, ProtobufWriter.encode { writeBool(EXACT_QUERY_FIELD, true) })
            }
        if (request.isEmpty()) return null

        return URLEncoder.encode(Base64.getEncoder().encodeToString(request), Charsets.UTF_8.name())
    }

    private const val SORT_FIELD = 1
    private const val FILTERS_FIELD = 2
    private const val FILTER_DATE_FIELD = 1
    private const val FILTER_TYPE_FIELD = 2
    private const val FILTER_DURATION_FIELD = 3

    // "Search instead for" in a corrected search's response carries exactly this: field 8 { 1: 1 }.
    private const val QUERY_OPTIONS_FIELD = 8
    private const val EXACT_QUERY_FIELD = 1
}
