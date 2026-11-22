package io.github.aedev.flow.ui.screens.search

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.local.SearchHistoryItem
import io.github.aedev.flow.innertube.pages.search.SearchSuggestion
import io.github.aedev.flow.player.DeepFlowManager
import io.github.aedev.flow.ui.components.search.SUGGESTION_DEBOUNCE_MS
import io.github.aedev.flow.ui.components.search.SUGGESTION_TIMEOUT_MS
import io.github.aedev.flow.ui.components.search.collectLatestSuggestions
import io.github.aedev.flow.ui.components.search.recentOrMatching
import io.github.aedev.flow.ui.components.shared.DeepFlowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything the search route needs that is not a result: the field, the history it matches
 * against, the live suggestions, and the display preferences.
 *
 * The preferences arrive as [State] rather than being written into this holder, so reading one
 * never schedules a recomposition of its own.
 */
@Stable
class SearchScreenState(
    val textFieldState: TextFieldState,
    private var submittedQuery: String?,
    private val scope: CoroutineScope,
    private val allHistoryState: State<List<SearchHistoryItem>>,
    private val suggestionsEnabledState: State<Boolean>,
    private val gridModeState: State<Boolean>,
    private val shortsEnabledState: State<Boolean>,
    private val feedColumnsState: State<HomeFeedColumns>,
    private val deepFlowState: State<DeepFlowState>,
    private val setDeepFlow: suspend (Boolean) -> Unit,
    private val fetchSuggestions: suspend (String) -> List<SearchSuggestion>?,
    private val onDeleteHistoryItem: (SearchHistoryItem) -> Unit = {},
    private val onClearHistory: () -> Unit = {},
    private val onGridModeChange: (Boolean) -> Unit = {},
) {
    var suggestions by mutableStateOf<List<SearchSuggestion>>(emptyList())
        private set

    /** True while the field owns the screen; submitting or going back hands it to the results. */
    var isTyping by mutableStateOf(submittedQuery == null)
        private set

    val isGridMode: Boolean get() = gridModeState.value
    val shortsContentEnabled: Boolean get() = shortsEnabledState.value
    val feedColumns: HomeFeedColumns get() = feedColumnsState.value
    val deepFlow: DeepFlowState get() = deepFlowState.value

    val query: String
        get() = textFieldState.text.toString()

    val matchingHistory: List<SearchHistoryItem>
        get() = allHistoryState.value.recentOrMatching(query)

    fun onSubmit(text: String) {
        suggestions = emptyList()
        submittedQuery = text
        isTyping = false
    }

    /** Editing reopens the suggestions; re-arriving at the submitted text does not. */
    internal fun onTextChanged(text: String) {
        if (text.trim() != submittedQuery?.trim()) isTyping = true
    }

    /** Tapping the field reopens the suggestions over a finished search. */
    fun startTyping() {
        isTyping = true
    }

    fun stopTyping() {
        isTyping = false
    }

    /** Puts [query], the search this page shows, back in the field and closes the suggestions. */
    fun returnTo(query: String) {
        textFieldState.setTextAndPlaceCursorAtEnd(query)
        onSubmit(query)
    }

    fun deleteHistoryItem(item: SearchHistoryItem) = onDeleteHistoryItem(item)

    fun clearHistory() = onClearHistory()

    /** The same switch as Home's long-press: the snackbar, the timer and the video pause come with it. */
    fun setDeepFlowEnabled(enabled: Boolean) {
        scope.launch { setDeepFlow(enabled) }
    }

    fun toggleGridMode() = onGridModeChange(!isGridMode)

    /**
     * Shows the rows for [typed]. A request that fails or outlives [SUGGESTION_TIMEOUT_MS] leaves
     * the rows on screen as they were, and a cancelled one never writes.
     */
    internal suspend fun refreshSuggestions(typed: String) {
        val trimmed = typed.trim()
        if (!suggestionsEnabledState.value || trimmed.length < MIN_SUGGESTION_LENGTH) {
            suggestions = emptyList()
            return
        }
        val rows = withTimeoutOrNull(SUGGESTION_TIMEOUT_MS) { fetchSuggestions(trimmed) }
        currentCoroutineContext().ensureActive()
        if (rows != null) suggestions = rows
    }

    private companion object {
        const val MIN_SUGGESTION_LENGTH = 2
    }
}

/**
 * [suggestionsActive] lets a surface that hosts more than one kind of search (TV's music tab)
 * stop asking for video suggestions while another kind owns the field.
 */
@Composable
fun rememberSearchState(
    viewModel: SearchViewModel,
    suggestionsActive: () -> Boolean = { true },
): SearchScreenState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val allHistory = viewModel.searchHistoryItems.collectAsStateWithLifecycle(emptyList())
    val suggestionsEnabled = viewModel.suggestionsEnabled.collectAsStateWithLifecycle(true)
    val gridMode = viewModel.gridMode.collectAsStateWithLifecycle(false)
    val shortsEnabled = viewModel.shortsContentEnabled.collectAsStateWithLifecycle(true)
    val feedColumns = viewModel.feedColumns.collectAsStateWithLifecycle(HomeFeedColumns.AUTO)
    val deepFlow = viewModel.deepFlow.collectAsStateWithLifecycle(DeepFlowState())
    val active by rememberUpdatedState(suggestionsActive)

    // Coming back from a result recreates this composable while the ViewModel keeps the query, so the
    // field and the typing mode are seeded from it rather than starting empty.
    val restoredQuery =
        viewModel.uiState.value.query
            .takeIf(String::isNotBlank)
    val textFieldState = remember { TextFieldState(initialText = restoredQuery.orEmpty()) }

    val state =
        remember(viewModel) {
            SearchScreenState(
                textFieldState = textFieldState,
                submittedQuery = restoredQuery,
                scope = scope,
                allHistoryState = allHistory,
                suggestionsEnabledState = suggestionsEnabled,
                gridModeState = gridMode,
                shortsEnabledState = shortsEnabled,
                feedColumnsState = feedColumns,
                deepFlowState = deepFlow,
                setDeepFlow = { DeepFlowManager.setEnabled(context, it) },
                fetchSuggestions = viewModel::getSearchSuggestions,
                onDeleteHistoryItem = viewModel::deleteHistoryItem,
                onClearHistory = viewModel::clearSearchHistory,
                onGridModeChange = viewModel::setGridMode,
            )
        }

    LaunchedEffect(state) {
        snapshotFlow { state.textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect(state::onTextChanged)
    }

    LaunchedEffect(state) {
        if (state.query.isBlank()) viewModel.warmSearchSuggestions()
        snapshotFlow {
            state.textFieldState.text
                .toString()
                .takeIf { active() }
        }.distinctUntilChanged()
            .filterNotNull()
            .collectLatestSuggestions { typed ->
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    state.refreshSuggestions(typed)
                    logSuggestTiming("rows=${state.suggestions.size}", typed, startedAt)
                } catch (cancelled: CancellationException) {
                    logSuggestTiming("superseded", typed, startedAt)
                    throw cancelled
                }
            }
    }

    return state
}

// Debug builds only, beside FlowRequestLog's network lines: the length, never the text, of what was typed.
private fun logSuggestTiming(
    outcome: String,
    typed: String,
    startedAt: Long,
) {
    if (!BuildConfig.DEBUG) return
    val waited = SystemClock.elapsedRealtime() - startedAt + SUGGESTION_DEBOUNCE_MS
    Log.d("FlowRequests", "suggest len=${typed.trim().length} $outcome ${waited}ms after keystroke")
}
