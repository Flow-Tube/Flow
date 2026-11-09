package io.github.aedev.flow.ui.screens.search

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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SearchHistoryItem
import io.github.aedev.flow.data.local.SearchHistoryRepository
import io.github.aedev.flow.data.local.SearchHistoryScope
import io.github.aedev.flow.innertube.pages.search.SearchSuggestion
import io.github.aedev.flow.player.DeepFlowManager
import io.github.aedev.flow.ui.components.search.matchingTyped
import io.github.aedev.flow.ui.components.shared.DeepFlowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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
    private val history: SearchHistoryRepository,
    private val preferences: PlayerPreferences,
    private val allHistoryState: State<List<SearchHistoryItem>>,
    private val suggestionsEnabledState: State<Boolean>,
    private val gridModeState: State<Boolean>,
    private val shortsEnabledState: State<Boolean>,
    private val feedColumnsState: State<HomeFeedColumns>,
    private val deepFlowState: State<DeepFlowState>,
    private val setDeepFlow: suspend (Boolean) -> Unit,
    private val fetchSuggestions: suspend (String) -> List<SearchSuggestion>,
    private val onHistoryCleared: () -> Unit = {},
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
        get() = allHistoryState.value.matchingTyped(query)

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

    fun deleteHistoryItem(item: SearchHistoryItem) {
        scope.launch { history.deleteSearchItem(item.id) }
    }

    fun clearHistory() {
        scope.launch { history.clearSearchHistory(SearchHistoryScope.VIDEO) }
        onHistoryCleared()
    }

    /** The same switch as Home's long-press: the snackbar, the timer and the video pause come with it. */
    fun setDeepFlowEnabled(enabled: Boolean) {
        scope.launch { setDeepFlow(enabled) }
    }

    fun toggleGridMode() {
        scope.launch { preferences.setSearchIsGridMode(!isGridMode) }
    }

    internal suspend fun refreshSuggestions(typed: String) {
        suggestions =
            if (suggestionsEnabledState.value && typed.trim().length >= MIN_SUGGESTION_LENGTH) {
                fetchSuggestions(typed.trim())
            } else {
                emptyList()
            }
    }

    private companion object {
        const val MIN_SUGGESTION_LENGTH = 2
    }
}

@OptIn(FlowPreview::class)
@Composable
fun rememberSearchState(viewModel: SearchViewModel): SearchScreenState {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val historyRepository = remember(context) { SearchHistoryRepository(context) }
    val preferences = remember(context) { PlayerPreferences(context) }

    val allHistory = historyRepository.getSearchHistoryFlow().collectAsStateWithLifecycle(emptyList())
    val suggestionsEnabled = historyRepository.isSearchSuggestionsEnabledFlow().collectAsStateWithLifecycle(true)
    val gridMode = preferences.searchIsGridMode.collectAsStateWithLifecycle(false)
    val shortsEnabled = preferences.shortsContentEnabled.collectAsStateWithLifecycle(true)
    val feedColumns = preferences.homeFeedColumns.collectAsStateWithLifecycle(HomeFeedColumns.AUTO)
    val deepFlow =
        remember(preferences) {
            combine(
                preferences.deepFlowOnSearch,
                preferences.deepFlowActive,
                preferences.deepFlowActivatedAt,
                preferences.deepFlowExpireHours,
            ) { onSearch, active, activatedAt, expireHours ->
                DeepFlowState(active = active, activatedAt = activatedAt, expireHours = expireHours, showOnSearch = onSearch)
            }
        }.collectAsStateWithLifecycle(DeepFlowState())

    // Coming back from a result recreates this composable while the ViewModel keeps the query, so the
    // field and the typing mode are seeded from it rather than starting empty.
    val restoredQuery =
        viewModel.uiState.value.query
            .takeIf(String::isNotBlank)
    val textFieldState = remember { TextFieldState(initialText = restoredQuery.orEmpty()) }

    val state =
        remember(historyRepository, preferences) {
            SearchScreenState(
                textFieldState = textFieldState,
                submittedQuery = restoredQuery,
                scope = scope,
                history = historyRepository,
                preferences = preferences,
                allHistoryState = allHistory,
                suggestionsEnabledState = suggestionsEnabled,
                gridModeState = gridMode,
                shortsEnabledState = shortsEnabled,
                feedColumnsState = feedColumns,
                deepFlowState = deepFlow,
                setDeepFlow = { DeepFlowManager.setEnabled(context, it) },
                fetchSuggestions = viewModel::getSearchSuggestions,
                onHistoryCleared = viewModel::onSearchHistoryCleared,
            )
        }

    LaunchedEffect(state) {
        snapshotFlow { state.textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect(state::onTextChanged)
    }

    LaunchedEffect(state) {
        snapshotFlow { state.textFieldState.text.toString() }
            .distinctUntilChanged()
            .debounce(SUGGESTION_DEBOUNCE_MS)
            .collect { state.refreshSuggestions(it) }
    }

    return state
}

private const val SUGGESTION_DEBOUNCE_MS = 280L
