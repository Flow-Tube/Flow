package io.github.aedev.flow.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.AudioSettingsPersistence
import io.github.aedev.flow.data.local.QueuePersistence
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.error.PlayerDiagnostics
import io.github.aedev.flow.player.musicvideo.MusicVideoItems
import io.github.aedev.flow.player.musicvideo.MusicVideoPlanner
import io.github.aedev.flow.service.Media3MusicService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.stream.AudioStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import kotlin.math.pow

@OptIn(UnstableApi::class)
object EnhancedMusicPlayerManager {
    var player: Player? = null
        private set

    /**
     * Genre/mood of the surface the current queue was started from, or null for
     * unscoped queues. Stamped into listen signals by the music service so the
     * brain learns time-of-day × genre context.
     */
    @Volatile
    var playContextGenre: String? = null

    /**
     * Video id of a radio the user asked for by name. The seed is usually already in the playing
     * queue, which the service reads as the same session, so the request has to travel as its own
     * signal. Consumed by the service on the next queue context change.
     */
    @Volatile
    var pendingRadioSeedId: String? = null

    private val _radioLoading = MutableStateFlow(false)

    /** True while the service is seeding or topping up the station, for the queue sheet's spinner. */
    val radioLoading: StateFlow<Boolean> = _radioLoading.asStateFlow()

    fun setRadioLoading(loading: Boolean) {
        _radioLoading.value = loading
    }

    private val _radioStationActive = MutableStateFlow(false)

    /** A radio the user started by name: it outruns the endless-radio setting, for this session only. */
    val radioStationActive: StateFlow<Boolean> = _radioStationActive.asStateFlow()

    fun setRadioStationActive(active: Boolean) {
        _radioStationActive.value = active
    }

    private var appContext: Context? = null

    private val _playerInstance = MutableStateFlow<Player?>(null)
    val playerInstance: StateFlow<Player?> = _playerInstance.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var isInitialized = false
    private val exceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            Log.e("EnhancedMusicPlayer", "Error in player scope: ${throwable.message}", throwable)
        }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    private var retryCount = 0
    private const val MAX_RETRIES = 3
    private var positionUpdateJob: kotlinx.coroutines.Job? = null

    // Persistence
    private var queuePersistence: QueuePersistence? = null

    /** The restored track and where it stopped, used once by the next play of that same track. */
    private var resumePosition: Pair<String, Long>? = null

    /** A song started before the controller connected, such as a link opening the app; sent on connect. */
    private var playBeforeConnect: (() -> Unit)? = null

    /** Counts started queues, so a saved queue read meanwhile knows a newer one replaced it. */
    private var queueStarts = 0
    private var audioSettingsPersistence: AudioSettingsPersistence? = null

    // Audio Settings State
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _playbackPitch = MutableStateFlow(0.0f)
    val playbackPitch: StateFlow<Float> = _playbackPitch.asStateFlow()

    // Player state flows
    private val _playerState = MutableStateFlow(MusicPlayerState())
    val playerState: StateFlow<MusicPlayerState> = _playerState.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    /**
     * Number of consumers that need sub-second progress — in practice only the expanded music
     * sheet's seek bar. Everything else (mini player, notification, saved position, elapsed-time
     * label) renders whole seconds, so [playerState] stays on a 1 Hz cadence regardless.
     *
     * Only ever touched from the main thread: the position loop runs on [scope] (Main) and the
     * callers are Compose effects.
     */
    private var preciseProgressConsumers = 0

    fun acquirePreciseProgress() {
        preciseProgressConsumers++
    }

    fun releasePreciseProgress() {
        preciseProgressConsumers = (preciseProgressConsumers - 1).coerceAtLeast(0)
    }

    // Events
    sealed class PlayerEvent {
        data class RequestPlayTrack(
            val track: MusicTrack,
        ) : PlayerEvent()

        object RequestToggleLike : PlayerEvent()
    }

    private val _playerEvents = MutableSharedFlow<PlayerEvent>()
    val playerEvents: SharedFlow<PlayerEvent> = _playerEvents.asSharedFlow()

    private val _playbackWarnings = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playbackWarnings: SharedFlow<String> = _playbackWarnings.asSharedFlow()

    // Queue
    private val _queue = MutableStateFlow<List<MusicTrack>>(emptyList())
    val queue: StateFlow<List<MusicTrack>> = _queue.asStateFlow()

    private val _automixItems = MutableStateFlow<List<MusicTrack>>(emptyList())
    val automixItems: StateFlow<List<MusicTrack>> = _automixItems.asStateFlow()

    private val _currentQueueIndex = MutableStateFlow(0)
    val currentQueueIndex: StateFlow<Int> = _currentQueueIndex.asStateFlow()

    private val _currentTrack = MutableStateFlow<MusicTrack?>(null)
    val currentTrack: StateFlow<MusicTrack?> = _currentTrack.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _playingFrom = MutableStateFlow("Flow Music")
    val playingFrom: StateFlow<String> = _playingFrom.asStateFlow()

    private val _isLiked = MutableStateFlow(false)
    val isLiked: StateFlow<Boolean> = _isLiked.asStateFlow()

    // OPTIMIZED: LRU Cache for resolved stream URLs to avoid re-fetching
    private val urlCache = android.util.LruCache<String, String>(50)
    private var pendingPlayNextMediaId: String? = null
    private var pendingPlayNextMediaIndex: Int = MusicQueuePlanner.INDEX_UNSET

    // OPTIMIZED: Pre-fetch next track to reduce gap
    private fun prefetchNextTrack() {
        val queue = _queue.value
        val idx = currentPlaybackQueueIndex()

        if (idx != -1 && idx < queue.size - 1) {
            val nextTrack = queue[idx + 1]
            if (!LocalMediaIds.isLocal(nextTrack.videoId) && urlCache.get(nextTrack.videoId) == null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        if (isDownloaded(nextTrack.videoId)) return@launch
                        resolveStreamUrl(nextTrack.videoId)
                        Log.d("EnhancedMusicPlayer", "Pre-fetched URL for next track: ${nextTrack.title}")
                    } catch (e: Exception) {
                        Log.e("EnhancedMusicPlayer", "Failed to pre-fetch next track", e)
                    }
                }
            }
        }
    }

    /**
     * Resolves stream URL with caching
     */
    suspend fun resolveStreamUrl(videoId: String): String? {
        // 1. Check cache
        urlCache.get(videoId)?.let {
            Log.d("EnhancedMusicPlayer", "Cache hit for $videoId")
            return it
        }

        // 2. Resolve (using MusicPlayerUtils)
        return try {
            val playbackData =
                io.github.aedev.flow.utils.MusicPlayerUtils
                    .playerResponseForPlayback(videoId)
                    .getOrNull()
            val url = playbackData?.streamUrl

            if (url != null) {
                urlCache.put(videoId, url)
            }
            url
        } catch (e: Exception) {
            Log.e("EnhancedMusicPlayer", "Error resolving URL for $videoId", e)
            null
        }
    }

    fun invalidateResolvedStream(videoId: String) {
        urlCache.remove(videoId)
        Log.d("EnhancedMusicPlayer", "Invalidated resolved URL cache for $videoId")
    }

    fun clearUrlCache() {
        urlCache.evictAll()
        Log.d("EnhancedMusicPlayer", "Cleared all resolved URL cache entries")
    }

    /** Whether a song plays from its download, whose stream then needs no warming. */
    private var isDownloaded: suspend (videoId: String) -> Boolean = { false }

    fun initialize(
        context: Context,
        isDownloaded: suspend (videoId: String) -> Boolean,
    ) {
        if (isInitialized) return
        this.isDownloaded = isDownloaded
        appContext = context.applicationContext
        isInitialized = true

        queuePersistence = QueuePersistence.getInstance(context)
        audioSettingsPersistence = AudioSettingsPersistence.getInstance(context)

        connectController(context)
        startPositionUpdates()
    }

    /**
     * Builds the controller for the music service. False when Android reports the service missing,
     * which happens when the component is disabled on the device (a blocker app, some ROMs): Media3
     * then throws from the token, and that has to cost music playback, not the app (#1439).
     */
    private fun connectController(context: Context): Boolean {
        val sessionToken =
            try {
                SessionToken(context, ComponentName(context, Media3MusicService::class.java))
            } catch (e: IllegalArgumentException) {
                PlayerDiagnostics.logError("EnhancedMusicPlayer", "music service unavailable: ${e.message}", e)
                return false
            }
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture?.addListener({
            try {
                val controller = controllerFuture?.get()
                player = controller
                _playerInstance.value = controller
                if (controller != null) {
                    setupPlayerListener(controller)

                    val startedBeforeConnect = playBeforeConnect
                    playBeforeConnect = null
                    scope.launch {
                        if (startedBeforeConnect != null) {
                            Log.d("EnhancedMusicPlayer", "Starting the song asked for before the controller connected")
                            startedBeforeConnect()
                        } else {
                            restoreSavedQueue()
                        }
                        restoreAudioSettings()
                    }

                    queuePersistence?.startAutoSave {
                        val currentQ = _queue.value
                        if (currentQ.isNotEmpty()) {
                            QueuePersistence.QueueState(
                                queue = currentQ,
                                currentIndex = _currentQueueIndex.value,
                                currentPosition = _currentPosition.value, // Use StateFlow, not player directly
                                currentTrackId = _currentTrack.value?.videoId,
                                shuffleEnabled = _shuffleEnabled.value,
                                repeatMode = _repeatMode.value.savedCode,
                                savedAt = System.currentTimeMillis(),
                                automix = _automixItems.value,
                            )
                        } else {
                            null
                        }
                    }
                }
            } catch (e: ExecutionException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
        return true
    }

    private fun setupPlayerListener(controller: Player) {
        // The service owns repeat and shuffle; the buttons show what the player will actually do.
        _repeatMode.value = RepeatMode.fromPlayer(controller.repeatMode)
        _shuffleEnabled.value = controller.shuffleModeEnabled
        controller.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updatePlayerState()
                    if (playbackState == Player.STATE_ENDED) {
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayerState()
                    if (isPlaying) startPositionUpdates()
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    if (mediaItem == null) return

                    val isAutomaticTransition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    if (enforcePendingPlayNext(controller, mediaItem, isAutomaticTransition)) {
                        return
                    }

                    syncCurrentTrackFromMediaItem(controller, mediaItem)
                    if (
                        isAutomaticTransition ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                    ) {
                        _currentPosition.value = 0L
                        _playerState.value = _playerState.value.copy(position = 0L)
                    }
                    prefetchNextTrack()
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    _repeatMode.value = RepeatMode.fromPlayer(repeatMode)
                }

                // Every shuffle, from the app, the card, a headset or the car, lands here.
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    val turnedOn = shuffleModeEnabled && !_shuffleEnabled.value
                    _shuffleEnabled.value = shuffleModeEnabled
                    if (turnedOn) shuffleQueue() else triggerQueueSave()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e("EnhancedMusicPlayer", "Player error: ${error.errorCodeName} (${error.errorCode})", error)
                    showPlaybackWarning(
                        appContext?.getString(io.github.aedev.flow.R.string.music_playback_warning_generic)
                            ?: "Music playback failed. Try again or switch networks.",
                    )
                    retryCount = 0
                }
            },
        )
    }

    private fun syncCurrentTrackFromMediaItem(
        controller: Player,
        mediaItem: MediaItem,
    ) {
        val trackId = mediaItem.mediaId
        val currentQ = _queue.value
        val queueIndex =
            MusicQueuePlanner.currentQueueIndex(
                queueIds = currentQ.map { it.videoId },
                playerIndex = controller.currentMediaItemIndex,
                currentTrackId = trackId,
            )
        val track = currentQ.getOrNull(queueIndex) ?: currentQ.find { it.videoId == trackId }
        if (track != null) {
            val resolvedIndex =
                if (queueIndex != MusicQueuePlanner.INDEX_UNSET) {
                    queueIndex
                } else {
                    currentQ.indexOf(track)
                }
            _currentTrack.value = track
            _currentQueueIndex.value = resolvedIndex.coerceAtLeast(0)
        } else {
            adoptPlayerQueue(controller)
        }
        if (
            pendingPlayNextMediaId == trackId &&
            (
                pendingPlayNextMediaIndex == MusicQueuePlanner.INDEX_UNSET ||
                    pendingPlayNextMediaIndex == controller.currentMediaItemIndex
            )
        ) {
            clearPendingPlayNext()
        }
    }

    /**
     * The app sets its queue before it hands the player any item, so an item it does not know was
     * loaded by someone else, such as the car (#1221). The player's items become the queue, so the
     * app shows, saves and extends what is actually playing.
     */
    private fun adoptPlayerQueue(controller: Player) {
        val adopted = adoptedQueue((0 until controller.mediaItemCount).map(controller::getMediaItemAt))
        if (adopted.isEmpty()) return
        val index = controller.currentMediaItemIndex.coerceIn(0, adopted.lastIndex)
        Log.d("EnhancedMusicPlayer", "Adopting a queue of ${adopted.size} the app did not set")
        _queue.value = adopted
        _currentQueueIndex.value = index
        _currentTrack.value = adopted[index]
        triggerQueueSave()
    }

    private fun enforcePendingPlayNext(
        controller: Player,
        mediaItem: MediaItem,
        isAutomaticTransition: Boolean,
    ): Boolean {
        val expectedMediaId = pendingPlayNextMediaId
        val actualMediaId = mediaItem.mediaId
        if (!MusicQueuePlanner.shouldForcePendingPlayNext(
                isAutomaticTransition = isAutomaticTransition,
                pendingMediaId = expectedMediaId,
                pendingPlayerIndex = pendingPlayNextMediaIndex,
                actualMediaId = actualMediaId,
                actualPlayerIndex = controller.currentMediaItemIndex,
            )
        ) {
            return false
        }

        val targetIndex =
            findPlayerMediaItemIndex(
                controller = controller,
                mediaId = expectedMediaId ?: return false,
                preferredIndex = pendingPlayNextMediaIndex,
            )
        if (targetIndex == MusicQueuePlanner.INDEX_UNSET) {
            Log.w("EnhancedMusicPlayer", "Pending play-next item $expectedMediaId is missing from player queue")
            clearPendingPlayNext()
            return false
        }

        Log.w(
            "EnhancedMusicPlayer",
            "Auto transition landed on $actualMediaId; forcing queued play-next item $expectedMediaId",
        )
        controller.seekTo(targetIndex, 0L)
        controller.play()
        return true
    }

    private fun findPlayerMediaItemIndex(
        controller: Player,
        mediaId: String,
        preferredIndex: Int = MusicQueuePlanner.INDEX_UNSET,
    ): Int {
        if (
            preferredIndex in 0 until controller.mediaItemCount &&
            controller.getMediaItemAt(preferredIndex).mediaId == mediaId
        ) {
            return preferredIndex
        }

        for (index in 0 until controller.mediaItemCount) {
            if (controller.getMediaItemAt(index).mediaId == mediaId) {
                return index
            }
        }
        return MusicQueuePlanner.INDEX_UNSET
    }

    private fun currentPlaybackQueueIndex(): Int {
        val queue = _queue.value
        return MusicQueuePlanner.currentQueueIndex(
            queueIds = queue.map { it.videoId },
            playerIndex = player?.currentMediaItemIndex ?: MusicQueuePlanner.INDEX_UNSET,
            currentTrackId = _currentTrack.value?.videoId,
        )
    }

    // Queue entries the Song/Video switch set to play with their picture; every rebuild keeps it.
    private val videoIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun showsVideo(videoId: String): Boolean = videoId in videoIds

    /** A device file plays from its MediaStore URI; everything else resolves through `music://`. */
    private fun streamUri(videoId: String): Uri = LocalMediaIds.audioUri(videoId) ?: MusicVideoItems.streamUri(videoId, showsVideo(videoId))

    /**
     * Puts [track] in place of the queue entry at [index] while that entry is still [expectedId].
     * The playing entry keeps its place in the song, within [track]'s length. Main thread only.
     */
    fun replaceQueueTrack(
        index: Int,
        expectedId: String,
        track: MusicTrack,
        showsVideo: Boolean,
    ): Boolean {
        val p = player ?: return false
        val queue = _queue.value
        if (queue.getOrNull(index)?.videoId != expectedId) return false
        if (index >= p.mediaItemCount || p.getMediaItemAt(index).mediaId != expectedId) return false

        videoIds -= expectedId
        if (showsVideo) videoIds += track.videoId
        _queue.value = queue.toMutableList().also { it[index] = track }
        val isCurrent = p.currentMediaItemIndex == index
        val positionMs = p.currentPosition
        p.replaceMediaItem(index, buildMediaItem(track))
        if (isCurrent) p.seekTo(index, MusicVideoPlanner.positionWithin(positionMs, track.duration))
        triggerQueueSave()
        return true
    }

    private fun buildMediaItem(
        track: MusicTrack,
        uri: Uri = streamUri(track.videoId),
        useCacheKey: Boolean = !LocalMediaIds.isLocal(track.videoId),
    ): MediaItem = musicMediaItem(track, uri, useCacheKey)

    private fun clearPendingPlayNext() {
        pendingPlayNextMediaId = null
        pendingPlayNextMediaIndex = MusicQueuePlanner.INDEX_UNSET
    }

    private fun updatePlayerState() {
        player?.let { p ->
            if (p.playbackState == Player.STATE_READY && p.isPlaying) {
                retryCount = 0
            }

            _playerState.value =
                _playerState.value.copy(
                    isPlaying = p.isPlaying,
                    isBuffering = p.playbackState == Player.STATE_BUFFERING,
                    duration = if (p.duration > 0) p.duration else _playerState.value.duration,
                    position = p.currentPosition,
                )
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob =
            scope.launch {
                while (true) {
                    val p = player
                    if (p != null && p.isPlaying) {
                        val position = p.currentPosition
                        _currentPosition.value = position

                        // Coarsened deliberately. Every consumer of playerState renders seconds, but a
                        // copy here emits a whole new MusicPlayerState to the mini player and the
                        // playback service, so it must not follow the fast tick.
                        val state = _playerState.value
                        val duration = if (p.duration > 0) p.duration else state.duration
                        if (position / 1000L != state.position / 1000L || duration != state.duration) {
                            _playerState.value = state.copy(position = position, duration = duration)
                        }

                        kotlinx.coroutines.delay(
                            if (preciseProgressConsumers > 0) {
                                PRECISE_POSITION_INTERVAL_MS
                            } else {
                                COARSE_POSITION_INTERVAL_MS
                            },
                        )
                    } else {
                        withTimeoutOrNull(5000) { _playerState.first { it.isPlaying } }
                    }
                }
            }
    }

    // --- Playback Control Methods ---

    fun setPendingTrack(
        track: MusicTrack,
        sourceName: String? = null,
    ) {
        clearPendingPlayNext()
        player?.stop()
        player?.clearMediaItems()

        _currentTrack.value = track
        sourceName?.let { _playingFrom.value = it }
        _playerState.value =
            _playerState.value.copy(
                isPlaying = false,
                isBuffering = false,
                isPreparing = true,
                position = 0,
            )
    }

    fun setCurrentTrack(
        track: MusicTrack,
        sourceName: String?,
    ) {
        clearPendingPlayNext()
        _currentTrack.value = track
        sourceName?.let { _playingFrom.value = it }
    }

    fun showPlaybackWarning(message: String) {
        _playbackWarnings.tryEmit(message)
    }

    fun playTrack(
        track: MusicTrack,
        audioStream: AudioStream,
        durationSeconds: Long,
        queue: List<MusicTrack> = emptyList(),
        startIndex: Int = -1,
        sourceName: String? = null,
    ) {
        playTrack(track, audioStream.content, queue, startIndex, sourceName = sourceName)
    }

    fun playTrack(
        track: MusicTrack,
        audioUrl: String,
        queue: List<MusicTrack> = emptyList(),
        startIndex: Int = -1,
        startPositionMs: Long = 0,
        sourceName: String? = null,
        localUriOverrides: Map<String, Uri> = emptyMap(),
    ) {
        val context = appContext
        if (player == null && controllerFuture == null && context != null && !connectController(context)) {
            showPlaybackWarning(context.getString(R.string.music_service_disabled))
            return
        }
        queueStarts++
        if (player == null && controllerFuture != null) {
            playBeforeConnect = { playTrack(track, audioUrl, queue, startIndex, startPositionMs, sourceName, localUriOverrides) }
        }
        val restoredStart = resumePosition?.takeIf { (id, _) -> id == track.videoId && startPositionMs == 0L }?.second
        resumePosition = null
        player?.stop()
        player?.clearMediaItems()
        clearPendingPlayNext()
        // A new queue ends a named station even when it shares tracks with it, which the service
        // would read as the same session. Start radio re-marks it from its pending seed.
        _radioStationActive.value = false

        _playerState.value = _playerState.value.copy(isPreparing = false)

        val activeQueue = if (queue.isNotEmpty()) queue else listOf(track)
        _queue.value = activeQueue
        _currentTrack.value = track
        sourceName?.let { _playingFrom.value = it }

        val mediaItems =
            activeQueue.map { t ->
                val localUri = localUriOverrides[t.videoId]
                val uri =
                    localUri ?: if (t.videoId == track.videoId && audioUrl.isNotEmpty() && !showsVideo(t.videoId)) {
                        Uri.parse(audioUrl)
                    } else {
                        streamUri(t.videoId)
                    }

                buildMediaItem(t, uri, useCacheKey = localUri == null && !LocalMediaIds.isLocal(t.videoId))
            }

        val startIdx = if (startIndex >= 0) startIndex else activeQueue.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)

        player?.setMediaItems(mediaItems, startIdx, restoredStart ?: startPositionMs)
        player?.prepare()
        player?.play()

        prefetchNextTrack()
    }

    fun updateQueue(newQueue: List<MusicTrack>) {
        if (newQueue.isEmpty()) return

        _queue.value = newQueue
        if (pendingPlayNextMediaId != null && newQueue.none { it.videoId == pendingPlayNextMediaId }) {
            clearPendingPlayNext()
        }
        triggerQueueSave()

        scope.launch {
            val currentMediaId = player?.currentMediaItem?.mediaId
            val currentPosition = player?.currentPosition ?: 0L

            val mediaItems = newQueue.map { track -> buildMediaItem(track) }

            val newIndex =
                MusicQueuePlanner
                    .currentQueueIndex(
                        queueIds = newQueue.map { it.videoId },
                        playerIndex = player?.currentMediaItemIndex ?: _currentQueueIndex.value,
                        currentTrackId = currentMediaId,
                    ).coerceAtLeast(0)

            player?.let { p ->
                if (p.mediaItemCount != mediaItems.size || p.currentMediaItem?.mediaId != currentMediaId) {
                    p.setMediaItems(mediaItems, newIndex, currentPosition)
                }
            }
        }
    }

    /**
     * Physically rearranges the queue: the playing track is pinned to the top and everything else
     * is randomized. Applied as individual player moves so playback never restarts or rebuffers.
     */
    fun shuffleQueue() {
        scope.launch {
            val currentQ = _queue.value
            if (currentQ.size < 2) return@launch
            val currentIdx =
                currentPlaybackQueueIndex().takeIf { it in currentQ.indices }
                    ?: _currentQueueIndex.value.coerceIn(0, currentQ.size - 1)
            val target =
                buildList {
                    add(currentQ[currentIdx])
                    addAll(currentQ.filterIndexed { index, _ -> index != currentIdx }.shuffled())
                }
            _queue.value = target
            clearPendingPlayNext()
            player?.let { p ->
                if (p.mediaItemCount == target.size) {
                    target.forEachIndexed { targetIdx, track ->
                        var fromIdx = -1
                        for (i in targetIdx until p.mediaItemCount) {
                            if (p.getMediaItemAt(i).mediaId == track.videoId) {
                                fromIdx = i
                                break
                            }
                        }
                        if (fromIdx > targetIdx) {
                            p.moveMediaItem(fromIdx, targetIdx)
                        }
                    }
                }
            }
            triggerQueueSave()
        }
    }

    fun updateAutomixItems(items: List<MusicTrack>) {
        _automixItems.value =
            MusicRadioPlanner.seedPool(
                candidates = items,
                currentId = _currentTrack.value?.videoId,
                queueIds = _queue.value.mapTo(HashSet()) { it.videoId },
            )
        triggerQueueSave()
    }

    /** Radio top-up path: grows the suggestion pool without disturbing what's already in it. */
    fun appendAutomixItems(items: List<MusicTrack>) {
        if (items.isEmpty()) return
        val existing = _automixItems.value
        val grown =
            MusicRadioPlanner.growPool(
                existing = existing,
                incoming = items,
                currentId = _currentTrack.value?.videoId,
                queueIds = _queue.value.mapTo(HashSet()) { it.videoId },
            )
        if (grown === existing) return
        _automixItems.value = grown
        triggerQueueSave()
    }

    fun removeAutomixItem(videoId: String) {
        val updated = _automixItems.value.filterNot { it.videoId == videoId }
        if (updated.size != _automixItems.value.size) {
            _automixItems.value = updated
            triggerQueueSave()
        }
    }

    private fun triggerQueueSave() {
        val currentQ = _queue.value
        if (currentQ.isNotEmpty()) {
            queuePersistence?.saveQueueDebounced(
                queue = currentQ,
                currentIndex = _currentQueueIndex.value,
                currentPosition = _currentPosition.value, // Use StateFlow for thread safety
                currentTrackId = _currentTrack.value?.videoId,
                shuffleEnabled = _shuffleEnabled.value,
                repeatMode = _repeatMode.value.savedCode,
                automix = _automixItems.value,
            )
        }
    }

    /**
     * Restore queue from persistent storage
     */
    private suspend fun restoreSavedQueue() {
        try {
            val starts = queueStarts
            val restored = queuePersistence?.restoreQueue()?.let(::restoredMusicQueue) ?: return
            if (queueStarts != starts) {
                Log.d("EnhancedMusicPlayer", "Saved queue skipped: a newer queue started while it was read")
                return
            }
            val controller = player
            val loadedItem = controller?.currentMediaItem
            // The system media controls may already have resumed this queue in the service.
            val resumedBySystem = loadedItem != null && restored.queue.any { it.videoId == loadedItem.mediaId }
            if (loadedItem != null && !resumedBySystem) return

            Log.d("EnhancedMusicPlayer", "Restoring saved queue: ${restored.queue.size} tracks")

            // The service may have rearranged a resumed queue (a shuffle while the app was closed).
            val playerOrder = controller?.let { c -> List(c.mediaItemCount) { c.getMediaItemAt(it).mediaId } }
            _queue.value =
                playerOrder
                    ?.takeIf { resumedBySystem }
                    ?.let { order -> PlaylistQueueOrder.alignTo(restored.queue, order) { it.videoId } }
                    ?: restored.queue
            _currentQueueIndex.value = restored.index
            _automixItems.value = restored.automix
            // A queue the service resumed already carries its shuffle; otherwise it comes back with it.
            if (!resumedBySystem) applySavedShuffle(controller, restored.shuffleEnabled)

            if (controller != null && loadedItem != null) {
                syncCurrentTrackFromMediaItem(controller, loadedItem)
                // Already playing when this controller connected, so no change callback reports it.
                updatePlayerState()
                if (controller.isPlaying) startPositionUpdates()
                return
            }
            restored.track?.let {
                _currentTrack.value = it
                _currentPosition.value = restored.resumePositionMs
                resumePosition = it.videoId to restored.resumePositionMs
                val state = _playerState.value
                _playerState.value =
                    state.copy(
                        position = restored.resumePositionMs,
                        duration = if (it.duration > 0) it.duration * 1000L else state.duration,
                    )
            }
        } catch (e: Exception) {
            Log.e("EnhancedMusicPlayer", "Failed to restore queue", e)
        }
    }

    /**
     * Force save current queue immediately.
     * Uses cached position from StateFlow for thread safety.
     */
    fun saveQueueNow() {
        scope.launch(Dispatchers.IO) {
            val currentQ = _queue.value
            if (currentQ.isNotEmpty()) {
                queuePersistence?.saveQueueImmediate(
                    queue = currentQ,
                    currentIndex = _currentQueueIndex.value,
                    currentPosition = _currentPosition.value, // Use StateFlow for thread safety
                    currentTrackId = _currentTrack.value?.videoId,
                    shuffleEnabled = _shuffleEnabled.value,
                    repeatMode = _repeatMode.value.savedCode,
                    automix = _automixItems.value,
                )
            }
        }
    }

    fun togglePlayPause() {
        scope.launch {
            player?.let { p ->
                if (p.mediaItemCount == 0 && _currentTrack.value != null) {
                    _currentTrack.value?.let { track ->
                        _playerEvents.emit(PlayerEvent.RequestPlayTrack(track))
                    }
                } else if (p.isPlaying) {
                    p.pause()
                } else {
                    p.play()
                }
            }
        }
    }

    fun playNext(track: MusicTrack) {
        val currentQ = _queue.value.toMutableList()
        val insertIdx =
            MusicQueuePlanner.playNextInsertionIndex(
                queueIds = currentQ.map { it.videoId },
                playerIndex = player?.currentMediaItemIndex ?: MusicQueuePlanner.INDEX_UNSET,
                currentTrackId = _currentTrack.value?.videoId,
            )

        currentQ.add(insertIdx, track)
        _queue.value = currentQ
        pendingPlayNextMediaId = track.videoId
        pendingPlayNextMediaIndex = insertIdx

        player?.let { p ->
            val playerInsertIdx =
                when {
                    p.currentMediaItemIndex in 0 until p.mediaItemCount -> p.currentMediaItemIndex + 1
                    else -> insertIdx
                }.coerceIn(0, p.mediaItemCount)

            if (playerInsertIdx <= p.mediaItemCount) {
                p.addMediaItem(playerInsertIdx, buildMediaItem(track))
                pendingPlayNextMediaIndex = playerInsertIdx
            }
        }

        triggerQueueSave()
    }

    fun addToQueue(track: MusicTrack) {
        val currentQ = _queue.value.toMutableList()
        currentQ.add(track)
        _queue.value = currentQ

        player?.let { p ->
            p.addMediaItem(buildMediaItem(track))
        }

        triggerQueueSave()
    }

    fun playNext() {
        val queue = _queue.value
        val idx = currentPlaybackQueueIndex()

        if (idx != -1 && idx < queue.size - 1) {
            val nextTrack = queue[idx + 1]
            setPendingTrack(nextTrack)
            scope.launch { _playerEvents.emit(PlayerEvent.RequestPlayTrack(nextTrack)) }
        }
    }

    fun playPrevious() {
        scope.launch {
            val queue = _queue.value
            val idx = currentPlaybackQueueIndex()

            if ((player?.currentPosition ?: 0) > 3000) {
                player?.seekTo(0)
                return@launch
            }

            if (idx > 0) {
                val prevTrack = queue[idx - 1]
                setPendingTrack(prevTrack)
                _playerEvents.emit(PlayerEvent.RequestPlayTrack(prevTrack))
            }
        }
    }

    fun playFromQueue(index: Int) {
        val queue = _queue.value
        if (index in queue.indices) {
            clearPendingPlayNext()
            val track = queue[index]
            setPendingTrack(track)
            scope.launch { _playerEvents.emit(PlayerEvent.RequestPlayTrack(track)) }
        }
    }

    /** The saved queue is already in its shuffled order, so turning the flag back on must not reshuffle it. */
    private fun applySavedShuffle(
        controller: Player?,
        enabled: Boolean,
    ) {
        _shuffleEnabled.value = enabled
        if (controller != null && controller.shuffleModeEnabled != enabled) controller.shuffleModeEnabled = enabled
    }

    /**
     * The player's shuffle flag says whether shuffle is on; turning it on physically rearranges the
     * queue, so the listed order always matches the playback order.
     */
    fun toggleShuffle() {
        scope.launch {
            val controller = player ?: return@launch
            controller.shuffleModeEnabled = !controller.shuffleModeEnabled
        }
    }

    fun toggleRepeat() {
        scope.launch {
            player?.let {
                val newMode =
                    when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                it.repeatMode = newMode
            }
        }
    }

    fun seekTo(position: Long) {
        scope.launch {
            val duration = player?.duration?.takeIf { it > 0 } ?: _playerState.value.duration.takeIf { it > 0 }
            val target = duration?.let { position.coerceIn(0L, it) } ?: position.coerceAtLeast(0L)

            _currentPosition.value = target
            _playerState.value = _playerState.value.copy(position = target)
            player?.seekTo(target)
        }
    }

    fun switchMode(url: String) {
        scope.launch {
            player?.let { p ->
                val currentPos = p.currentPosition
                val wasPlaying = p.isPlaying

                val currentItem = p.currentMediaItem ?: return@let
                val newItem =
                    currentItem
                        .buildUpon()
                        .setUri(Uri.parse(url))
                        .build()

                val currentIndex = p.currentMediaItemIndex
                if (currentIndex >= 0 && currentIndex < p.mediaItemCount) {
                    p.replaceMediaItem(currentIndex, newItem)
                    p.seekTo(currentPos)
                    if (wasPlaying) p.play()
                }
            }
        }
    }

    fun getCurrentPosition(): Long =
        try {
            if (player?.isPlaying == true) {
                player?.currentPosition ?: _currentPosition.value
            } else {
                _currentPosition.value
            }
        } catch (e: Exception) {
            _currentPosition.value
        }

    fun getDuration(): Long = _playerState.value.duration

    fun toggleLike() {
        _isLiked.value = !_isLiked.value
        emitToggleLikeEvent()
    }

    fun setLiked(liked: Boolean) {
        _isLiked.value = liked
    }

    fun emitToggleLikeEvent() {
        scope.launch { _playerEvents.emit(PlayerEvent.RequestToggleLike) }
    }

    fun play() {
        scope.launch { player?.play() }
    }

    fun pause() {
        scope.launch { player?.pause() }
    }

    fun stop() {
        scope.launch {
            player?.stop()
            _playerState.value =
                _playerState.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    isPreparing = false,
                    position = 0L,
                )
            _currentPosition.value = 0L
        }
    }

    /** A slider passes [persist] false while dragging and saves once on release. */
    fun setPlaybackSpeed(
        speed: Float,
        persist: Boolean = true,
    ) {
        _playbackSpeed.value = speed
        if (persist) scope.launch { audioSettingsPersistence?.saveSpeed(speed) }

        player?.let { p ->
            val currentPitch = p.playbackParameters.pitch
            p.playbackParameters = PlaybackParameters(speed, currentPitch)
        }
    }

    fun setPlaybackPitch(
        semitones: Float,
        persist: Boolean = true,
    ) {
        _playbackPitch.value = semitones
        if (persist) scope.launch { audioSettingsPersistence?.savePitch(semitones) }

        player?.let { p ->
            val pitch = 2.0.pow(semitones.toDouble() / 12.0).toFloat()
            val currentSpeed = p.playbackParameters.speed
            p.playbackParameters = PlaybackParameters(currentSpeed, pitch)
        }
    }

    private suspend fun restoreAudioSettings() {
        try {
            val settings = audioSettingsPersistence?.settingsFlow?.first() ?: return

            Log.d("EnhancedMusicPlayer", "Restoring audio settings: $settings")

            _playbackSpeed.value = settings.speed
            _playbackPitch.value = settings.pitch

            player?.let { p ->
                val pitch = 2.0.pow(settings.pitch.toDouble() / 12.0).toFloat()
                p.playbackParameters = PlaybackParameters(settings.speed, pitch)
            }
        } catch (e: Exception) {
            Log.e("EnhancedMusicPlayer", "Failed to restore audio settings", e)
        }
    }

    fun isPlaying(): Boolean = _playerState.value.isPlaying

    fun clearCurrentTrack() {
        scope.launch {
            player?.pause()
            player?.stop()
            player?.clearMediaItems()
            resetQueueState()
            (player as? MediaController)?.let(Media3MusicService::requestStop)
        }
    }

    /** Called by the service once it has stopped itself, so the UI drops the track it showed. */
    fun onServiceStopped() {
        scope.launch { resetQueueState() }
    }

    private fun resetQueueState() {
        _currentTrack.value = null
        _queue.value = emptyList()
        _automixItems.value = emptyList()
        playContextGenre = null
        pendingRadioSeedId = null
        _radioLoading.value = false
        _radioStationActive.value = false
        _currentQueueIndex.value = 0
        clearPendingPlayNext()
        _currentPosition.value = 0L
        _playingFrom.value = "Flow Music"
        _playerState.value = MusicPlayerState()
    }

    /** A song taken out of the queue, with what [restoreToQueue] needs to put it back. */
    data class RemovedQueueTrack(
        val track: MusicTrack,
        val index: Int,
        internal val queueAfter: List<String>,
    )

    /** Takes the song at [index] out of the queue; null for the playing song or an index the queue lacks. */
    fun removeFromQueue(index: Int): RemovedQueueTrack? {
        val removal = PlaylistQueueOrder.removeAt(_queue.value, _currentQueueIndex.value, index) ?: return null
        _queue.value = removal.queue.items
        _currentQueueIndex.value = removal.queue.currentIndex
        when {
            pendingPlayNextMediaIndex == index -> clearPendingPlayNext()
            pendingPlayNextMediaIndex > index -> pendingPlayNextMediaIndex--
        }
        player?.let { p -> if (index < p.mediaItemCount) p.removeMediaItem(index) }
        triggerQueueSave()
        return RemovedQueueTrack(removal.removedItem, index, removal.queue.items.map { it.videoId })
    }

    /**
     * Puts [removed] back where it was. Songs added at the end since then are fine; any other change
     * to the queue means its place no longer exists, and the undo does nothing.
     */
    fun restoreToQueue(removed: RemovedQueueTrack) {
        val queue = _queue.value
        if (queue.take(removed.queueAfter.size).map { it.videoId } != removed.queueAfter) return
        val index = removed.index.coerceIn(0, queue.size)
        _queue.value = queue.toMutableList().apply { add(index, removed.track) }
        if (index <= _currentQueueIndex.value) _currentQueueIndex.value += 1
        if (pendingPlayNextMediaIndex >= index) pendingPlayNextMediaIndex++
        player?.let { p -> if (index <= p.mediaItemCount) p.addMediaItem(index, buildMediaItem(removed.track)) }
        triggerQueueSave()
    }

    fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) {
        scope.launch {
            val moved = PlaylistQueueOrder.move(_queue.value, _currentQueueIndex.value, fromIndex, toIndex) ?: return@launch
            _queue.value = moved.items
            // The player reports no transition when songs move around the playing one, so the index follows here.
            _currentQueueIndex.value = moved.currentIndex
            clearPendingPlayNext()

            player?.let { p ->
                if (fromIndex < p.mediaItemCount && toIndex < p.mediaItemCount) {
                    p.moveMediaItem(fromIndex, toIndex)
                }
            }
            triggerQueueSave()
        }
    }

    fun insertMediaItem(
        index: Int,
        track: MusicTrack,
    ) {
        scope.launch {
            val currentQ = _queue.value.toMutableList()
            val insertIndex = index.coerceIn(0, currentQ.size)
            currentQ.add(insertIndex, track)
            _queue.value = currentQ
            if (pendingPlayNextMediaIndex >= insertIndex) {
                pendingPlayNextMediaIndex++
            }

            player?.let { p ->
                if (insertIndex <= p.mediaItemCount) {
                    p.addMediaItem(insertIndex, buildMediaItem(track))
                }
            }
            triggerQueueSave()
        }
    }
}

data class MusicPlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isPreparing: Boolean = false,
    val isReady: Boolean = false,
    val playWhenReady: Boolean = false,
    val duration: Long = 0,
    val position: Long = 0,
)

private const val PRECISE_POSITION_INTERVAL_MS = 250L
private const val COARSE_POSITION_INTERVAL_MS = 1_000L

/** [savedCode] is how a saved queue writes the mode; [playerMode] is Media3's own value for it. */
enum class RepeatMode(
    val savedCode: Int,
    val playerMode: Int,
) {
    OFF(0, Player.REPEAT_MODE_OFF),
    ALL(1, Player.REPEAT_MODE_ALL),
    ONE(2, Player.REPEAT_MODE_ONE),
    ;

    companion object {
        fun fromPlayer(mode: Int): RepeatMode = entries.firstOrNull { it.playerMode == mode } ?: OFF
    }
}
