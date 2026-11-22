package io.github.aedev.flow.data.search

import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.search.SearchSuggestion
import javax.inject.Inject
import javax.inject.Singleton

/** The one source of typeahead suggestions for both the phone and the TV search surfaces. */
@Singleton
class SearchSuggestionsRepository
    @Inject
    constructor() {
        /** The rows for [query], or null when the request failed and the rows on screen should stay. */
        suspend fun suggestions(query: String): List<SearchSuggestion>? {
            if (query.trim().length < MIN_QUERY_LENGTH) return emptyList()
            return YouTube
                .videoSearchSuggestions(query.trim())
                .getOrNull()
                ?.take(MAX_SUGGESTIONS)
        }

        suspend fun warm() = YouTube.warmSearchSuggestions()

        private companion object {
            const val MIN_QUERY_LENGTH = 2
            const val MAX_SUGGESTIONS = 10
        }
    }
