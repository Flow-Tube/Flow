package io.github.aedev.flow.ui.components.musicplayer.full

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.music.sheet.CrossfadePauseRow
import io.github.aedev.flow.ui.components.music.sheet.MusicArtistsSheet
import io.github.aedev.flow.ui.components.music.sheet.MusicQuickActionsSheet
import io.github.aedev.flow.ui.components.music.sheet.SaveSongSheet
import io.github.aedev.flow.ui.components.musicplayer.sheet.AudioSettingsSheet
import io.github.aedev.flow.ui.screens.music.CrossfadePauseViewModel

/** Which of the full player's own sheets is open. */
@Stable
internal class FullPlayerSheetState {
    var moreOptions by mutableStateOf(false)
    var save by mutableStateOf(false)
    var audioSettings by mutableStateOf(false)
    var sleepTimer by mutableStateOf(false)
    var artists by mutableStateOf(emptyList<MusicArtist>())
}

@Composable
internal fun FullPlayerSheets(
    state: FullPlayerSheetState,
    currentTrack: MusicTrack?,
    isPlayerSheetExpanded: Boolean,
) {
    if (state.save && currentTrack != null) {
        SaveSongSheet(
            track = currentTrack,
            onDismiss = { state.save = false },
        )
    }

    if (state.moreOptions && currentTrack != null) {
        val crossfade: CrossfadePauseViewModel = hiltViewModel()
        val crossfadeOn by crossfade.crossfadeOn.collectAsStateWithLifecycle()
        val crossfadePaused by crossfade.paused.collectAsStateWithLifecycle()
        MusicQuickActionsSheet(
            track = currentTrack,
            onDismiss = { state.moreOptions = false },
            onAudioEffectsClick = { state.audioSettings = true },
            onSleepTimerClick = { state.sleepTimer = true },
            crossfadePause = if (crossfadeOn) CrossfadePauseRow(crossfadePaused, crossfade::toggle) else null,
        )
    }

    if (state.audioSettings) {
        AudioSettingsSheet(
            onDismiss = { state.audioSettings = false },
        )
    }

    // The player stays composed under the mini bar, so the artists sheet closes with it.
    LaunchedEffect(isPlayerSheetExpanded) {
        if (!isPlayerSheetExpanded) state.artists = emptyList()
    }
    if (state.artists.isNotEmpty() && isPlayerSheetExpanded) {
        MusicArtistsSheet(
            artists = state.artists,
            onDismiss = { state.artists = emptyList() },
        )
    }
}
