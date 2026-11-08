package io.github.aedev.flow.ui.screens.lockscreen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.data.lyrics.LyricsHelper
import io.github.aedev.flow.data.lyrics.LyricsUtils
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.ui.components.musicplayer.lyrics.KaraokeLine
import io.github.aedev.flow.ui.components.musicplayer.lyrics.rememberLyricsMotionEnabled
import io.github.aedev.flow.ui.components.musicplayer.lyrics.sanitizeWordTimestamps
import io.github.aedev.flow.ui.components.musicplayer.lyrics.splitTrailingHyphenWord
import io.github.aedev.flow.ui.components.musicplayer.lyrics.synthesizeWordTimings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
 * The line being sung, filled word by word like the music player's lyrics. It wakes only when the line
 * changes; [positionKey] moves with the player's once-a-second position, so a seek is picked up within a
 * second. The word fill runs only while the lock screen player is on screen and music plays.
 */
@Composable
internal fun LockScreenLyricLine(
    lyrics: LockScreenLyrics,
    isPlaying: Boolean,
    positionKey: Long,
    modifier: Modifier = Modifier,
) {
    var index by remember(lyrics) { mutableIntStateOf(-1) }
    LaunchedEffect(lyrics, isPlaying, positionKey) {
        while (true) {
            val lyricTime = EnhancedMusicPlayerManager.getCurrentPosition() + lyrics.offsetMs
            index = LyricsUtils.findCurrentLineIndex(lyrics.lines, lyricTime)
            if (!isPlaying) break
            val next = lyrics.lines.getOrNull(index + 1)?.time ?: break
            delay((next - LINE_LEAD_MS - lyricTime).coerceAtLeast(MIN_WAIT_MS))
        }
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val shown = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val motionEnabled = rememberLyricsMotionEnabled()
    val style =
        MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
    val sung = MaterialTheme.colorScheme.onSurface
    val unsung = sung.copy(alpha = UNSUNG_ALPHA)
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedContent(
        targetState = index,
        transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
        modifier = modifier.fillMaxWidth().heightIn(min = LyricsRowMinHeight),
        contentAlignment = Alignment.BottomStart,
        label = "lockScreenLyricLine",
    ) { shownIndex ->
        val line = lyrics.lines.getOrNull(shownIndex)
        if (line == null) {
            Text(text = MUSIC_NOTE, style = style, color = unsung)
            return@AnimatedContent
        }
        val nextTime = lyrics.lines.getOrNull(shownIndex + 1)?.time
        val words =
            remember(line, nextTime) {
                line.words
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { splitTrailingHyphenWord(sanitizeWordTimestamps(it)).first }
                    ?: synthesizeWordTimings(line.text, line.time, nextTime)
            }
        KaraokeLine(
            text = line.text,
            words = words,
            style = style,
            sungColor = sung,
            unsungColor = unsung,
            isCurrent = true,
            isPlaying = isPlaying && shown,
            isBackground = false,
            syncOffsetMs = lyrics.offsetMs,
            positionProvider = EnhancedMusicPlayerManager::getCurrentPosition,
            motionEnabled = motionEnabled,
        )
    }
}

/** [LyricsUtils.findCurrentLineIndex] moves to a line this early, so the wait ends when it does. */
private const val LINE_LEAD_MS = 300L
private const val MIN_WAIT_MS = 50L
private const val MUSIC_NOTE = "♪"
private const val UNSUNG_ALPHA = 0.45f

/** Room for a line that wraps once, so a long line doesn't move the controls. */
private val LyricsRowMinHeight = 64.dp
