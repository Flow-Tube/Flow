package io.github.aedev.flow.ui.screens.music

import android.content.Context
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.localmedia.LocalLyricsReader
import io.github.aedev.flow.data.localmedia.LocalLyricsSource
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.localmedia.lrcOffsetMs
import io.github.aedev.flow.data.localmedia.plainLyricsText
import io.github.aedev.flow.data.lyrics.LyricsCandidate
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.data.lyrics.LyricsHelper
import io.github.aedev.flow.data.lyrics.LyricsUtils
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.utils.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The music player's lyrics: fetching for each track, refreshing, browsing other sources, manual
 * edits and timing. Everything it learns is written into the player's shared ui state.
 */
internal class MusicPlayerLyrics(
    private val context: Context,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MusicPlayerUiState>,
    private val lyricsHelper: LyricsHelper,
    private val playerPreferences: PlayerPreferences,
    private val localLyrics: LocalLyricsReader,
    private val downloadedTrackPath: suspend (videoId: String) -> String?,
) {
    private var lyricsJob: kotlinx.coroutines.Job? = null

    init {
        scope.launch {
            playerPreferences.lyricsShowTranslation.collect { show ->
                uiState.update { it.copy(lyricsShowTranslation = show) }
            }
        }
        scope.launch {
            playerPreferences.lyricsShowRomanization.collect { show ->
                uiState.update { it.copy(lyricsShowRomanization = show) }
            }
        }
        scope.launch { playerPreferences.lyricsAutoRomanize.collect { on -> uiState.update { it.copy(lyricsAutoRomanize = on) } } }
        scope.launch { playerPreferences.lyricsKeepScreenOn.collect { on -> uiState.update { it.copy(lyricsKeepScreenOn = on) } } }
        scope.launch { playerPreferences.lyricsBlurOtherLines.collect { on -> uiState.update { it.copy(lyricsBlurOtherLines = on) } } }
    }

    fun setShowTranslation(show: Boolean) {
        scope.launch { playerPreferences.setLyricsShowTranslation(show) }
    }

    fun setShowRomanization(show: Boolean) {
        scope.launch { playerPreferences.setLyricsShowRomanization(show) }
    }

    fun setAutoRomanize(enabled: Boolean) {
        scope.launch { playerPreferences.setLyricsAutoRomanize(enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        scope.launch { playerPreferences.setLyricsKeepScreenOn(enabled) }
    }

    fun setBlurOtherLines(enabled: Boolean) {
        scope.launch { playerPreferences.setLyricsBlurOtherLines(enabled) }
    }

    private fun cleanName(name: String): String =
        name
            .replace(Regex("(?i)\\s*-\\s*topic$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)\\s*[(\\[]official (audio|video|music video|lyric video)[)\\]]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)\\s*[(\\[]lyrics?[)\\]]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)\\s*[(]feat\\.? .*?[)]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?i)\\s*[\\[]feat\\.? .*?[\\]]", RegexOption.IGNORE_CASE), "")
            .trim()

    fun fetch(
        videoId: String,
        artist: String,
        title: String,
        duration: Int? = null,
        album: String? = null,
    ) {
        lyricsJob?.cancel()
        if (LocalMediaIds.isLocal(videoId)) {
            lyricsJob = scope.launch { loadDeviceLyrics(videoId) }
            return
        }
        lyricsJob =
            scope.launch {
                beginLoading()

                val cleanArtist = cleanName(artist)
                val cleanTitle = cleanName(title)
                val targetDuration = duration ?: (uiState.value.duration.toInt() / 1000)

                try {
                    // A downloaded song carries the lyrics it was saved with; offline they come first.
                    val result =
                        if (NetworkState.isOnline(context)) {
                            providerLyrics(videoId, cleanTitle, cleanArtist, targetDuration, album) ?: downloadedLyrics(videoId)
                        } else {
                            downloadedLyrics(videoId) ?: providerLyrics(videoId, cleanTitle, cleanArtist, targetDuration, album)
                        }

                    if (result != null) {
                        val (entries, providerName) = result
                        val hasWords = entries.any { it.words != null }
                        val isSynced = lyricsHelper.entriesAreSynced(entries)
                        android.util.Log.d(
                            "MusicPlayerViewModel",
                            "Got ${entries.size} lyrics lines from $providerName (word-sync=$hasWords, synced=$isSynced)",
                        )

                        val plainText = entries.joinToString("\n") { it.text }
                        uiState.update {
                            it.copy(
                                isLyricsLoading = false,
                                lyrics = plainText.takeIf { it.isNotBlank() },
                                syncedLyrics = if (isSynced) entries else emptyList(),
                                lyricsProviderName = providerName,
                            )
                        }
                    } else {
                        uiState.update { it.copy(isLyricsLoading = false) }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MusicPlayerViewModel", "Lyrics fetch failed", e)
                    uiState.update { it.copy(isLyricsLoading = false) }
                }
            }
    }

    private suspend fun providerLyrics(
        videoId: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Pair<List<LyricsEntry>, String>? =
        try {
            lyricsHelper.getLyrics(videoId, title, artist, duration, album)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("MusicPlayerViewModel", "Lyrics providers failed: ${e.message}")
            null
        }

    private suspend fun downloadedLyrics(videoId: String): Pair<List<LyricsEntry>, String>? {
        val path = downloadedTrackPath(videoId) ?: return null
        val found = localLyrics.readDownload(path) ?: return null
        val entries =
            withContext(kotlinx.coroutines.Dispatchers.Default) {
                LyricsUtils.parseLyrics(found.text).ifEmpty {
                    plainLyricsText(found.text)
                        .lines()
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .map { LyricsEntry(0L, it) }
                }
            }
        return entries.takeIf { it.isNotEmpty() }?.let { it to context.getString(R.string.lyrics_source_embedded) }
    }

    private fun beginLoading() {
        uiState.update {
            it.copy(
                isLyricsLoading = true,
                lyrics = null,
                syncedLyrics = emptyList(),
                lyricsProviderName = "",
                lyricsSyncOffsetMs = 0L,
                lyricsCandidates = emptyList(),
            )
        }
    }

    /** A device song only ever reads its own `.lrc` or embedded lyrics; no provider is asked. */
    private suspend fun loadDeviceLyrics(videoId: String) {
        beginLoading()
        val found =
            try {
                localLyrics.read(videoId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MusicPlayerViewModel", "Device lyrics failed: ${e.message}")
                null
            }
        if (found == null) {
            uiState.update { it.copy(isLyricsLoading = false) }
            return
        }
        val entries = withContext(kotlinx.coroutines.Dispatchers.Default) { LyricsUtils.parseLyrics(found.text) }
        val synced = lyricsHelper.entriesAreSynced(entries)
        val text = if (synced) entries.joinToString("\n") { it.text } else plainLyricsText(found.text)
        uiState.update {
            it.copy(
                isLyricsLoading = false,
                lyrics = text.takeIf(String::isNotBlank),
                syncedLyrics = if (synced) entries else emptyList(),
                lyricsProviderName =
                    context.getString(
                        if (found.source == LocalLyricsSource.FILE) R.string.lyrics_source_local_file else R.string.lyrics_source_embedded,
                    ),
                lyricsSyncOffsetMs = if (synced) lrcOffsetMs(found.text) else 0L,
            )
        }
    }

    /**
     * Called when the player screen opens for a track that is ALREADY playing in
     * EnhancedMusicPlayerManager (same videoId). In that case, the currentTrack
     * StateFlow doesn't re-emit, so fetch is never triggered automatically.
     *
     * - If lyrics are already loaded for this track, does nothing (cache hit).
     * - Otherwise fetches lyrics as normal.
     */
    fun ensureLoaded(track: MusicTrack) {
        val state = uiState.value
        if (state.isLyricsLoading) return
        if (!state.syncedLyrics.isNullOrEmpty()) return
        if (!state.lyrics.isNullOrEmpty()) return
        fetch(
            videoId = track.videoId,
            artist = track.artist,
            title = track.title,
            duration = track.duration,
            album = track.album,
        )
    }

    fun refresh() {
        val track = uiState.value.currentTrack ?: return
        scope.launch {
            try {
                lyricsHelper.forceRefresh(track.videoId)
            } catch (e: Exception) {
                android.util.Log.w("MusicPlayerViewModel", "forceRefresh failed: ${e.message}")
            }
            fetch(
                videoId = track.videoId,
                artist = track.artist,
                title = track.title,
                duration = track.duration,
                album = track.album,
            )
        }
    }

    private var browseLyricsJob: kotlinx.coroutines.Job? = null

    fun browseCandidates() {
        val track = uiState.value.currentTrack ?: return
        if (LocalMediaIds.isLocal(track.videoId)) return
        browseLyricsJob?.cancel()
        browseLyricsJob =
            scope.launch {
                uiState.update { it.copy(isBrowsingLyrics = true, lyricsCandidates = emptyList()) }
                try {
                    lyricsHelper.getAllLyrics(
                        videoId = track.videoId,
                        title = cleanName(track.title),
                        artist = cleanName(track.artist),
                        duration = track.duration,
                        album = track.album,
                    ) { candidate ->
                        if (isActive) {
                            uiState.update { it.copy(lyricsCandidates = it.lyricsCandidates + candidate) }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("MusicPlayerViewModel", "Lyrics browse failed: ${e.message}")
                } finally {
                    // cancel() does not wait: a superseded browse's finally can run after the
                    // replacement already set isBrowsingLyrics = true. Only the job that is
                    // still current may clear the flag.
                    if (browseLyricsJob === coroutineContext[kotlinx.coroutines.Job]) {
                        uiState.update { it.copy(isBrowsingLyrics = false) }
                    }
                }
            }
    }

    fun cancelBrowse() {
        browseLyricsJob?.cancel()
        uiState.update { it.copy(isBrowsingLyrics = false) }
    }

    fun applyCandidate(candidate: LyricsCandidate) {
        val track = uiState.value.currentTrack ?: return
        scope.launch {
            lyricsHelper.applyManualLyrics(track.videoId, candidate.entries)
            val plainText = candidate.entries.joinToString("\n") { it.text }
            uiState.update {
                it.copy(
                    lyrics = plainText.takeIf { text -> text.isNotBlank() },
                    syncedLyrics = if (candidate.synced) candidate.entries else emptyList(),
                    lyricsProviderName = candidate.providerName,
                    lyricsSyncOffsetMs = 0L,
                )
            }
        }
    }

    fun applyEdited(text: String) {
        val track = uiState.value.currentTrack ?: return
        scope.launch {
            val parsed =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    io.github.aedev.flow.data.lyrics.LyricsUtils
                        .parseLyrics(text)
                }
            val entries =
                parsed.ifEmpty {
                    text
                        .lines()
                        .map { line -> line.trim() }
                        .filter { line -> line.isNotBlank() }
                        .map { line -> LyricsEntry(0L, line) }
                }
            if (entries.isEmpty()) return@launch
            val synced = lyricsHelper.entriesAreSynced(entries)
            lyricsHelper.applyManualLyrics(track.videoId, entries)
            val plainText = entries.joinToString("\n") { it.text }
            uiState.update {
                it.copy(
                    lyrics = plainText.takeIf { t -> t.isNotBlank() },
                    syncedLyrics = if (synced) entries else emptyList(),
                    lyricsProviderName = context.getString(io.github.aedev.flow.R.string.lyrics_source_edited),
                )
            }
        }
    }

    fun adjustSyncOffset(deltaMs: Long) {
        uiState.update {
            it.copy(lyricsSyncOffsetMs = (it.lyricsSyncOffsetMs + deltaMs).coerceIn(-30_000L, 30_000L))
        }
    }

    fun resetSyncOffset() {
        uiState.update { it.copy(lyricsSyncOffsetMs = 0L) }
    }

    fun setTextAlign(align: String) {
        scope.launch { playerPreferences.setLyricsTextAlign(align) }
    }
}
