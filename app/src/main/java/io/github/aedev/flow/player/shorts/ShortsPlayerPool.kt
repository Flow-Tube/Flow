package io.github.aedev.flow.player.shorts

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import io.github.aedev.flow.data.local.DEFAULT_VIDEO_VOLUME_NORMALIZATION
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.player.audio.LoudnessGain
import io.github.aedev.flow.player.datasource.YouTubeHttpDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Three pooled ExoPlayers for the Shorts pager. A page maps to slot `index % 3`, so the reel on
 * screen and both neighbours are prepared at once and a swipe only starts or pauses a player.
 * Buffer limits come from [io.github.aedev.flow.player.factory.LoadControlFactory.forShorts].
 */
@OptIn(UnstableApi::class)
class ShortsPlayerPool private constructor() {
    companion object {
        private const val TAG = "ShortsPlayerPool"
        private const val POOL_SIZE = 3
        private val MovieAudio =
            AudioAttributes
                .Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build()

        @Volatile
        private var instance: ShortsPlayerPool? = null

        fun getInstance(): ShortsPlayerPool =
            instance ?: synchronized(this) {
                instance ?: ShortsPlayerPool().also { instance = it }
            }
    }

    private val players = arrayOfNulls<ExoPlayer>(POOL_SIZE)
    private val slots = ShortsSlots(POOL_SIZE)

    private var audioEffects: ShortsPoolAudioEffects? = null

    private var isInitialized = false
    private var dataSourceFactory: DefaultDataSource.Factory? = null

    /**
     * One session for the whole pool, re-pointed at the active player on every settle, so headset
     * and Bluetooth buttons reach the reel on screen. A session per page used to be built and torn
     * down on every swipe.
     */
    private var mediaSession: MediaSession? = null

    @Volatile
    private var activeIndex: Int = -1
    private var activeVideoId: String? = null

    private val _ownershipGeneration = MutableStateFlow(0)
    val ownershipGeneration: StateFlow<Int> = _ownershipGeneration.asStateFlow()

    private val _trimGeneration = MutableStateFlow(0)
    val trimGeneration: StateFlow<Int> = _trimGeneration.asStateFlow()
    private var memoryTrim: ShortsMemoryTrim? = null

    private fun bumpOwnership() {
        _ownershipGeneration.value += 1
    }

    private var hostToken: Long = 0L
    private var nextHostToken: Long = 1L

    fun acquireHost(): Long = nextHostToken++.also { hostToken = it }

    fun releaseIfHost(token: Long) {
        if (token != hostToken) return
        release()
    }

    private val sharedCache = ShortsSharedCache()
    private var preferredAudioLanguage: String = "original"
    private var shortsPlaybackMode: String = "loop"
    private var basePlaybackSpeed: Float = 1f
    private var volumeNormalization: Boolean = DEFAULT_VIDEO_VOLUME_NORMALIZATION

    private val preferenceObservers = ShortsPreferenceObservers()
    private val firstFrames = ShortsFirstFrames()

    /** Reels whose first frame is on a surface; see [ShortsFirstFrames]. */
    val renderedVideoIds: StateFlow<Set<String>> = firstFrames.renderedVideoIds

    private val _currentVideoId = MutableStateFlow<String?>(null)
    val currentVideoId: StateFlow<String?> = _currentVideoId.asStateFlow()

    private val _anyPlaying = MutableStateFlow(false)

    /** Whether any pooled player is playing, from the players' own callbacks. */
    val anyPlaying: StateFlow<Boolean> = _anyPlaying.asStateFlow()

    private val playingListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _anyPlaying.value = players.any { it?.isPlaying == true }
            }
        }

    private val _currentVideo = MutableStateFlow<ShortVideo?>(null)
    val currentVideo: StateFlow<ShortVideo?> = _currentVideo.asStateFlow()

    fun setCurrentVideo(video: ShortVideo?) {
        _currentVideo.value = video
    }

    fun playbackPosition(): Long = findActivePlayer()?.currentPosition ?: 0L

    fun playbackDuration(): Long = findActivePlayer()?.duration?.coerceAtLeast(0L) ?: 0L

    fun isPlaying(): Boolean = findActivePlayer()?.isPlaying == true

    /**
     * The playing reel's frame aspect, or null before the first frame reports a size.
     *
     * Measured rather than assumed 9:16: reels are only *mostly* portrait, and handing a
     * Picture-in-Picture window the wrong ratio letterboxes it for the whole session.
     */
    fun activeVideoAspectRatio(): Float? =
        findActivePlayer()
            ?.videoSize
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { it.width.toFloat() / it.height.toFloat() }

    // INITIALIZATION
    fun initialize(context: Context) {
        if (isInitialized) return

        val appContext = context.applicationContext
        Log.d(TAG, "Initializing 3-player pool for Shorts")
        dataSourceFactory = DefaultDataSource.Factory(appContext, YouTubeHttpDataSource.Factory())
        sharedCache.attach(appContext)
        val preferences = PlayerPreferences(appContext)
        preferenceObservers.start(
            preferredAudioLanguage = preferences.preferredAudioLanguage,
            playbackMode = preferences.shortsPlaybackMode,
            playbackSpeed = preferences.shortsPlaybackSpeed,
            onPreferredAudioLanguage = { language ->
                preferredAudioLanguage = language
                updateTrackSelectors(language)
            },
            onPlaybackMode = { mode ->
                shortsPlaybackMode = mode
                Log.d(TAG, "Shorts playback mode changed to: $mode")
            },
            onPlaybackSpeed = { speed ->
                setBasePlaybackSpeed(speed)
            },
            volumeNormalization = preferences.videoVolumeNormalizationEnabled,
            onVolumeNormalization = { enabled ->
                volumeNormalization = enabled
                for (slot in 0 until POOL_SIZE) applyVolume(slot)
            },
        )

        val audio = ShortsPoolAudioEffects(appContext, POOL_SIZE).also { audioEffects = it }

        try {
            for (i in 0 until POOL_SIZE) {
                players[i] =
                    ShortsPlayerFactory
                        .player(
                            appContext,
                            audio.equalizerFor(i),
                            dataSourceFactory!!,
                            preferredAudioLanguage,
                            slotVideoId = { slots.videoIds[i] },
                            onFirstFrame = { firstFrames.onRendered(slots.videoIds[i]) },
                            isActiveSlot = { activeIndex >= 0 && ShortsSlotRules.slotFor(activeIndex, POOL_SIZE) == i },
                        ).also {
                            it.addListener(playingListener)
                            audio.announce(i, it)
                        }
                slots.clear(i)
            }
            mediaSession = ShortsPlayerFactory.mediaSession(appContext, players[0]!!)
            memoryTrim = ShortsMemoryTrim(appContext) { trimInactiveSlots() }.also { it.register() }
            isInitialized = true
            Log.d(TAG, "Player pool initialized with $POOL_SIZE players")
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    private fun updateTrackSelectors(language: String) {
        players.filterNotNull().forEach { player ->
            val trackSelector = player.trackSelector as? DefaultTrackSelector
            trackSelector?.let { selector ->
                val builder = selector.buildUponParameters()
                builder.setPreferredAudioLanguage(language.takeUnless { it == "original" || it.isBlank() })
                selector.setParameters(builder)
            }
        }
    }

    // PLAYER ACCESS

    /**
     * The player a page should bind its `PlayerView` to.
     *
     * Deliberately returns the slot before any media is loaded: the surface has to be attached to
     * the ExoPlayer instance ahead of [prepare] or the first frame arrives with nowhere to go. It
     * refuses only when the slot currently belongs to a *different* index, which is what used to
     * hand a mid-fling page the still-playing previous short.
     *
     * Callers must re-read this when [ownershipGeneration] changes.
     */
    fun playerForAttach(
        index: Int,
        videoId: String,
    ): ExoPlayer? {
        if (!isInitialized || index < 0) return null
        val slot = ShortsSlotRules.slotFor(index, POOL_SIZE)
        if (!ShortsSlotRules.canAttach(slots.ownerIndices[slot], slots.videoIds[slot], index, videoId)) return null
        return players[slot]
    }

    /**
     * The player that actually holds this index's media, or null while the slot is unprepared or
     * owned by someone else. Everything that reads playback state or issues a command uses this, so
     * a control can never land on a short the user is not looking at.
     */
    fun ownedPlayer(
        index: Int,
        videoId: String?,
    ): ExoPlayer? {
        if (!isInitialized || index < 0 || videoId == null) return null
        val slot = ShortsSlotRules.slotFor(index, POOL_SIZE)
        if (!ShortsSlotRules.isOwnedBy(slots.ownerIndices[slot], slots.videoIds[slot], index, videoId)) return null
        return players[slot]
    }

    fun getVideoUrlForIndex(
        index: Int,
        videoId: String,
    ): String? {
        if (ownedPlayer(index, videoId) == null) return null
        return slots.streams[ShortsSlotRules.slotFor(index, POOL_SIZE)]?.videoUrl
    }

    fun getCurrentVideoId(): String? = _currentVideoId.value

    // MEDIA LOADING

    /**
     * Prepare the player for a specific index with video + audio streams.
     * @param index The absolute list position of the video
     * @param shouldPlay If true, starts playback immediately (for current item). If false, just buffers (for next/prev).
     */
    fun prepare(
        index: Int,
        videoId: String,
        videoUrl: String,
        audioUrl: String?,
        shouldPlay: Boolean,
        videoDashManifest: String? = null,
        audioDashManifest: String? = null,
        loudnessGainDb: Float? = null,
        videoAdaptiveManifest: DashManifest? = null,
    ) {
        if (!isInitialized || index < 0) return
        val slot = index % POOL_SIZE
        val player = players[slot] ?: return

        if (slots.owns(slot, index, videoId)) {
            if (shouldPlay && !player.isPlaying) {
                Log.d(TAG, "Player at index $index (slot $slot) already prepared. Resuming.")
                activatePlayer(index, videoId)
            }
            return
        }

        Log.d(TAG, "Preparing player at index $index (slot $slot) for video $videoId. AutoPlay: $shouldPlay")

        // Stop any previous playback in this slot
        player.stop()
        player.clearMediaItems()
        firstFrames.forget(slots.videoIds[slot])
        firstFrames.forget(videoId)

        val streams = ShortsSlotStreams(videoUrl, audioUrl, videoDashManifest, audioDashManifest, loudnessGainDb, videoAdaptiveManifest)
        slots.assign(slot, index, videoId, streams)
        applyVolume(slot)
        bumpOwnership()

        // Load media
        if (shouldPlay) ShortsStartupTrace.onPrepared(videoId)
        preparePlayerInternal(player, streams)

        // Set playback state
        player.setPlaybackSpeed(basePlaybackSpeed)
        player.playWhenReady = shouldPlay
        player.repeatMode = repeatMode()

        if (shouldPlay) _currentVideoId.value = videoId
        // Only the reel on screen takes audio focus; the buffering neighbours must not.
        player.setAudioAttributes(MovieAudio, shouldPlay)
    }

    /**
     * Activates the player holding [videoId] at [index] (play) and pauses all others.
     * Call this when a page settles.
     */
    fun activatePlayer(
        index: Int,
        videoId: String,
    ) {
        if (!isInitialized) return
        val activeSlot = index % POOL_SIZE

        activeIndex = index
        activeVideoId = videoId
        ShortsStartupTrace.onActivated(videoId)

        for (i in 0 until POOL_SIZE) {
            val player = players[i] ?: continue
            val isTarget = (i == activeSlot)

            if (isTarget) {
                if (ShortsSlotRules.isOwnedBy(slots.ownerIndices[i], slots.videoIds[i], index, videoId)) {
                    player.playWhenReady = true
                    player.setPlaybackSpeed(basePlaybackSpeed)
                    player.setAudioAttributes(MovieAudio, true)
                    _currentVideoId.value = slots.videoIds[i]
                } else {
                    // The slot still holds another short until prepare() swaps it; it must not play meanwhile.
                    player.playWhenReady = false
                    _currentVideoId.value = null
                }
            } else {
                player.playWhenReady = false
            }
        }
        players[activeSlot]?.let { active ->
            mediaSession?.takeIf { it.player !== active }?.setPlayer(active)
        }
    }

    fun releaseUnusedPlayers(currentIndex: Int) {
        if (!isInitialized) return

        val stale = (0 until POOL_SIZE).filter { ShortsSlotRules.shouldRelease(slots.ownerIndices[it], currentIndex) }
        stale.forEach(::releaseSlot)
        if (stale.isNotEmpty()) bumpOwnership()
    }

    /** Memory pressure: everything but the reel on screen goes; [trimGeneration] tells the pager to prepare them again. */
    private fun trimInactiveSlots() {
        val active = if (activeIndex >= 0) ShortsSlotRules.slotFor(activeIndex, POOL_SIZE) else -1
        val inactive = (0 until POOL_SIZE).filter { it != active && slots.ownerIndices[it] != null }
        inactive.forEach(::releaseSlot)
        if (inactive.isNotEmpty()) {
            bumpOwnership()
            _trimGeneration.value += 1
        }
    }

    private fun releaseSlot(slot: Int) {
        Log.d(TAG, "Releasing player slot $slot (owned by index ${slots.ownerIndices[slot]})")
        players[slot]?.stop()
        players[slot]?.clearMediaItems()
        slots.videoIds[slot]?.let { ShortsStartupTrace.forget(it) }
        firstFrames.forget(slots.videoIds[slot])
        slots.clear(slot)
    }

    /**
     * Hot-swap the audio track for an already-prepared player slot, keeping the same video URL.
     * Used for the Shorts audio track selector.
     */
    fun reloadWithAudioUrl(
        index: Int,
        videoId: String,
        newAudioUrl: String?,
        newAudioDashManifest: String? = null,
        loudnessGainDb: Float? = null,
    ) = reload(index, videoId, keepPosition = false) {
        it.copy(audioUrl = newAudioUrl, audioManifest = newAudioDashManifest, loudnessGainDb = loudnessGainDb)
    }

    /**
     * Hot-swap the video quality (URL) for an already-prepared player slot.
     * Used for the Shorts quality selector.
     */
    fun reloadWithVideoUrl(
        index: Int,
        videoId: String,
        newVideoUrl: String,
        newVideoDashManifest: String? = null,
    ) = reload(index, videoId, keepPosition = true) {
        it.copy(videoUrl = newVideoUrl, videoManifest = newVideoDashManifest, adaptiveManifest = null)
    }

    private fun reload(
        index: Int,
        videoId: String,
        keepPosition: Boolean,
        change: (ShortsSlotStreams) -> ShortsSlotStreams,
    ) {
        if (!isInitialized || index < 0) return
        val slot = index % POOL_SIZE
        val player = players[slot] ?: return
        if (!slots.owns(slot, index, videoId)) return
        val streams = slots.streams[slot]?.let(change) ?: return
        val wasPlaying = player.isPlaying || player.playWhenReady
        val position = player.currentPosition

        player.stop()
        player.clearMediaItems()
        slots.streams[slot] = streams
        applyVolume(slot)

        preparePlayerInternal(player, streams)
        player.playWhenReady = wasPlaying
        if (keepPosition) player.seekTo(position)
        player.repeatMode = repeatMode()
    }

    private fun preparePlayerInternal(
        player: ExoPlayer,
        streams: ShortsSlotStreams,
    ) {
        val factory = sharedCache.dataSourceFactory ?: dataSourceFactory ?: return
        ShortsMediaSources.load(player, factory, streams)
        player.setPlaybackSpeed(basePlaybackSpeed)
    }

    private fun repeatMode(): Int = if (shortsPlaybackMode == "loop") Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

    private fun applyVolume(slot: Int) {
        val gainDb = slots.streams[slot]?.loudnessGainDb?.takeIf { volumeNormalization }
        players[slot]?.volume = LoudnessGain.volume(gainDb)
    }

    // PLAYBACK CONTROL

    /**
     * The player for [activeIndex], or null when the active page's media has not loaded yet.
     *
     * Returning null is the point: a command issued during that window is dropped rather than
     * applied to whichever slot happened to still hold the previous short's id.
     */
    private fun findActivePlayer(): ExoPlayer? = ownedPlayer(activeIndex, activeVideoId)

    fun play() {
        findActivePlayer()?.let { player ->
            player.setPlaybackSpeed(basePlaybackSpeed)
            player.playWhenReady = true
        }
    }

    fun pause() {
        findActivePlayer()?.playWhenReady = false
    }

    fun togglePlayPause() {
        val player = findActivePlayer() ?: return
        player.playWhenReady = !player.playWhenReady
    }

    fun seekTo(positionMs: Long) {
        findActivePlayer()?.seekTo(positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        findActivePlayer()?.setPlaybackSpeed(speed)
    }

    fun resetPlaybackSpeed() {
        findActivePlayer()?.setPlaybackSpeed(basePlaybackSpeed)
    }

    fun setBasePlaybackSpeed(speed: Float) {
        basePlaybackSpeed = speed
        findActivePlayer()?.setPlaybackSpeed(speed)
    }

    fun getBasePlaybackSpeed(): Float = basePlaybackSpeed

    /** Pause ALL players */
    fun pauseAll() {
        for (i in 0 until POOL_SIZE) {
            players[i]?.playWhenReady = false
        }
    }

    /**
     * Release all players and reset state.
     * Call when leaving the Shorts screen.
     */
    fun release() {
        Log.d(TAG, "Releasing player pool")
        memoryTrim?.unregister()
        memoryTrim = null
        preferenceObservers.stop()
        sharedCache.release()
        mediaSession?.release()
        mediaSession = null
        audioEffects?.release()
        audioEffects = null
        for (i in 0 until POOL_SIZE) {
            players[i]?.stop()
            players[i]?.release()
            players[i] = null
            slots.clear(i)
        }
        dataSourceFactory = null
        isInitialized = false
        activeIndex = -1
        activeVideoId = null
        _currentVideoId.value = null
        _currentVideo.value = null
        _anyPlaying.value = false
        firstFrames.clear()
        bumpOwnership()
    }

    fun isReady(): Boolean = isInitialized && players[0] != null
}
