package io.github.aedev.flow.player.shorts

import android.content.Context
import android.media.audiofx.AudioEffect
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import dagger.hilt.android.EntryPointAccessors
import io.github.aedev.flow.data.local.DEFAULT_VIDEO_VOLUME_NORMALIZATION
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.player.audio.AudioEffectsEntryPoint
import io.github.aedev.flow.player.audio.AudioSessionAnnouncer
import io.github.aedev.flow.player.audio.LoudnessGain
import io.github.aedev.flow.player.audio.eq.EqualizerAudioProcessor
import io.github.aedev.flow.player.cache.PlayerCacheManager
import io.github.aedev.flow.player.cache.SharedPlayerCacheProvider
import io.github.aedev.flow.player.datasource.YouTubeHttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ShortsPlayerPool — 3-player pool for instant swipe transitions.
 *
 * Architecture:
 * - 3 ExoPlayer instances with roles: PREVIOUS, CURRENT, NEXT
 * - Aggressive buffer settings (2.5s min / 15s max / 500ms playback / 1.5s rebuffer)
 * - REPEAT_MODE_ONE for looping shorts
 * - RESIZE_MODE_ZOOM for full-screen display
 * - Role rotation on swipe: no player creation/destruction, just role reassignment
 * - Pre-loading: NEXT player starts buffering before user swipes
 *
 * Lifecycle:
 * 1. initialize(context) — creates 3 players
 * 2. prepareCurrent(videoUrl, audioUrl) — loads media into CURRENT player
 * 3. prepareNext(videoUrl, audioUrl) — pre-loads NEXT player
 * 4. swipeForward() — rotates: CURRENT→PREVIOUS, NEXT→CURRENT, PREVIOUS→NEXT
 * 5. swipeBackward() — rotates: CURRENT→NEXT, PREVIOUS→CURRENT, NEXT→PREVIOUS
 * 6. release() — destroys all players
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

    private val equalizers = arrayOfNulls<EqualizerAudioProcessor>(POOL_SIZE)
    private val audioSessionAnnouncers = arrayOfNulls<AudioSessionAnnouncer>(POOL_SIZE)
    private var equalizerScope: CoroutineScope? = null

    private var isInitialized = false
    private var dataSourceFactory: DefaultDataSource.Factory? = null

    /**
     * One session for the whole pool, re-pointed at the active player on every settle, so headset
     * and Bluetooth buttons reach the reel on screen. A session per page used to be built and torn
     * down on every swipe.
     */
    private var mediaSession: MediaSession? = null

    private var activeIndex: Int = -1
    private var activeVideoId: String? = null

    private val _ownershipGeneration = MutableStateFlow(0)
    val ownershipGeneration: StateFlow<Int> = _ownershipGeneration.asStateFlow()

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

    /**
     * Reads through the shared media cache once it exists.
     *
     * Without it every loop of a REPEAT_MODE_ONE short and every swipe back re-downloaded the
     * whole file — the main video player has read through [SharedPlayerCacheProvider] all along,
     * and this pool was the one playback path that did not.
     *
     * Null until the cache has been opened, which cannot happen here: [initialize] runs on the main
     * thread and opening the cache touches SQLite and the filesystem.
     */
    @Volatile
    private var cachedDataSourceFactory: DataSource.Factory? = null

    private var poolScope: CoroutineScope? = null
    private var preferredAudioLanguage: String = "original"
    private var shortsPlaybackMode: String = "loop"
    private var basePlaybackSpeed: Float = 1f
    private var volumeNormalization: Boolean = DEFAULT_VIDEO_VOLUME_NORMALIZATION

    private val preferenceObservers = ShortsPreferenceObservers()

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
        attachSharedCache(appContext)
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

        val effects = EntryPointAccessors.fromApplication(appContext, AudioEffectsEntryPoint::class.java)
        val equalizerSpec = effects.equalizerRepository().processingSpec
        equalizerScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope ->
                scope.launch { equalizerSpec.collect { spec -> equalizers.forEach { it?.setSpec(spec) } } }
            }

        try {
            for (i in 0 until POOL_SIZE) {
                val equalizer = EqualizerAudioProcessor().also { it.setSpec(equalizerSpec.value) }
                equalizers[i] = equalizer
                players[i] =
                    ShortsPlayerFactory
                        .player(
                            appContext,
                            equalizer,
                            dataSourceFactory!!,
                            preferredAudioLanguage,
                        ) { _currentVideoId.value }
                        .also {
                            it.addListener(playingListener)
                            audioSessionAnnouncers[i] =
                                AudioSessionAnnouncer(effects.audioSessionRegistry(), AudioEffect.CONTENT_TYPE_MOVIE)
                                    .apply { attach(it) }
                        }
                slots.clear(i)
            }
            mediaSession = ShortsPlayerFactory.mediaSession(appContext, players[0]!!)
            isInitialized = true
            Log.d(TAG, "Player pool initialized with $POOL_SIZE players")
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    /**
     * Points playback at the shared media cache, opening it off the main thread if some other
     * player has not already. Until that completes the pool keeps streaming uncached, so a cold
     * cache delays the benefit rather than the first frame.
     */
    private fun attachSharedCache(appContext: Context) {
        SharedPlayerCacheProvider.existing()?.let {
            cachedDataSourceFactory = ShortsPlayerFactory.cachedDataSourceFactory(appContext, it)
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { poolScope = it }
        scope.launch {
            runCatching {
                PlayerCacheManager.preload(appContext)
                SharedPlayerCacheProvider.existing()
            }.onSuccess { cache ->
                if (cache != null) {
                    cachedDataSourceFactory = ShortsPlayerFactory.cachedDataSourceFactory(appContext, cache)
                    Log.d(TAG, "Shorts playback now reads through the shared media cache")
                }
            }.onFailure { Log.w(TAG, "Shared cache unavailable; Shorts will stream uncached", it) }
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
        return slots.videoUrls[ShortsSlotRules.slotFor(index, POOL_SIZE)]
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

        slots.assign(slot, index, videoId, videoUrl, audioUrl, videoDashManifest, audioDashManifest, loudnessGainDb)
        applyVolume(slot)
        bumpOwnership()

        // Load media
        if (shouldPlay) ShortsStartupTrace.onPrepared(videoId)
        preparePlayerInternal(player, videoUrl, audioUrl, videoDashManifest, audioDashManifest)

        // Set playback state
        player.setPlaybackSpeed(basePlaybackSpeed)
        player.playWhenReady = shouldPlay
        // Set repeat mode based on playback preference: "loop" → REPEAT_MODE_ONE, "auto_next" → REPEAT_MODE_OFF
        player.repeatMode = if (shortsPlaybackMode == "loop") Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

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

        var released = false
        for (i in 0 until POOL_SIZE) {
            val ownerIndex = slots.ownerIndices[i] ?: continue
            if (ShortsSlotRules.shouldRelease(ownerIndex, currentIndex)) {
                Log.d(TAG, "Releasing stale player slot $i (owned by index $ownerIndex, current is $currentIndex)")
                players[i]?.stop()
                players[i]?.clearMediaItems()
                slots.videoIds[i]?.let { ShortsStartupTrace.forget(it) }
                slots.clear(i)
                released = true
            }
        }
        if (released) bumpOwnership()
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
    ) {
        if (!isInitialized || index < 0) return
        val slot = index % POOL_SIZE
        val player = players[slot] ?: return
        if (!slots.owns(slot, index, videoId)) return

        val videoUrl = slots.videoUrls[slot] ?: return
        val wasPlaying = player.isPlaying || player.playWhenReady

        player.stop()
        player.clearMediaItems()
        slots.audioUrls[slot] = newAudioUrl
        slots.audioManifests[slot] = newAudioDashManifest

        preparePlayerInternal(player, videoUrl, newAudioUrl, slots.videoManifests[slot], newAudioDashManifest)
        player.setPlaybackSpeed(basePlaybackSpeed)
        player.playWhenReady = wasPlaying
        player.repeatMode = if (shortsPlaybackMode == "loop") Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
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
    ) {
        if (!isInitialized || index < 0) return
        val slot = index % POOL_SIZE
        val player = players[slot] ?: return
        if (!slots.owns(slot, index, videoId)) return

        val wasPlaying = player.isPlaying || player.playWhenReady
        val position = player.currentPosition

        player.stop()
        player.clearMediaItems()
        slots.videoUrls[slot] = newVideoUrl
        slots.videoManifests[slot] = newVideoDashManifest

        val audioUrl = slots.audioUrls[slot]
        preparePlayerInternal(player, newVideoUrl, audioUrl, newVideoDashManifest, slots.audioManifests[slot])
        player.setPlaybackSpeed(basePlaybackSpeed)
        player.playWhenReady = wasPlaying
        player.seekTo(position)
        player.repeatMode = if (shortsPlaybackMode == "loop") Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    private fun preparePlayerInternal(
        player: ExoPlayer,
        videoUrl: String,
        audioUrl: String?,
        videoDashManifest: String?,
        audioDashManifest: String?,
    ) {
        val factory = cachedDataSourceFactory ?: dataSourceFactory ?: return
        ShortsMediaSources.load(player, factory, videoUrl, audioUrl, videoDashManifest, audioDashManifest)
        player.setPlaybackSpeed(basePlaybackSpeed)
    }

    private fun applyVolume(slot: Int) {
        val gainDb = slots.loudnessGainsDb[slot].takeIf { volumeNormalization }
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
        preferenceObservers.stop()
        poolScope?.cancel()
        poolScope = null
        cachedDataSourceFactory = null
        mediaSession?.release()
        mediaSession = null
        equalizerScope?.cancel()
        equalizerScope = null
        for (i in 0 until POOL_SIZE) {
            audioSessionAnnouncers[i]?.detach()
            audioSessionAnnouncers[i] = null
            equalizers[i] = null
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
        bumpOwnership()
    }

    fun isReady(): Boolean = isInitialized && players[0] != null
}
