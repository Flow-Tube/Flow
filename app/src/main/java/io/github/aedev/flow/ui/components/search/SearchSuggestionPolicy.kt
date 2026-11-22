package io.github.aedev.flow.ui.components.search

import io.github.aedev.flow.data.local.SearchHistoryItem
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** How long typing pauses before suggestions are asked for. Cancellation, not this wait, keeps rows current. */
const val SUGGESTION_DEBOUNCE_MS = 150L

/** A suggestion request still out after this is dropped and the rows on screen stay. */
const val SUGGESTION_TIMEOUT_MS = 2_000L

/**
 * Suggestions follow the newest text: each change cancels the request still out for the one
 * before, so a slow answer can never hold back the rows for what is in the box now. Typing a
 * space after a word is not a change, so it leaves that word's request running.
 */
@OptIn(FlowPreview::class)
suspend fun Flow<String>.collectLatestSuggestions(
    debounceMs: Long = SUGGESTION_DEBOUNCE_MS,
    refresh: suspend (String) -> Unit,
) = map(String::trim).distinctUntilChanged().debounce(debounceMs).collectLatest(refresh)

/**
 * Suggestions without the queries a shown history row already offers, so each query is listed
 * once and keeps its history row's delete button.
 */
fun <T> List<T>.withoutShownHistory(
    shown: List<SearchHistoryItem>,
    text: (T) -> String,
): List<T> {
    if (shown.isEmpty()) return this
    val kept = shown.mapTo(HashSet()) { normalizedQuery(it.query) }
    return filterNot { normalizedQuery(text(it)) in kept }
}

private val Whitespace = Regex("\\s+")

internal fun normalizedQuery(query: String): String = query.trim().lowercase().replace(Whitespace, " ")
