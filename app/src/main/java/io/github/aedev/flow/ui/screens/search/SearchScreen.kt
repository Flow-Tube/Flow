package io.github.aedev.flow.ui.screens.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.SearchFilter
import io.github.aedev.flow.data.local.availableWith
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.paging.SearchResultItem
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.ui.OnTabReselected
import io.github.aedev.flow.ui.components.layout.navigation.FlowTab
import io.github.aedev.flow.ui.components.search.SearchFilterDialog
import io.github.aedev.flow.ui.components.search.SearchSuggestionsPanel
import io.github.aedev.flow.ui.components.search.SearchTopBar
import io.github.aedev.flow.ui.components.search.SearchTopBarActions
import io.github.aedev.flow.ui.components.shared.MediaPostCommentsSheet
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsViewModel
import io.github.aedev.flow.ui.components.shared.quickactions.sharedQuickActionsViewModel

/**
 * The route: a bar, then either what the user might be looking for or what they found.
 *
 * Typing is a mode of this screen rather than a surface stacked on it, so back always leaves —
 * it never has a collapse step to fall into first.
 */
@Composable
fun SearchScreen(
    onVideoClick: (Video) -> Unit,
    onChannelClick: (Channel) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onShortsQueue: (ShortsQueueSource) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNewSearch: ((String, SearchFilter) -> Unit)? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val state = rememberSearchState(viewModel)

    val quickActions: QuickActionsViewModel = sharedQuickActionsViewModel()
    val pagingItems = viewModel.searchResults.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()
    OnTabReselected(FlowTab.Search.route) { gridState.animateScrollToItem(0) }
    var showFilters by rememberSaveable { mutableStateOf(false) }

    val submitWith = rememberSearchSubmitter(viewModel, state, quickActions, onNewSearch)
    val submit: (String) -> Unit = { raw -> submitWith(raw, uiState.filters) }

    val voiceSearchLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let { spoken ->
                    state.textFieldState.setTextAndPlaceCursorAtEnd(spoken)
                    submit(spoken)
                }
        }

    val showResults = uiState.query.isNotBlank() && !state.isTyping
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(showResults) {
        if (showResults) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        } else {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    // A new query starts at the top; coming back from a result keeps the grid where it was.
    var scrolledForQuery by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.query) {
        if (uiState.query.isNotBlank() && scrolledForQuery != uiState.query) {
            if (scrolledForQuery != null) gridState.scrollToItem(0)
            scrolledForQuery = uiState.query
        }
    }

    LaunchedEffect(pagingItems.itemSnapshotList.items) {
        pagingItems.itemSnapshotList.items
            .filterIsInstance<SearchResultItem.ChannelResult>()
            .forEach { quickActions.loadSubscriptionState(it.channel.id) }
    }

    // Back leaves the screen, but a query typed over a finished search returns to that search first.
    BackHandler(enabled = state.isTyping && uiState.query.isNotBlank()) { state.stopTyping() }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchTopBar(
                textFieldState = state.textFieldState,
                onSearch = submit,
                onBack = onBack,
                onVoiceSearch = { launchVoiceSearch(context, voiceSearchLauncher::launch) },
                focusRequester = focusRequester,
                onFieldFocused = state::startTyping,
                actions =
                    if (showResults) {
                        {
                            SearchTopBarActions(
                                activeFilterCount = uiState.filters.activeCount,
                                isGridMode = state.isGridMode,
                                onOpenFilters = { showFilters = true },
                                onToggleGridMode = state::toggleGridMode,
                            )
                        }
                    } else {
                        null
                    },
            )

            if (!showResults) {
                SearchSuggestionsPanel(
                    query = state.query,
                    history = state.matchingHistory,
                    suggestions = state.suggestions,
                    onSubmit = { text ->
                        state.textFieldState.setTextAndPlaceCursorAtEnd(text)
                        submit(text)
                    },
                    onHistorySelect = { item ->
                        state.textFieldState.setTextAndPlaceCursorAtEnd(item.query)
                        submitWith(item.query, item.filters?.availableWith(state.shortsContentEnabled) ?: uiState.filters)
                    },
                    onFill = state.textFieldState::setTextAndPlaceCursorAtEnd,
                    onDeleteHistoryItem = state::deleteHistoryItem,
                    onClearHistory = state::clearHistory,
                    deepFlow = state.deepFlow,
                    onDeepFlowChange = state::setDeepFlowEnabled,
                )
                return@Column
            }

            SearchResultsBody(
                viewModel = viewModel,
                filters = uiState.filters,
                shortsEnabled = state.shortsContentEnabled,
                isGridMode = state.isGridMode,
                feedColumns = state.feedColumns,
                pagingItems = pagingItems,
                gridState = gridState,
                quickActions = quickActions,
                onVideoClick = onVideoClick,
                onChannelClick = onChannelClick,
                onPlaylistClick = onPlaylistClick,
                onShortsQueue = onShortsQueue,
                dismissKeyboard = state::stopTyping,
            )
        }
        MediaPostCommentsSheet(
            loader = viewModel.postComments,
            onAuthorClick = { channelId -> onChannelClick(Channel(channelId, "", "", 0)) },
        )
    }

    if (showFilters) {
        SearchFilterDialog(
            filter = uiState.filters,
            shortsEnabled = state.shortsContentEnabled,
            onApply = {
                showFilters = false
                viewModel.updateFilters(it)
            },
            onDismiss = { showFilters = false },
        )
    }
}

internal fun launchVoiceSearch(
    context: Context,
    launch: (Intent) -> Unit,
) {
    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.search_voice_prompt))
        }
    try {
        launch(intent)
    } catch (_: ActivityNotFoundException) {
    }
}
