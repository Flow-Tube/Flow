package io.github.aedev.flow.ui.tv.screens

import android.app.Activity
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.local.availableWith
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.innertube.pages.MoodAndGenres
import io.github.aedev.flow.ui.components.search.SearchSuggestionsPanel
import io.github.aedev.flow.ui.components.shared.FlowConnectedToggleGroup
import io.github.aedev.flow.ui.components.shared.FlowSearchField
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.components.shared.MediaKind
import io.github.aedev.flow.ui.components.shared.quickactions.sharedQuickActionsViewModel
import io.github.aedev.flow.ui.screens.music.MusicSearchBody
import io.github.aedev.flow.ui.screens.music.MusicSearchViewModel
import io.github.aedev.flow.ui.screens.search.SearchResultsBody
import io.github.aedev.flow.ui.screens.search.SearchViewModel
import io.github.aedev.flow.ui.screens.search.launchVoiceSearch
import io.github.aedev.flow.ui.screens.search.rememberSearchState
import io.github.aedev.flow.ui.screens.search.rememberSearchSubmitter
import io.github.aedev.flow.ui.tv.components.TvScreenFrame
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.flow.drop

private val KindSelectorWidth = 320.dp

/**
 * TV search: the query, voice search and the Videos and Music switch across the top, the phone's
 * suggestions and results under them. OK on the field raises the system keyboard, which types in
 * every language. Both kinds read the one query, so switching kind keeps what was typed.
 */
@Composable
fun TvSearchScreen(
    viewModel: SearchViewModel,
    onVideoClick: (Video) -> Unit,
    onChannelClick: (Channel) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenMusicPlaylist: (String) -> Unit,
    onOpenMood: (MoodAndGenres.Item) -> Unit,
    onOpenMoods: () -> Unit,
    modifier: Modifier = Modifier,
    musicViewModel: MusicSearchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val dimens = LocalTvDimens.current
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    val uiState by viewModel.uiState.collectAsState()
    val state = rememberSearchState(viewModel)
    val quickActions = sharedQuickActionsViewModel()
    val submitVideo = rememberSearchSubmitter(viewModel, state, quickActions)
    val pagingItems = viewModel.searchResults.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()
    var kind by rememberSaveable { mutableStateOf(MediaKind.Videos) }
    val text = state.textFieldState
    val kinds = MediaKind.entries.map { FlowToggleOption(it, stringResource(it.labelRes), it.icon) }

    // As on the phone: a query edited over finished results goes back to those results first.
    BackHandler(enabled = kind != MediaKind.Music && state.isTyping && uiState.query.isNotBlank()) { state.stopTyping() }

    fun submit() {
        softwareKeyboard?.hide()
        val query = text.text.toString()
        if (kind == MediaKind.Music) musicViewModel.performSearch(query) else submitVideo(query, uiState.filters)
    }

    LaunchedEffect(state) {
        snapshotFlow { text.text.toString() }
            .drop(1)
            .collect(musicViewModel::onQueryChange)
    }

    val voiceLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let { spoken ->
                    text.setTextAndPlaceCursorAtEnd(spoken)
                    submit()
                }
        }

    TvScreenFrame(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = SearchBarHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowSearchField(
                    state = text,
                    placeholder = stringResource(R.string.search_videos_channels_placeholder),
                    onSearch = { submit() },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { launchVoiceSearch(context, voiceLauncher::launch) }) {
                    Icon(imageVector = Icons.Rounded.Mic, contentDescription = stringResource(R.string.voice_search_cd))
                }
                FlowConnectedToggleGroup(
                    options = kinds,
                    selected = kind,
                    onSelected = { kind = it },
                    modifier = Modifier.width(KindSelectorWidth),
                )
            }
            if (kind == MediaKind.Music) {
                MusicSearchBody(
                    viewModel = musicViewModel,
                    onTrackClick = onPlayTrack,
                    onAlbumClick = onOpenAlbum,
                    onArtistClick = onOpenArtist,
                    onChannelClick = onOpenChannel,
                    onPlaylistClick = onOpenMusicPlaylist,
                    onMoodClick = onOpenMood,
                    onMoodsSeeAll = onOpenMoods,
                    dismissSearchInput = {},
                    onSuggestionPicked = {},
                )
            } else if (uiState.query.isBlank() || state.isTyping) {
                SearchSuggestionsPanel(
                    query = state.query,
                    history = state.matchingHistory,
                    suggestions = state.suggestions,
                    onSubmit = { suggestion ->
                        text.setTextAndPlaceCursorAtEnd(suggestion)
                        submitVideo(suggestion, uiState.filters)
                    },
                    onHistorySelect = { item ->
                        text.setTextAndPlaceCursorAtEnd(item.query)
                        submitVideo(item.query, item.filters?.availableWith(false) ?: uiState.filters)
                    },
                    onFill = text::setTextAndPlaceCursorAtEnd,
                    onDeleteHistoryItem = state::deleteHistoryItem,
                    onClearHistory = state::clearHistory,
                )
            } else {
                SearchResultsBody(
                    viewModel = viewModel,
                    filters = uiState.filters,
                    shortsEnabled = false,
                    isGridMode = false,
                    feedColumns = HomeFeedColumns.AUTO,
                    pagingItems = pagingItems,
                    gridState = gridState,
                    quickActions = quickActions,
                    onVideoClick = onVideoClick,
                    onChannelClick = onChannelClick,
                    onPlaylistClick = onPlaylistClick,
                    onShortsQueue = {},
                    dismissKeyboard = state::stopTyping,
                    maxAutoColumns = dimens.feedMaxColumns,
                    minCellWidth = dimens.feedCardMinWidth,
                )
            }
        }
    }
}

/** The phone pages under the bar keep a 16 dp margin; the bar lines up with them. */
private val SearchBarHorizontalPadding = 16.dp
