package io.github.aedev.flow.ui.components.search

import io.github.aedev.flow.data.local.SearchHistoryItem

const val SEARCH_HISTORY_LIMIT = 8

/** History rows that match what has been typed, prefix matches first, as YouTube orders them. */
fun List<SearchHistoryItem>.matchingTyped(
    typed: String,
    limit: Int = SEARCH_HISTORY_LIMIT,
): List<SearchHistoryItem> {
    val trimmed = typed.trim()
    if (trimmed.isEmpty()) return take(limit)
    val lowered = trimmed.lowercase()
    val matches = filter { it.query.contains(trimmed, ignoreCase = true) }
    val (prefix, rest) = matches.partition { it.query.lowercase().startsWith(lowered) }
    return (prefix + rest).take(limit)
}

/**
 * Every kept search while nothing is typed, when history has the list to itself; only the best
 * matches once typing brings suggestions in below them.
 */
fun List<SearchHistoryItem>.recentOrMatching(typed: String): List<SearchHistoryItem> = if (typed.isBlank()) this else matchingTyped(typed)
