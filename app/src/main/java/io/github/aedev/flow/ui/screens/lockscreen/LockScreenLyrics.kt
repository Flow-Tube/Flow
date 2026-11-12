package io.github.aedev.flow.ui.screens.lockscreen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.data.lyrics.LyricsHelper
import io.github.aedev.flow.data.lyrics.LyricsUtils
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.ui.components.musicplayer.lyrics.SYNCED_LINE_UNSUNG_ALPHA
import io.github.aedev.flow.ui.components.musicplayer.lyrics.SyncedLines
import io.github.aedev.flow.ui.components.musicplayer.lyrics.SyncedLyricLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** A song's synced lines for the lock screen player, with the offset the user set for it. */
@Immutable
internal class LockScreenLyrics(
    val videoId: String,
    val lines: List<LyricsEntry>,
    val offsetMs: Long,
)

/**
 * The current song's synced lyrics, looked up only while the lock screen player is on screen: it is
 * collected with the activity's lifecycle, and lyrics the music player already shows are reused.
 */
@HiltViewModel
internal class LockScreenLyricsViewModel
    @Inject
    constructor(
        private val lyricsHelper: LyricsHelper,
    ) : ViewModel() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val lyrics: StateFlow<LockScreenLyrics?> =
            combine(EnhancedMusicPlayerManager.currentTrack, lyricsHelper.shown) { track, shown ->
                track to shown?.takeIf { track != null && it.videoId == track.videoId && it.entries.isNotEmpty() }
            }.distinctUntilChanged()
                .flatMapLatest { (track, shown) ->
                    when {
                        track == null -> {
                            flowOf(null)
                        }

                        shown != null -> {
                            flowOf(LockScreenLyrics(track.videoId, syncedLines(shown.entries), shown.syncOffsetMs))
                        }

                        else -> {
                            flow {
                                emit(null)
                                emit(lookUp(track))
                            }
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        private suspend fun lookUp(track: MusicTrack): LockScreenLyrics? {
            if (LocalMediaIds.isLocal(track.videoId)) return null
            val answer =
                runCatching {
                    lyricsHelper.getLyrics(track.videoId, track.title, track.artist, track.duration, track.album.ifBlank { null })
                }.getOrNull() ?: return null
            val entries = answer.first.takeIf(lyricsHelper::entriesAreSynced) ?: return null
            return LockScreenLyrics(track.videoId, syncedLines(entries), lyricsHelper.syncOffset(track.videoId) ?: 0L)
        }

        private fun syncedLines(entries: List<LyricsEntry>): List<LyricsEntry> =
            LyricsUtils.filterCreditLines(entries).filterNot { it.isBackground || it.text.isBlank() }.sortedBy { it.time }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/**
 * The line being sung, filled word by word like the music player's lyrics. [positionKey] moves with the
 * player's once-a-second position, so a seek is picked up within a second.
 */
@Composable
internal fun LockScreenLyricLine(
    lyrics: LockScreenLyrics,
    isPlaying: Boolean,
    positionKey: Long,
    modifier: Modifier = Modifier,
) {
    val synced = remember(lyrics) { SyncedLines(lyrics.lines, lyrics.offsetMs) }
    val style =
        MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
    val sung = MaterialTheme.colorScheme.onSurface
    val unsung = sung.copy(alpha = SYNCED_LINE_UNSUNG_ALPHA)
    SyncedLyricLine(
        lyrics = synced,
        isPlaying = isPlaying,
        positionKey = positionKey,
        style = style,
        sungColor = sung,
        unsungColor = unsung,
        modifier = modifier.fillMaxWidth().heightIn(min = LyricsRowMinHeight),
        empty = { Text(text = MUSIC_NOTE, style = style, color = unsung) },
    )
}

private const val MUSIC_NOTE = "♪"

/** Room for a line that wraps once, so a long line doesn't move the controls. */
private val LyricsRowMinHeight = 64.dp
