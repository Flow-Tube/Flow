package io.github.aedev.flow.ui.screens.music

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.LyricsBackgroundStyle
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.localmedia.LocalLyricsReader
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.lyrics.LyricsCandidate
import io.github.aedev.flow.data.lyrics.LyricsHelper
import io.github.aedev.flow.data.lyrics.MusicVideoLyricsTiming
import io.github.aedev.flow.data.music.DownloadManager
import io.github.aedev.flow.data.music.PlaylistRepository
import io.github.aedev.flow.data.music.YouTubeMusicService
import io.github.aedev.flow.data.music.model.MUSIC_GENRE_SOURCE_PREFIX
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.newmusic.InnertubeMusicService
import io.github.aedev.flow.data.newmusic.MusicArtistLookup
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import io.github.aedev.flow.data.recommendation.music.onRepeatShelf
import io.github.aedev.flow.data.scrobble.Scrobbler
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.PlaybackTarget
import io.github.aedev.flow.ui.components.music.sheet.resolveTrackArtists
import io.github.aedev.flow.ui.components.musicplayer.queue.QueueSwipes
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class MusicPlayerViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val playlistRepository: PlaylistRepository,
        private val downloadManager: DownloadManager,
        private val likedVideosRepository: LikedVideosRepository,
        private val viewHistory: ViewHistory,
        private val musicBrain: MusicBrainEngine,
        private val scrobbler: Scrobbler,
        private val lyricsHelper: LyricsHelper,
        private val artistLookup: MusicArtistLookup,
        localLyrics: LocalLyricsReader,
        videoLyricsTiming: MusicVideoLyricsTiming,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(MusicPlayerUiState())
        val uiState: StateFlow<MusicPlayerUiState> = _uiState.asStateFlow()

        /**
         * Playback position is kept out of [MusicPlayerUiState] on purpose. It changes several times a
         * second, and folding it into the screen state made every position tick emit a fresh copy of a
         * 25-field object — invalidating the whole player screen to move a seek bar.
         */
        private val _currentPositionMs = MutableStateFlow(0L)
        val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

        private val playerPreferences = PlayerPreferences(context)

        internal val queueSwipes: StateFlow<QueueSwipes> =
            combine(playerPreferences.queueSwipeLeftAction, playerPreferences.queueSwipeRightAction, ::QueueSwipes)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueSwipes())

        private var isInitialized = false
        private var loadTrackJob: kotlinx.coroutines.Job? = null
        private val seekGuard = MusicSeekGuard()
        private val radioSwitch = MusicRadioSwitch(viewModelScope, _uiState, playerPreferences)
        private val playStart = MusicPlayStart(downloadManager, viewHistory, playerPreferences, playlistRepository)
        private val lyrics =
            MusicPlayerLyrics(
                context,
                viewModelScope,
                _uiState,
                lyricsHelper,
                playerPreferences,
                localLyrics,
                downloadManager::getDownloadedTrackPath,
                videoLyricsTiming,
            )

        init {
            lyrics.publishShownLyrics()
        }

        private val trackActions =
            MusicPlayerTrackActions(
                context,
                viewModelScope,
                _uiState,
                playlistRepository,
                likedVideosRepository,
                downloadManager,
                musicBrain,
                playerPreferences,
                scrobbler,
            )

        init {
            EnhancedMusicPlayerManager.initialize(context, downloadManager::isDownloaded)
            EnhancedMusicPlayerManager.setSkipStoredReader(playStart::skipStored)
            initializeObservers()
            viewModelScope.launch {
                playerPreferences.lyricsTextAlign.collect { align ->
                    _uiState.update { it.copy(lyricsTextAlign = align) }
                }
            }
            radioSwitch.observe()
        }

        private fun initializeObservers() {
            if (isInitialized) return
            isInitialized = true

            viewModelScope.launch {
                EnhancedMusicPlayerManager.playerEvents.collect { event ->
                    when (event) {
                        is EnhancedMusicPlayerManager.PlayerEvent.RequestPlayTrack -> {
                            loadAndPlayTrack(event.track, _uiState.value.queue)
                        }

                        is EnhancedMusicPlayerManager.PlayerEvent.RequestToggleLike -> {
                            toggleLike()
                        }

                        is EnhancedMusicPlayerManager.PlayerEvent.SkippedOnPlayer -> {
                            loadTrackJob?.cancel()
                            _uiState.update { it.copy(isLoading = false, error = null) }
                            viewModelScope.launch(PerformanceDispatcher.diskIO) { playStart.recordSkip(event.track) }
                        }
                    }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.playerState.collect { playerState ->
                    _uiState.update {
                        it.copy(
                            isPlaying = playerState.isPlaying,
                            isBuffering = playerState.isBuffering,
                            duration = playerState.duration,
                        )
                    }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.currentPosition.collect { position ->
                    seekGuard.accept(position)?.let { acceptedPosition ->
                        _currentPositionMs.value = acceptedPosition
                    }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.currentTrack.collect { track ->
                    _uiState.update {
                        it.copy(
                            currentTrack = track,
                            lyrics = null,
                            syncedLyrics = emptyList(),
                            // Fix: Reset duration and position to prevent showing previous track's info
                            duration = if (track != null) track.duration * 1000L else 0L,
                        )
                    }
                    _currentPositionMs.value = 0L
                    track?.let {
                        if (!isLocalMediaId(it.videoId)) {
                            checkIfFavorite(it.videoId)
                            lyrics.onSongChanged(it)
                            fetchRelatedContent(it.videoId)
                        } else {
                            favoriteJob?.cancel()
                            _uiState.update { state -> state.copy(isLiked = false) }
                            EnhancedMusicPlayerManager.setLiked(false)
                            lyrics.onSongChanged(it)
                        }
                    }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.playingFrom.collect { source ->
                    _uiState.update { it.copy(playingFrom = source) }
                }
            }

            viewModelScope.launch {
                downloadManager.downloadedTracks.collect { tracks ->
                    val ids = tracks.map { it.track.videoId }.toSet()
                    _uiState.update { it.copy(downloadedTrackIds = ids) }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.queue.collect { queue ->
                    _uiState.update { it.copy(queue = queue) }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.currentQueueIndex.collect { index ->
                    _uiState.update { it.copy(currentQueueIndex = index) }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.shuffleEnabled.collect { enabled ->
                    _uiState.update { it.copy(shuffleEnabled = enabled) }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.repeatMode.collect { mode ->
                    _uiState.update { it.copy(repeatMode = mode) }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.automixItems.collect { automix ->
                    _uiState.update { it.copy(autoplaySuggestions = automix) }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.radioLoading.collect { loading ->
                    _uiState.update { it.copy(isRadioLoading = loading) }
                }
            }
        }

        private var favoriteJob: Job? = null

        private fun checkIfFavorite(videoId: String) {
            favoriteJob?.cancel()
            favoriteJob =
                viewModelScope.launch {
                    likedVideosRepository.getLikeState(videoId).collect { state ->
                        val isLiked = state == "LIKED"
                        _uiState.update { it.copy(isLiked = isLiked) }
                        EnhancedMusicPlayerManager.setLiked(isLiked)
                    }
                }
        }

        fun playLocalMusic(
            track: MusicTrack,
            queue: List<MusicTrack>,
            localUris: Map<String, Uri>,
        ) {
            loadTrackJob?.cancel()
            loadTrackJob =
                viewModelScope.launch {
                    val activeQueue = if (queue.isNotEmpty()) queue else listOf(track)
                    _uiState.update {
                        it.copy(
                            currentTrack = track,
                            isLoading = false,
                            error = null,
                            playingFrom = context.getString(R.string.local_media_title),
                        )
                    }
                    val startPositionMs = playStart.resumePosition(track)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        EnhancedMusicPlayerManager.playTrack(
                            track = track,
                            audioUrl = localUris[track.videoId]?.toString() ?: "",
                            queue = activeQueue,
                            startPositionMs = startPositionMs,
                            sourceName = context.getString(R.string.local_media_title),
                            localUriOverrides = localUris,
                        )
                    }
                    launch(PerformanceDispatcher.diskIO) { playStart.recordStart(track) }
                }
        }

        private fun isLocalMediaId(id: String?): Boolean = LocalMediaIds.isLocal(id)

        private fun isLoadedInPlayer(videoId: String): Boolean {
            val player = EnhancedMusicPlayerManager.player ?: return false
            val state = player.playbackState
            return EnhancedMusicPlayerManager.currentTrack.value?.videoId == videoId &&
                (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
        }

        fun loadAndPlayTrack(
            track: MusicTrack,
            queue: List<MusicTrack> = emptyList(),
            sourceName: String? = null,
            asRadio: Boolean = false,
        ) {
            // Tapping the song that is already loaded, from any list, keeps it going instead of
            // fetching and restarting it; a paused one resumes. The queue is left as it is.
            if (!asRadio && isLoadedInPlayer(track.videoId)) {
                EnhancedMusicPlayerManager.play()
                return
            }
            if (isLocalMediaId(track.videoId)) {
                // A file on this device plays here, which ends driving another device.
                PlaybackTarget.current?.release()
                val localUris = (queue + track).mapNotNull { t -> LocalMediaIds.audioUri(t.videoId)?.let { t.videoId to it } }.toMap()
                playLocalMusic(track, queue.filter { isLocalMediaId(it.videoId) }, localUris)
                return
            }
            PlaybackTarget.current?.let { remote ->
                val ids = queue.ifEmpty { listOf(track) }.map { it.videoId }
                return remote.play(ids, ids.indexOf(track.videoId).coerceAtLeast(0), music = true)
            }
            loadTrackJob?.cancel()
            // Genre-scoped surfaces tag their source; the genre becomes listen
            // context for this queue and is stripped from the display label.
            // Any non-tagged queue start clears the previous context.
            val contextGenre =
                sourceName
                    ?.trim()
                    ?.takeIf { it.startsWith(MUSIC_GENRE_SOURCE_PREFIX) }
                    ?.removePrefix(MUSIC_GENRE_SOURCE_PREFIX)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            EnhancedMusicPlayerManager.playContextGenre = contextGenre
            val displaySourceName = contextGenre ?: sourceName
            loadTrackJob =
                viewModelScope.launch {
                    val finalSourceName = musicSourceLabel(context, displaySourceName, track)
                    val activeQueue = if (queue.isNotEmpty()) queue else listOf(track)
                    val localUriOverrides = playStart.downloadedUris(activeQueue)
                    val startPositionMs = playStart.resumePosition(track)

                    // ─── PHASE 1: Instant start ───────────────────────────────────────────
                    _uiState.update {
                        it.copy(
                            currentTrack = track,
                            isLoading = true,
                            error = null,
                            playingFrom = finalSourceName,
                        )
                    }

                    // Flagged as late as possible: the service consumes the seed on the next
                    // playlist change, and an unrelated advance during the lookup above would
                    // otherwise eat it.
                    EnhancedMusicPlayerManager.pendingRadioSeedId = track.videoId.takeIf { asRadio }

                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        EnhancedMusicPlayerManager.playTrack(
                            track = track,
                            audioUrl = "music://${track.videoId}",
                            queue = activeQueue,
                            startPositionMs = startPositionMs,
                            sourceName = finalSourceName,
                            localUriOverrides = localUriOverrides,
                        )
                    }

                    // Player is now buffering — clear loading indicator so artwork etc. show
                    _uiState.update { it.copy(isLoading = false) }

                    // ─── PHASE 2: Background — does NOT block audio ───────────────────────
                    supervisorScope {
                        launch(PerformanceDispatcher.networkIO) {
                            if (!localUriOverrides.containsKey(track.videoId) && !downloadManager.isCachedForOffline(track.videoId)) {
                                EnhancedMusicPlayerManager.resolveStreamUrl(track.videoId)
                            }
                        }

                        launch(PerformanceDispatcher.diskIO) {
                            playlistRepository.addToHistory(track)
                            playStart.recordStart(track)
                        }

                        launch(PerformanceDispatcher.networkIO) {
                            fetchRelatedContent(track.videoId)
                        }
                        // Single-track queues need no special automix fill: the service
                        // seeds the radio pool for every new queue context.
                    }
                }
        }

        /** The song's credited artists, looked up once when the track kept only the first one. */
        suspend fun resolveArtists(track: MusicTrack): List<MusicArtist> = resolveTrackArtists(track, artistLookup)

        /** A YouTube Music link names only the song, so its details are fetched before it plays. */
        suspend fun playFromLink(videoId: String) {
            val track =
                InnertubeMusicService.fetchQueue(videoIds = listOf(videoId)).firstOrNull()
                    ?: MusicTrack(videoId = videoId, title = "", artist = "", thumbnailUrl = "", duration = 0)
            loadAndPlayTrack(track)
        }

        /** Plays a watch playlist such as an artist's shuffle or mix; false when YouTube returned no songs. */
        suspend fun playWatchPlaylist(
            playlistId: String,
            params: String?,
        ): Boolean {
            val tracks = withTimeoutOrNull(10_000L) { InnertubeMusicService.fetchWatchPlaylist(playlistId, params) }.orEmpty()
            loadAndPlayTrack(tracks.firstOrNull() ?: return false, tracks)
            return true
        }

        /** Plays the On Repeat shelf in a shuffled order, as the widget's Shuffle asks. */
        suspend fun shuffleOnRepeat(sourceName: String) {
            val tracks = musicBrain.onRepeatShelf().shuffled()
            loadAndPlayTrack(tracks.firstOrNull() ?: return, tracks, sourceName)
        }

        /**
         * Starts a station seeded from this track alone. The seed is flagged for the service,
         * which would otherwise read a track taken from the playing queue as an in-queue skip
         * and leave the previous station running.
         */
        fun startRadio(track: MusicTrack) {
            loadAndPlayTrack(track, asRadio = true)
        }

        /** Local files have no InnerTube seed, so no station can be built from one. */
        fun canStartRadio(track: MusicTrack): Boolean = !isLocalMediaId(track.videoId)

        fun togglePlayPause() {
            EnhancedMusicPlayerManager.togglePlayPause()
        }

        fun play() {
            EnhancedMusicPlayerManager.play()
        }

        fun pause() {
            EnhancedMusicPlayerManager.pause()
        }

        fun moveTrack(
            fromIndex: Int,
            toIndex: Int,
        ) {
            EnhancedMusicPlayerManager.moveMediaItem(fromIndex, toIndex)
        }

        fun playNextFromQueuePosition(index: Int) {
            val current = _uiState.value.currentQueueIndex
            if (index == current) return
            val target = if (index > current) current + 1 else current
            if (index != target) EnhancedMusicPlayerManager.moveMediaItem(index, target)
        }

        fun removeFromQueue(index: Int): EnhancedMusicPlayerManager.RemovedQueueTrack? = EnhancedMusicPlayerManager.removeFromQueue(index)

        fun moveQueueTrackToEnd(index: Int) {
            val lastIndex = _uiState.value.queue.size - 1
            if (index in 0 until lastIndex) EnhancedMusicPlayerManager.moveMediaItem(index, lastIndex)
        }

        fun playNextFromRadio(track: MusicTrack) {
            EnhancedMusicPlayerManager.playNext(track)
            EnhancedMusicPlayerManager.removeAutomixItem(track.videoId)
        }

        fun addRadioTrackToQueue(track: MusicTrack) {
            EnhancedMusicPlayerManager.addToQueue(track)
            EnhancedMusicPlayerManager.removeAutomixItem(track.videoId)
        }

        /**
         * Plays a suggestion by taking it into the queue and jumping to it. Loading it as a track
         * would replace the whole queue with that one song — the sheet is showing what comes next,
         * not an invitation to throw away what the user lined up.
         */
        fun playRadioTrack(track: MusicTrack) {
            addRadioTrackToQueue(track)
            val index = EnhancedMusicPlayerManager.queue.value.indexOfFirst { it.videoId == track.videoId }
            if (index >= 0) EnhancedMusicPlayerManager.playFromQueue(index) else loadAndPlayTrack(track)
        }

        fun setEndlessRadioEnabled(enabled: Boolean) = radioSwitch.set(enabled)

        fun seekTo(position: Long) {
            val duration =
                _uiState.value.duration.takeIf { it > 0 }
                    ?: EnhancedMusicPlayerManager.getDuration().takeIf { it > 0 }
            val target = duration?.let { position.coerceIn(0L, it) } ?: position.coerceAtLeast(0L)
            seekGuard.seekRequested(target)
            EnhancedMusicPlayerManager.seekTo(target)
            _currentPositionMs.value = target
        }

        fun skipToNext() {
            EnhancedMusicPlayerManager.playNext()
        }

        fun skipToPrevious() {
            EnhancedMusicPlayerManager.playPrevious()
        }

        fun playFromQueue(index: Int) {
            EnhancedMusicPlayerManager.playFromQueue(index)
        }

        // Both the currentTrack collector and the (kept-composed) player content request
        // related tracks for the same id; without this guard every advance fetched twice.
        private var relatedFetchedForId: String? = null

        fun fetchRelatedContent(videoId: String) {
            if (relatedFetchedForId == videoId) return
            relatedFetchedForId = videoId
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                _uiState.update { it.copy(isRelatedLoading = true) }
                try {
                    val related =
                        withTimeoutOrNull(10_000L) {
                            YouTubeMusicService.getRelatedMusic(videoId, 20)
                        } ?: emptyList()
                    if (related.isEmpty()) relatedFetchedForId = null

                    // Related content is display-only here: the radio pool (automix)
                    // is owned by Media3MusicService and must not churn per track.
                    _uiState.update {
                        it.copy(
                            relatedContent = related,
                            isRelatedLoading = false,
                        )
                    }
                } catch (e: Exception) {
                    relatedFetchedForId = null
                    _uiState.update { it.copy(isRelatedLoading = false) }
                }
            }
        }

        fun toggleShuffle() {
            EnhancedMusicPlayerManager.toggleShuffle()
        }

        fun toggleRepeat() {
            EnhancedMusicPlayerManager.toggleRepeat()
        }

        fun toggleLike() = trackActions.toggleLike()

        fun notInterested(track: MusicTrack) = trackActions.notInterested(track)

        fun dontRecommendArtist(track: MusicTrack) = trackActions.dontRecommendArtist(track)

        fun playNext(track: MusicTrack) = trackActions.playNext(track)

        fun addToQueue(track: MusicTrack) = trackActions.addToQueue(track)

        fun playNext(tracks: List<MusicTrack>) = trackActions.playNext(tracks)

        fun addToQueue(tracks: List<MusicTrack>) = trackActions.addToQueue(tracks)

        fun downloadTrack(track: MusicTrack? = null) = trackActions.downloadTrack(track)

        fun ensureLyricsLoaded(track: MusicTrack) = lyrics.ensureLoaded(track)

        fun warmLyrics(track: MusicTrack) = lyrics.warm(track)

        fun setPlayerOpen(open: Boolean) = lyrics.setPlayerOpen(open)

        fun refreshLyrics() = lyrics.refresh()

        fun browseLyricsCandidates() = lyrics.browseCandidates()

        fun cancelLyricsBrowse() = lyrics.cancelBrowse()

        fun applyLyricsCandidate(candidate: LyricsCandidate) = lyrics.applyCandidate(candidate)

        fun applyEditedLyrics(text: String) = lyrics.applyEdited(text)

        fun adjustLyricsSyncOffset(deltaMs: Long) = lyrics.adjustSyncOffset(deltaMs)

        fun resetLyricsSyncOffset() = lyrics.resetSyncOffset()

        fun setLyricsTextAlign(align: String) = lyrics.setTextAlign(align)

        fun setLyricsShowTranslation(show: Boolean) = lyrics.setShowTranslation(show)

        fun setLyricsShowRomanization(show: Boolean) = lyrics.setShowRomanization(show)

        fun setLyricsAutoRomanize(enabled: Boolean) = lyrics.setAutoRomanize(enabled)

        fun setLyricsRomanizationOnly(enabled: Boolean) = lyrics.setRomanizationOnly(enabled)

        fun setLyricsKeepScreenOn(enabled: Boolean) = lyrics.setKeepScreenOn(enabled)

        fun setLyricsBlurOtherLines(enabled: Boolean) = lyrics.setBlurOtherLines(enabled)

        fun setLyricsBackground(style: LyricsBackgroundStyle) = lyrics.setBackground(style)

        override fun onCleared() {
            super.onCleared()
        }
    }
