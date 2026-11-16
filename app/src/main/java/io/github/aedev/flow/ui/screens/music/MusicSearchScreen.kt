package io.github.aedev.flow.ui.screens.music

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.SearchType
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.pages.MoodAndGenres
import io.github.aedev.flow.ui.components.music.search.MusicSearchBar
import kotlinx.coroutines.delay

private const val FOCUS_DELAY_MS = 100L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSearchScreen(
    onBackClick: () -> Unit,
    onTrackClick: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onMoodClick: (MoodAndGenres.Item) -> Unit,
    onMoodsSeeAll: () -> Unit,
    initialQuery: String? = null,
    viewModel: MusicSearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    // When opened with a preset query (e.g. from music recognition), run the search instead of auto-focusing.
    LaunchedEffect(Unit) {
        if (!initialQuery.isNullOrBlank()) {
            viewModel.onQueryChange(initialQuery)
            viewModel.performSearch(initialQuery)
            keyboardController?.hide()
        } else {
            delay(FOCUS_DELAY_MS)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    fun dismissSearchInput() {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
    }

    val voiceSearchLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!spokenText.isNullOrBlank()) {
                    viewModel.onQueryChange(spokenText)
                    viewModel.performSearch(spokenText, SearchType.VOICE)
                }
            }
        }

    Scaffold(
        topBar = {
            MusicSearchBar(
                query = query,
                onQueryChange = viewModel::onQueryChange,
                onSearch = {
                    viewModel.performSearch()
                    focusManager.clearFocus(force = true)
                },
                onBackClick = onBackClick,
                onClearClick = viewModel::clearSearch,
                onVoiceSearchClick = {
                    val intent =
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.voice_search_prompt))
                        }
                    voiceSearchLauncher.launch(intent)
                },
                focusRequester = focusRequester,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            MusicSearchBody(
                viewModel = viewModel,
                onTrackClick = onTrackClick,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                onChannelClick = onChannelClick,
                onPlaylistClick = onPlaylistClick,
                onMoodClick = onMoodClick,
                onMoodsSeeAll = onMoodsSeeAll,
                dismissSearchInput = ::dismissSearchInput,
                onSuggestionPicked = { keyboardController?.hide() },
            )
        }
    }
}

// Helper to convert SongItem to MusicTrack (shared with the TV search screen)
internal fun convertSongToMusicTrack(item: SongItem): MusicTrack =
    MusicTrack(
        videoId = item.id,
        title = item.title,
        artist = item.artists.joinToString { it.name },
        thumbnailUrl = item.thumbnail,
        duration = item.duration ?: 0,
        views = 0, // View count text is a string in SongItem
        sourceUrl = "https://www.youtube.com/watch?v=${item.id}",
        album = item.album?.name ?: "Unknown Album",
        channelId = item.artists.firstOrNull()?.id ?: "",
        isExplicit = item.explicit,
        isVideoSong = item.isVideoSong,
        artists = item.artists.map { MusicArtist(it.name, it.id) },
    )
