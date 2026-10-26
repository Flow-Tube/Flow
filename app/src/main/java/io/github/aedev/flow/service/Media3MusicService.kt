package io.github.aedev.flow.service

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import io.github.aedev.flow.MainActivity
import io.github.aedev.flow.R
import io.github.aedev.flow.data.audio.eq.EqualizerRepository
import io.github.aedev.flow.data.download.DownloadUtil
import io.github.aedev.flow.data.download.LegacySongDownloads
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.QueuePersistence
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.YouTubeMusicService
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.newmusic.InnertubeMusicService
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import io.github.aedev.flow.data.scrobble.ScrobbleRules
import io.github.aedev.flow.extensions.setOffloadEnabled
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.WatchEndpoint
import io.github.aedev.flow.player.MusicFailureStreak
import io.github.aedev.flow.player.MusicLoadErrorPolicy
import io.github.aedev.flow.player.MusicMutePause
import io.github.aedev.flow.player.MusicPlaybackDiagnostics
import io.github.aedev.flow.player.MusicPlaybackRecoveryPlanner
import io.github.aedev.flow.player.MusicQueuePlanner
import io.github.aedev.flow.player.MusicRadioPlanner
import io.github.aedev.flow.player.MusicResumeTracker
import io.github.aedev.flow.player.PlaylistQueueOrder
import io.github.aedev.flow.player.audio.AudioSessionAnnouncer
import io.github.aedev.flow.player.audio.AudioSessionRegistry
import io.github.aedev.flow.player.audio.LoudnessGain
import io.github.aedev.flow.player.audio.eq.EqualizerAudioProcessor
import io.github.aedev.flow.player.audio.shouldHandleAudioFocus
import io.github.aedev.flow.player.datasource.YouTubeHttpDataSource
import io.github.aedev.flow.player.error.PlayerDiagnostics
import io.github.aedev.flow.player.error.StreamDenialClassifier
import io.github.aedev.flow.player.error.StreamDenialKind
import io.github.aedev.flow.player.factory.LoadControlFactory
import io.github.aedev.flow.player.musicvideo.MusicMediaSourceFactory
import io.github.aedev.flow.player.musicvideo.MusicVideoFormats
import io.github.aedev.flow.player.musicvideo.MusicVideoSwitch
import io.github.aedev.flow.player.restoredMusicQueue
import io.github.aedev.flow.player.sessionArtworkBitmapLoader
import io.github.aedev.flow.player.stream.ClientGateTracker
import io.github.aedev.flow.player.stream.PlaybackBlock
import io.github.aedev.flow.service.auto.AutoLibrary
import io.github.aedev.flow.service.auto.AutoLibraryBrowser
import io.github.aedev.flow.utils.MusicPlayerUtils
import io.github.aedev.flow.utils.NetworkConnectivityObserver
import io.github.aedev.flow.utils.NetworkState
import io.github.aedev.flow.utils.potoken.WebPoTokenSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.min

@AndroidEntryPoint
class Media3MusicService : MediaLibraryService() {
    companion object {
        private const val TAG = "Media3MusicService"
        private const val ACTION_TOGGLE_SHUFFLE = "ACTION_TOGGLE_SHUFFLE"
        private const val ACTION_TOGGLE_REPEAT = "ACTION_TOGGLE_REPEAT"
        private const val ACTION_STOP = "ACTION_STOP"

        private const val MAX_RETRY_PER_SONG = 5
        private const val BASE_RETRY_DELAY_MS = 3000L
        private const val MAX_RETRY_DELAY_MS = 30000L
        private const val FAILED_SONGS_CACHE_SIZE = 50
        private const val RECOVERY_SUCCESS_GRACE_MS = 2 * 60 * 1000L

        // Endless radio: append to the real queue when this few tracks remain,
        // this many at a time, and refill the suggestion pool below this size.
        // LOW_WATER/BATCH mirror the desktop station (3 / 10).
        private const val RADIO_MIN_UPCOMING = 3
        private const val RADIO_APPEND_BATCH = 10
        private const val RADIO_POOL_LOW_WATER = 15
        private const val MUSIC_URI_SCHEME = "music"

        private val CommandToggleShuffle = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
        private val CommandToggleRepeat = SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY)
        private val CommandStop = SessionCommand(ACTION_STOP, Bundle.EMPTY)

        const val ACTION_TOGGLE_LIKE = "ACTION_TOGGLE_LIKE"
        private val CommandToggleLike = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)

        /**
         * Current audio session ID for the music player.
         * External audio processors (like James DSP) can use this to apply effects.
         * Value is 0 when no active session exists.
         */
        @Volatile
        var currentAudioSessionId: Int = 0
            private set

        /**
         * Stops playback and the service from inside it. Sent through the controller, it lands after
         * the player commands the caller already queued, which a direct stopService() overtakes.
         */
        fun requestStop(controller: MediaController) {
            controller.sendCustomCommand(CommandStop, Bundle.EMPTY)
        }
    }

    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var player: ExoPlayer
    private val equalizer = EqualizerAudioProcessor()
    private val musicAudioAttributes =
        AudioAttributes
            .Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
    private var handlesAudioFocus = true
    private var applyingSavedShuffle = false
    private lateinit var connectivityObserver: NetworkConnectivityObserver

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * Coroutine job that defers WakeLock/WifiLock release by 12 seconds after playback pauses.
     * Prevents the CPU from entering deep sleep during brief buffering/focus-loss events.
     */
    private var lockReleaseJob: Job? = null

    private var offloadState = MusicOffloadState()
    private var appliedOffload: Boolean? = null
    private var isOffloaded = false

    private var automixJob: Job? = null

    // ── Endless radio session (desktop semantics: seeded once per queue, append-only) ──
    private var radioSeedId: String? = null
    private var radioContinuation: String? = null
    private var radioEndpoint: WatchEndpoint? = null

    @Volatile private var radioAnchors: List<String> = emptyList()

    @Volatile private var usedRadioAnchors: Set<String> = emptySet()
    private var radioTopUpJob: Job? = null
    private var radioAutoplayEnabled = true
    private var pauseWhenMuted = false
    private var pausedForMute = false
    private var loudnessNormalizationEnabled = true
    private var lastQueueIds: List<String>? = null

    // The item a network failure was deferred for: connectivity can come back long after the
    // playlist has moved on, so the retry must not re-target whatever is current by then.
    private var pendingNetworkRetry: MusicPlaybackRecoveryPlanner.FailedItem? = null

    // Queue-end continuation: appends go through the manager's MediaController and
    // land asynchronously, so a resume at STATE_ENDED must wait for the timeline.
    private var radioResumeWhenAppended = false
    private var radioEndedItemCount = 0

    private val retryCountMap = mutableMapOf<String, Int>()
    private val lastPlaybackErrorAtMap = mutableMapOf<String, Long>()

    private val recentlyFailedSongs = LinkedHashSet<String>()

    private val failureStreak = MusicFailureStreak()

    private var pendingRetryJob: Job? = null

    private var waitingForNetwork = false

    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var legacySongs: LegacySongDownloads

    @Inject
    lateinit var widgetPublisher: io.github.aedev.flow.widget.nowplaying.NowPlayingWidgetPublisher

    @Inject
    lateinit var musicBrain: MusicBrainEngine

    @Inject
    lateinit var scrobbler: io.github.aedev.flow.data.scrobble.Scrobbler

    @Inject
    lateinit var videoSwitch: MusicVideoSwitch

    private val prefs by lazy { PlayerPreferences(this) }

    @Inject
    lateinit var widgetContentSync: dagger.Lazy<io.github.aedev.flow.widget.core.refresh.WidgetContentSync>

    @Inject
    lateinit var equalizerRepository: EqualizerRepository

    @Inject
    lateinit var audioSessions: AudioSessionRegistry
    private var audioSessionAnnouncer: AudioSessionAnnouncer? = null

    @Inject
    lateinit var autoLibrary: AutoLibrary

    @Inject
    lateinit var musicDownloads: io.github.aedev.flow.data.music.DownloadManager

    private val autoBrowser by lazy { AutoLibraryBrowser(this, lifecycleScope, autoLibrary) }

    @Inject
    lateinit var queuePersistence: QueuePersistence

    @Inject
    lateinit var musicResume: MusicResumeTracker

    @Inject
    lateinit var lyricsHelper: io.github.aedev.flow.data.lyrics.LyricsHelper

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        MusicServiceState.isRunning = true

        recordForegroundStartFailures("music-service")

        connectivityObserver = NetworkConnectivityObserver(this)
        connectivityObserver.startObserving()

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Flow:MusicServiceWakeLock")
            wakeLock?.setReferenceCounted(false)

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "Flow:MusicServiceWifiLock")
            wifiLock?.setReferenceCounted(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire locks", e)
        }

        lifecycleScope.launch {
            connectivityObserver.isConnected.collectLatest { isConnected ->
                if (isConnected && waitingForNetwork) {
                    Log.d(TAG, "Network restored, triggering retry")
                    waitingForNetwork = false
                    triggerRetryAfterNetworkRestore()
                }
            }
        }

        lifecycleScope.launch {
            io.github.aedev.flow.player.EnhancedMusicPlayerManager.isLiked.collectLatest {
                updateNotification()
                if (::player.isInitialized) widgetPublisher.publish(player)
            }
        }

        lifecycleScope.launch {
            prefs.musicEndlessRadioEnabled.collect { enabled ->
                val wasEnabled = radioAutoplayEnabled
                radioAutoplayEnabled = enabled
                // Switching it on mid-track otherwise does nothing until the next transition.
                if (enabled && !wasEnabled && ::player.isInitialized) maybeExtendRadio()
            }
        }
        lifecycleScope.launch { prefs.pauseMusicWhenMuted.collect { pauseWhenMuted = it } }
        lifecycleScope.launch {
            prefs.musicLoudnessNormalizationEnabled.collect {
                loudnessNormalizationEnabled = it
                applyLoudnessGain()
            }
        }
        lifecycleScope.launch {
            var lastQuality: io.github.aedev.flow.data.local.MusicAudioQuality? = null
            prefs.musicAudioQuality.collect { quality ->
                val previous = lastQuality
                lastQuality = quality
                if (previous != null && previous != quality) {
                    applyMusicQualityChange()
                }
            }
        }

        initializePlayer()
        initializeSession()
        observeEqualizer()
        // The service, not a saved queue, owns repeat: it outlives the app's process and is saved
        // the moment it changes, so a restart or a new song keeps it.
        lifecycleScope.launch { player.repeatMode = prefs.musicRepeatMode.first() }
        videoSwitch.attach(lifecycleScope, prefs.musicVideoSwitch)

        lifecycleScope.launch {
            prefs.playDuringCalls
                .distinctUntilChanged()
                .collectLatest(::applyPlayDuringCallsPreference)
        }
    }

    private fun applyLoudnessGain() {
        if (!::player.isInitialized) return
        val mediaId = player.currentMediaItem?.mediaId
        val gainDb =
            mediaId
                ?.takeIf { loudnessNormalizationEnabled && !LocalMediaIds.isLocal(it) }
                ?.let(MusicPlayerUtils::cachedLoudnessGainDb)
        player.volume = LoudnessGain.volume(gainDb)
    }

    private fun applyPlayDuringCallsPreference(playDuringCalls: Boolean) {
        val handleAudioFocus = shouldHandleAudioFocus(playDuringCalls)
        if (handlesAudioFocus == handleAudioFocus) return

        handlesAudioFocus = handleAudioFocus
        player.setAudioAttributes(musicAudioAttributes, handleAudioFocus)
        Log.i(TAG, "Music audio focus handling enabled: $handleAudioFocus")
    }

    private fun applyMusicQualityChange() {
        Log.d(TAG, "Music quality changed — clearing resolution caches")
        try {
            downloadUtil.clearUrlCache()
            MusicPlayerUtils.clearPlaybackCache()
            io.github.aedev.flow.player.EnhancedMusicPlayerManager
                .clearUrlCache()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear caches on quality change: ${e.message}")
        }

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (legacySongs.isComplete(mediaId)) return

        try {
            val position = player.currentPosition
            val wasPlaying = player.playWhenReady
            downloadUtil.performAggressiveCacheClear(mediaId)
            refreshStreamMediaItemAt(currentIndex, mediaId, position)
            player.prepare()
            player.playWhenReady = wasPlaying
            Log.d(TAG, "Re-streaming $mediaId at new quality from ${position}ms")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reload current track on quality change: ${e.message}")
        }
    }

    /**
     * Offloaded audio bypasses every audio processor, so offload stays on only while the equalizer
     * leaves the sound untouched. Compare does not count: toggling offload reselects tracks.
     */
    private fun observeEqualizer() {
        lifecycleScope.launch { equalizerRepository.processingSpec.collect(equalizer::setSpec) }
        lifecycleScope.launch {
            equalizerRepository.needsProcessing.collect { processing -> updateOffload { it.copy(processing = processing) } }
        }
    }

    /** Applies [change] to what offload depends on; tracks are reselected only when the answer flips. */
    private fun updateOffload(change: (MusicOffloadState) -> MusicOffloadState) {
        offloadState = change(offloadState)
        val allowed = offloadState.allowed
        if (allowed == appliedOffload) return
        appliedOffload = allowed
        player.setOffloadEnabled(allowed)
    }

    @OptIn(UnstableApi::class)
    private fun observeOffload() {
        lifecycleScope.launch {
            prefs.musicAudioOffloadEnabled.collect { enabled -> updateOffload { it.copy(enabledByUser = enabled) } }
        }
        player.addAudioOffloadListener(
            object : ExoPlayer.AudioOffloadListener {
                override fun onOffloadedPlayback(isOffloadedPlayback: Boolean) {
                    isOffloaded = isOffloadedPlayback
                }
            },
        )
    }

    private fun initializePlayer() {
        val mediaSourceFactory =
            MusicMediaSourceFactory(
                audio =
                    DefaultMediaSourceFactory(downloadUtil.getPlayerDataSourceFactory())
                        .setLoadErrorHandlingPolicy(MusicLoadErrorPolicy { !connectivityObserver.checkCurrentConnectivity() }),
                videoDataSource = DefaultDataSource.Factory(this, YouTubeHttpDataSource.Factory()),
                maxVideoHeight = { MusicVideoFormats.maxHeight(NetworkState.isOnWifi(this)) },
            )

        val renderersFactory =
            object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): androidx.media3.exoplayer.audio.AudioSink? =
                    androidx.media3.exoplayer.audio.DefaultAudioSink
                        .Builder(context)
                        .setAudioProcessors(arrayOf(equalizer))
                        .build()
            }.setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val loadControl = LoadControlFactory.forMusic()

        player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .setRenderersFactory(renderersFactory)
                .setAudioAttributes(musicAudioAttributes, handlesAudioFocus)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setLoadControl(loadControl)
                .setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                // Reports device volume changes, which the pause-when-muted setting listens for.
                .setDeviceVolumeControlEnabled(true)
                .build()

        // Expose audio session ID for external audio processors (James DSP, etc.)
        audioSessionAnnouncer =
            AudioSessionAnnouncer(audioSessions, AudioEffect.CONTENT_TYPE_MUSIC) { sessionId ->
                currentAudioSessionId = sessionId
                Log.i(TAG, "Audio session initialized - Session ID: $sessionId")
            }.also { it.attach(player) }

        updateOffload { it.copy(processing = equalizerRepository.needsProcessing.value) }
        observeOffload()
        setVideoDecoding(false)
        // Shuffle rearranges the queue itself, so the list on screen is the play order. With this
        // order, the player's shuffle flag reorders nothing on its own, whichever controller sets it.
        player.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(0))
        musicResume.attach(player)

        player.addListener(
            object : Player.Listener {
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    updateNotification()
                    // A connected app rearranges the queue it shows; without one (a media button
                    // started the service), this order is the only one, so rearrange it here.
                    val appConnected = io.github.aedev.flow.player.EnhancedMusicPlayerManager.playerInstance.value != null
                    if (shuffleModeEnabled && !applyingSavedShuffle && !appConnected) shuffleFromCurrentItem()
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    updateNotification()
                    lifecycleScope.launch { prefs.setMusicRepeatMode(repeatMode) }
                }

                override fun onMediaItemTransition(
                    mediaItem: androidx.media3.common.MediaItem?,
                    reason: Int,
                ) {
                    // The Song/Video switch trading versions mid-song is still the same listen.
                    val versionSwap =
                        videoSwitch.consumeSwap(mediaItem?.mediaId) &&
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                    if (!versionSwap) {
                        finalizeListenSession()
                        startListenSession(mediaItem?.mediaId)
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) failureStreak.reset()
                    }
                    applyLoudnessGain()

                    mediaItem?.let { item ->
                        val videoId = item.mediaId
                        val title = item.mediaMetadata.title?.toString()
                        val artist = item.mediaMetadata.artist?.toString()

                        if (!videoId.isNullOrBlank() && !LocalMediaIds.isLocal(videoId)) {
                            // Desktop radio semantics: only a genuinely NEW queue seeds a
                            // fresh radio. In-app skips also arrive as PLAYLIST_CHANGED
                            // (playTrack rebuilds the playlist), so the discriminator is
                            // whether the queue CONTENTS changed — never the current track.
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && !versionSwap) {
                                onQueueContextChanged(videoId)
                            } else {
                                maybeExtendRadio()
                            }
                        }

                        if (!versionSwap && !videoId.isNullOrBlank() && !title.isNullOrBlank() && !artist.isNullOrBlank()) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    Log.d(TAG, "Pre-warming lyrics cache in background for: $videoId - \"$title\"")
                                    val durationSeconds = ((item.mediaMetadata.durationMs ?: 0L) / 1000L).toInt()
                                    lyricsHelper.getLyrics(videoId, title, artist, durationSeconds, null)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Lyrics pre-warm background task encountered error: ${e.message}")
                                }
                            }
                        }
                    }

                    widgetPublisher.publish(player)
                }

                override fun onTimelineChanged(
                    timeline: androidx.media3.common.Timeline,
                    reason: Int,
                ) {
                    // Radio tracks appended at queue end arrive asynchronously (the
                    // manager routes addMediaItem through its MediaController) —
                    // resume the moment they actually land in the playlist.
                    if (!radioResumeWhenAppended) return
                    if (player.playbackState != Player.STATE_ENDED) {
                        radioResumeWhenAppended = false
                        return
                    }
                    if (player.mediaItemCount <= radioEndedItemCount) return
                    radioResumeWhenAppended = false
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                    } else {
                        // Shuffle can slot the new items before the current position;
                        // the appended range always starts at the old item count.
                        player.seekTo(radioEndedItemCount, 0L)
                    }
                    player.play()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateLocks(isPlaybackActive())
                    widgetPublisher.publish(player)
                    if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                        // ENDED: the queue ran out — no transition fires for the last track.
                        // IDLE: player.stop() from a dismiss/stop path — same deal.
                        finalizeListenSession()
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        // Radio raced the queue end: append now and keep playing.
                        maybeExtendRadio()
                        if (player.hasNextMediaItem()) {
                            player.seekToNextMediaItem()
                            player.play()
                        }
                    }
                    if (playbackState == Player.STATE_READY) {
                        refreshLearnDuration()
                        if (player.isPlaying) scheduleScrobbleCheck()
                        applyLoudnessGain()
                        failureStreak.reset()
                        player.currentMediaItem?.mediaId?.let { mediaId ->
                            val lastErrorAt = lastPlaybackErrorAtMap[mediaId] ?: 0L
                            if (System.currentTimeMillis() - lastErrorAt > RECOVERY_SUCCESS_GRACE_MS) {
                                retryCountMap.remove(mediaId)
                                recentlyFailedSongs.remove(mediaId)
                                lastPlaybackErrorAtMap.remove(mediaId)
                            }
                        }
                    }
                }

                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    updateLocks(isPlaybackActive())
                    if (playWhenReady) {
                        pausedForMute = false
                        updateOffload { it.copy(pausedLong = false) }
                    }
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    val changed = MusicOffloadState.changesPlayback(playbackParameters.speed, playbackParameters.pitch)
                    updateOffload { it.copy(playbackChanged = changed) }
                }

                override fun onDeviceVolumeChanged(
                    volume: Int,
                    muted: Boolean,
                ) {
                    val action = MusicMutePause.onVolume(muted || volume == 0, pauseWhenMuted, player.isPlaying, pausedForMute)
                    when (action) {
                        MusicMutePause.Action.PAUSE -> {
                            player.pause()
                            pausedForMute = true
                        }

                        MusicMutePause.Action.RESUME -> {
                            pausedForMute = false
                            player.play()
                        }

                        MusicMutePause.Action.NONE -> {}
                    }
                }

                // A music video is decoded only while the player shows it: a collapsed player,
                // a closed app or a locked screen drops the surface and with it the picture. A
                // surface handed over from the app's process reports its size as unknown (-1).
                override fun onSurfaceSizeChanged(
                    width: Int,
                    height: Int,
                ) {
                    setVideoDecoding(width != 0 && height != 0)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateLocks(isPlaybackActive())
                    widgetPublisher.publish(player)
                    if (isPlaying) {
                        lifecycleScope.launch {
                            HeadphoneResume.set(this@Media3MusicService, prefs.resumeFromHeadphones.first())
                        }
                        val playingId = player.currentMediaItem?.mediaId
                        if (learnMediaId == null) learnMediaId = playingId?.let(videoSwitch::listenId)
                        if (learnTrack?.videoId != learnMediaId) learnTrack = listenTrack(playingId)
                        refreshLearnDuration()
                        learnPlayingSinceMs = android.os.SystemClock.elapsedRealtime()
                        announceNowPlaying()
                        scheduleScrobbleCheck()
                    } else {
                        closePlayingSegment()
                    }
                }
            },
        )

        // The item that failed is not always the current one: a next-item preload can fail while
        // the previous track still plays. Only EventTime names the window that actually broke.
        player.addAnalyticsListener(EventLogger("MusicPlayerEvents"))
        player.addAnalyticsListener(MusicPlaybackDiagnostics(player, lifecycleScope))
        player.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onPlayerError(
                    eventTime: AnalyticsListener.EventTime,
                    error: PlaybackException,
                ) {
                    handlePlayerError(error, eventTime.windowIndex)
                }
            },
        )
    }

    // ── Listen-session accounting (feeds MusicBrainEngine) ──
    // Hand-rolled instead of Media3's PlaybackStatsListener, whose internal state
    // machine throws IllegalArgumentException on some transition orders (seen on
    // device with our seekTo(0)-on-transition). Wall-clock time while isPlaying is
    // pause-free and seek-immune; a repeat loop finalizes and restarts a session,
    // so relistens still count once each.

    private var learnMediaId: String? = null
    private var learnTrack: MusicTrack? = null
    private var learnGenre: String? = null
    private var learnDurationMs = 0L
    private var learnPlayedMs = 0L
    private var learnPlayingSinceMs = -1L
    private var learnStartedAtMs = 0L
    private var learnAnnounced = false
    private var learnScrobbled = false
    private var scrobbleCheck: Job? = null

    /** Queues the scrobble the moment the listen counts, so a process killed later in the song cannot lose it. */
    private fun scheduleScrobbleCheck() {
        scrobbleCheck?.cancel()
        if (learnScrobbled || learnPlayingSinceMs < 0) return
        val track = learnTrack?.takeIf { it.videoId == learnMediaId } ?: return
        val durationMs = if (track.duration > 0) track.duration.toLong() * 1000 else learnDurationMs
        val thresholdMs = ScrobbleRules.thresholdMs(durationMs) ?: return
        val playedMs = learnPlayedMs + (android.os.SystemClock.elapsedRealtime() - learnPlayingSinceMs)
        scrobbleCheck =
            lifecycleScope.launch {
                delay((thresholdMs - playedMs).coerceAtLeast(0L))
                if (learnMediaId != track.videoId || learnScrobbled) return@launch
                learnScrobbled = true
                scrobbler.onListened(track, durationMs, thresholdMs, learnStartedAtMs)
            }
    }

    /** Once per listen, when it really starts playing rather than when the item merely loads. */
    private fun announceNowPlaying() {
        val track = learnTrack?.takeIf { it.videoId == learnMediaId } ?: return
        if (learnAnnounced) return
        learnAnnounced = true
        val durationMs = if (track.duration > 0) track.duration.toLong() * 1000 else learnDurationMs
        scrobbler.onNowPlaying(track, durationMs)
    }

    private fun closePlayingSegment() {
        scrobbleCheck?.cancel()
        if (learnPlayingSinceMs >= 0) {
            learnPlayedMs += android.os.SystemClock.elapsedRealtime() - learnPlayingSinceMs
            learnPlayingSinceMs = -1L
        }
    }

    // Queue metadata often ships duration=0 (related/next payloads omit it), so the
    // player's own duration — valid once READY — is the reliable denominator.
    private fun refreshLearnDuration() {
        if (!::player.isInitialized) return
        if (player.currentMediaItem?.mediaId != learnMediaId) return
        val d = player.duration
        if (d > 0) learnDurationMs = d
    }

    private fun resolveLearnTrack(mediaId: String?): MusicTrack? {
        if (mediaId.isNullOrBlank()) return null
        val manager = io.github.aedev.flow.player.EnhancedMusicPlayerManager
        return manager.queue.value.firstOrNull { it.videoId == mediaId }
            ?: manager.currentTrack.value?.takeIf { it.videoId == mediaId }
            ?: manager.automixItems.value.firstOrNull { it.videoId == mediaId }
    }

    /** The track a listen of [mediaId] counts toward: the song when it plays as its swapped-in video. */
    private fun listenTrack(mediaId: String?): MusicTrack? = mediaId?.let(videoSwitch::songFor) ?: resolveLearnTrack(mediaId)

    private fun setVideoDecoding(enabled: Boolean) {
        val parameters = player.trackSelectionParameters
        val disabled = C.TRACK_TYPE_VIDEO in parameters.disabledTrackTypes
        if (disabled != enabled) return
        player.trackSelectionParameters =
            parameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
                .build()
    }

    private fun startListenSession(mediaId: String?) {
        learnMediaId = mediaId?.let(videoSwitch::listenId)
        // Pin the track now: by finalize time a new playlist may have replaced the
        // queue and the outgoing track would no longer resolve.
        learnTrack = listenTrack(mediaId)
        // Pin the genre context too — it belongs to the queue this track started in.
        learnGenre =
            io.github.aedev.flow.player.EnhancedMusicPlayerManager
                .playContextGenre
        learnDurationMs = 0L
        learnPlayedMs = 0L
        learnStartedAtMs = System.currentTimeMillis()
        learnAnnounced = false
        learnScrobbled = false
        learnPlayingSinceMs =
            if (::player.isInitialized && player.isPlaying) android.os.SystemClock.elapsedRealtime() else -1L
        refreshLearnDuration()
        if (learnPlayingSinceMs >= 0) {
            announceNowPlaying()
            scheduleScrobbleCheck()
        }
    }

    private fun finalizeListenSession() {
        closePlayingSegment()
        val mediaId = learnMediaId
        val pinnedTrack = learnTrack
        val pinnedDurationMs = learnDurationMs
        val playedMs = learnPlayedMs
        val pinnedGenre = learnGenre
        val startedAtMs = learnStartedAtMs
        val alreadyScrobbled = learnScrobbled
        learnScrobbled = false
        learnMediaId = null
        learnTrack = null
        learnGenre = null
        learnDurationMs = 0L
        learnPlayedMs = 0L
        if (mediaId.isNullOrBlank() || playedMs <= 0) {
            Log.d(TAG, "listen finalize skipped: id=$mediaId playedMs=$playedMs")
            return
        }

        val track = pinnedTrack?.takeIf { it.videoId == mediaId } ?: resolveLearnTrack(mediaId)
        if (track == null) {
            Log.w(TAG, "listen finalize: no track match for $mediaId")
            return
        }
        val durationMs = if (track.duration > 0) track.duration.toLong() * 1000 else pinnedDurationMs
        if (durationMs <= 0) {
            Log.w(TAG, "listen finalize: no duration for $mediaId")
            return
        }

        Log.d(TAG, "listen finalize: $mediaId playedMs=$playedMs pct=${playedMs.toDouble() / durationMs}")
        // Engine-scoped, NOT lifecycleScope: the finalize from onDestroy runs after
        // this service's scope is already cancelled, and the session must still land.
        musicBrain.onListenSessionAsync(track, playedMs.toDouble() / durationMs, pinnedGenre, playedMs)
        if (!alreadyScrobbled) scrobbler.onListened(track, durationMs, playedMs, startedAtMs)
        widgetContentSync.get().run {
            request(io.github.aedev.flow.widget.core.refresh.WidgetContentKey.ON_REPEAT)
            request(io.github.aedev.flow.widget.core.refresh.WidgetContentKey.WEEK)
        }
    }

    /**
     * Main error handling logic with error-type-specific handlers.
     */
    private fun handlePlayerError(
        error: PlaybackException,
        errorWindowIndex: Int,
    ) {
        val failed =
            MusicPlaybackRecoveryPlanner.resolveFailedItem(
                errorWindowIndex = errorWindowIndex,
                currentIndex = player.currentMediaItemIndex,
                currentPositionMs = player.currentPosition,
                mediaIds = playerMediaIds(),
            )
        if (failed == null) {
            Log.e(TAG, "Player error with no resolvable media item", error)
            return
        }
        val mediaId = failed.mediaId

        Log.e(TAG, "Playback error for $mediaId: ${error.errorCodeName} (code=${error.errorCode})", error)
        lastPlaybackErrorAtMap[mediaId] = System.currentTimeMillis()

        if (MusicLoadErrorPolicy.isUnavailable(error)) {
            Log.w(TAG, "$mediaId is unavailable on every client, skipping without retries")
            PlayerDiagnostics.logWarning(TAG, "music $mediaId unavailable on every client, skipped")
            handleFinalFailure(failed, getString(R.string.music_playback_warning_unavailable), gone = true)
            return
        }

        MusicLoadErrorPolicy.viewerBlockOf(error)?.let { block ->
            Log.w(TAG, "$mediaId refused to this viewer: $block")
            PlayerDiagnostics.logWarning(TAG, "music $mediaId refused: $block")
            // Every next song would hit the same limit, and each try deepens it.
            handleFinalFailure(failed, blockWarning(block), keepSkipping = block != PlaybackBlock.RATE_LIMITED)
            return
        }

        if (recentlyFailedSongs.contains(mediaId)) {
            Log.w(TAG, "$mediaId is in recently failed list, skipping to next")
            skipOrStop(failed, gone = false)
            return
        }

        val currentRetry = retryCountMap.getOrDefault(mediaId, 0)

        if (currentRetry >= MAX_RETRY_PER_SONG) {
            handleFinalFailure(failed)
            return
        }

        performAggressiveCacheClear(mediaId)

        when {
            isAudioRendererError(error) -> {
                Log.d(TAG, "AudioTrack error detected (${error.errorCode}), performing safe recovery")
                handleAudioRendererError(failed, currentRetry)
            }

            isRangeNotSatisfiableError(error) -> {
                Log.d(TAG, "Range Not Satisfiable (416) detected, performing strict recovery")
                handleRangeNotSatisfiableError(failed, currentRetry)
            }

            isPageReloadError(error) -> {
                Log.d(TAG, "Page reload error detected, performing strict recovery")
                handlePageReloadError(failed, currentRetry)
            }

            isExpiredUrlError(error) -> {
                Log.d(TAG, "Expired URL (403) detected, refreshing stream URL")
                reportStreamDenial(error)
                notifyMusicWarning(getString(R.string.music_playback_warning_forbidden))
                handleExpiredUrlError(failed, currentRetry)
            }

            isFileNotFoundError(error) -> {
                Log.d(TAG, "Cache file missing (ENOENT) detected, refreshing stream")
                handleFileNotFoundError(failed, currentRetry)
            }

            !connectivityObserver.checkCurrentConnectivity() || isNetworkError(error) -> {
                Log.d(TAG, "Network-related error detected, waiting for connection")
                notifyMusicWarning(getString(R.string.music_playback_warning_network))
                handleNetworkError(failed, currentRetry)
            }

            else -> {
                Log.d(TAG, "Generic/IO error detected (${error.errorCode}), attempting recovery")
                handleGenericError(failed, currentRetry)
            }
        }
    }

    private fun playerMediaIds(): List<String> = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }

    /**
     * Where the failed item sits in the playlist *now*. Recovery runs after a delay, and the radio
     * appends and queue edits in between move it — an index captured at error time goes stale.
     */
    private fun playerIndexOf(failed: MusicPlaybackRecoveryPlanner.FailedItem): Int =
        MusicQueuePlanner.currentQueueIndex(
            queueIds = playerMediaIds(),
            playerIndex = failed.index,
            currentTrackId = failed.mediaId,
        )

    /** Re-prepares the item that actually failed, never whatever happens to be current. */
    private fun restartFailedItem(
        failed: MusicPlaybackRecoveryPlanner.FailedItem,
        fromStart: Boolean = false,
    ) {
        val index = playerIndexOf(failed)
        if (index == MusicQueuePlanner.INDEX_UNSET) return
        player.seekTo(index, if (fromStart) 0L else failed.resumePositionMs)
        player.prepare()
        player.play()
    }

    private fun shuffleFromCurrentItem() {
        val order = List(player.mediaItemCount) { it }
        if (order.size < 2) return
        val target = PlaylistQueueOrder.shuffleFromCurrent(order, player.currentMediaItemIndex).items
        PlaylistQueueOrder.movesTo(order, target)?.forEach { (from, to) -> player.moveMediaItem(from, to) }
    }

    private fun skipPastFailedItem(failed: MusicPlaybackRecoveryPlanner.FailedItem) {
        val index = playerIndexOf(failed)
        when {
            // Shuffle order only matters from where we stand, so hand the skip to the player.
            index == player.currentMediaItemIndex && player.hasNextMediaItem() -> {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }

            index != MusicQueuePlanner.INDEX_UNSET && index + 1 < player.mediaItemCount -> {
                player.seekTo(index + 1, 0L)
                player.prepare()
                player.play()
            }

            player.repeatMode == Player.REPEAT_MODE_ALL && player.mediaItemCount > 0 -> {
                player.seekTo(0, 0L)
                player.prepare()
                player.play()
            }
        }
    }

    private fun performAggressiveCacheClear(mediaId: String) {
        Log.d(TAG, "Performing aggressive cache clear for $mediaId")
        try {
            downloadUtil.performAggressiveCacheClear(mediaId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear download cache for $mediaId", e)
        }
        try {
            MusicPlayerUtils.forceRefreshForVideo(mediaId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear decryption cache for $mediaId", e)
        }
        try {
            io.github.aedev.flow.player.EnhancedMusicPlayerManager
                .invalidateResolvedStream(mediaId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear resolved stream cache for $mediaId", e)
        }
    }

    private fun httpFailure(error: PlaybackException): HttpDataSource.InvalidResponseCodeException? =
        generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()

    private fun getHttpResponseCode(error: PlaybackException): Int? = httpFailure(error)?.responseCode

    /** Demotes the client a refused url came from, so the retry and every later song skip it. */
    private fun reportStreamDenial(error: PlaybackException) {
        val url = httpFailure(error)?.dataSpec?.uri?.toString() ?: return
        val kind = ClientGateTracker.reportDenied(url)
        if (kind == StreamDenialKind.TOKEN_REJECTED) WebPoTokenSession.reportTokenRejected()
        Log.w(
            TAG,
            "HTTP 403 c=${StreamDenialClassifier.clientOf(url)} itag=${StreamDenialClassifier.itagOf(url)} " +
                "pot=${StreamDenialClassifier.hasPoToken(url)} ${StreamDenialClassifier.describeExpiry(url)} denial=$kind",
        )
    }

    private fun isExpiredUrlError(error: PlaybackException): Boolean = getHttpResponseCode(error) == 403

    private fun isRangeNotSatisfiableError(error: PlaybackException): Boolean = getHttpResponseCode(error) == 416

    private fun isPageReloadError(error: PlaybackException): Boolean {
        val errorMessage = error.message?.lowercase(Locale.ROOT) ?: ""
        val causeMessage = error.cause?.message?.lowercase(Locale.ROOT) ?: ""
        val reloadKeywords = listOf("page needs to be reloaded", "page must be reloaded", "reload")
        return reloadKeywords.any { errorMessage.contains(it) || causeMessage.contains(it) }
    }

    private fun isFileNotFoundError(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

    private fun isAudioRendererError(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED

    private fun isNetworkError(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

    private fun handleAudioRendererError(
        failed: MusicPlaybackRecoveryPlanner.FailedItem,
        currentRetry: Int,
    ) {
        retryCountMap[failed.mediaId] = currentRetry + 1
        retryJobCancel()
        pendingRetryJob =
            lifecycleScope.launch {
                try {
                    player.pause()
                    delay(BASE_RETRY_DELAY_MS * 3)
                    restartFailedItem(failed)
                } catch (e: Exception) {
                    Log.e(TAG, "AudioTrack recovery failed", e)
                    handleFinalFailure(failed)
                }
            }
    }

    private fun handleRangeNotSatisfiableError(
        failed: MusicPlaybackRecoveryPlanner.FailedItem,
        currentRetry: Int,
    ) {
        retryCountMap[failed.mediaId] = currentRetry + 1
        retryJobCancel()
        pendingRetryJob =
            lifecycleScope.launch {
                delay(BASE_RETRY_DELAY_MS)
                try {
                    restartFailedItem(failed, fromStart = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Range retry failed", e)
                }
            }
    }

    private fun handlePageReloadError(
        failed: MusicPlaybackRecoveryPlanner.FailedItem,
        currentRetry: Int,
    ) {
        retryCountMap[failed.mediaId] = currentRetry + 1
        retryJobCancel()
        pendingRetryJob =
            lifecycleScope.launch {
                delay(BASE_RETRY_DELAY_MS * 2)
                try {
                    restartFailedItem(failed)
                } catch (e: Exception) {
                    Log.e(TAG, "Page reload recovery failed", e)
                }
            }
    }

    private fun handleExpiredUrlError(
        failed: MusicPlaybackRecoveryPlanner.FailedItem,
        currentRetry: Int,
    ) {
        val mediaId = failed.mediaId
        retryCountMap[mediaId] = currentRetry + 1
        retryJobCancel()
        pendingRetryJob =
            lifecycleScope.launch {
                delay(BASE_RETRY_DELAY_MS)
                try {
                    downloadUtil.invalidateUrlCache(mediaId)
                    MusicPlayerUtils.forceRefreshForVideo(mediaId)
                    io.github.aedev.flow.player.EnhancedMusicPlayerManager
                        .invalidateResolvedStream(mediaId)
                    player.stop()
                    if (refreshStreamMediaItem(failed)) {
                        player.prepare()
                        player.play()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Expired URL recovery failed", e)
                }
            }
    }

    /**
     * Rebuilds a streaming item so the next prepare resolves a fresh url. Returns false when the
     * item is gone or plays from a local file, which has no url to refresh — rewriting one would
     * silently turn offline playback into a stream.
     */
    private fun refreshStreamMediaItem(failed: MusicPlaybackRecoveryPlanner.FailedItem): Boolean {
        val index = playerIndexOf(failed)
        if (index == MusicQueuePlanner.INDEX_UNSET) return false
        return refreshStreamMediaItemAt(index, failed.mediaId, failed.resumePositionMs)
    }

    private fun refreshStreamMediaItemAt(
        index: Int,
        mediaId: String,
        positionMs: Long,
    ): Boolean {
        val currentItem = player.getMediaItemAt(index)
        if (currentItem.localConfiguration?.uri?.scheme != MUSIC_URI_SCHEME) return false

        val refreshedItem =
            currentItem
                .buildUpon()
                .setUri("$MUSIC_URI_SCHEME://$mediaId")
                .setMediaId(mediaId)
                .setCustomCacheKey(mediaId)
                .build()

        player.replaceMediaItem(index, refreshedItem)
        player.seekTo(index, positionMs)
        return true
    }

    private fun handleFileNotFoundError(
        failed: MusicPlaybackRecoveryPlanner.FailedItem,
        currentRetry: Int,
    ) {
        retryCountMap[failed.mediaId] = currentRetry + 1
        retryJobCancel()
        pendingRetryJob =
            lifecycleScope.launch {
                delay(BASE_RETRY_DELAY_MS)
                try {
                    restartFailedItem(failed)
                } catch (e: Exception) {
                    Log.e(TAG, "File not found recovery failed", e)
                }
            }
    }

    private fun handleNetworkError(
        failed: MusicPlaybackRecoveryPlanner.FailedItem,
        currentRetry: Int,
    ) {
        pendingNetworkRetry = failed
        if (!connectivityObserver.checkCurrentConnectivity()) {
            Log.d(TAG, "No network connectivity, waiting for connection...")
            waitingForNetwork = true
            retryCountMap[failed.mediaId] = currentRetry + 1
        } else {
            scheduleRetry(failed, currentRetry, delayMultiplier = 2.0)
        }
    }

    private fun handleGenericError(
        failed: MusicPlaybackRecoveryPlanner.FailedItem,
        currentRetry: Int,
    ) {
        scheduleRetry(failed, currentRetry, delayMultiplier = 1.5)
    }

    private fun retryJobCancel() {
        pendingRetryJob?.cancel()
        pendingRetryJob = null
    }

    private fun scheduleRetry(
        failed: MusicPlaybackRecoveryPlanner.FailedItem,
        currentRetry: Int,
        delayMultiplier: Double,
    ) {
        val mediaId = failed.mediaId
        retryCountMap[mediaId] = currentRetry + 1
        val baseDelay = (BASE_RETRY_DELAY_MS * delayMultiplier).toLong()
        val delay = min(baseDelay * (1L shl currentRetry), MAX_RETRY_DELAY_MS)

        Log.d(TAG, "Scheduling retry ${currentRetry + 1}/$MAX_RETRY_PER_SONG for $mediaId in ${delay}ms")
        retryJobCancel()
        pendingRetryJob =
            lifecycleScope.launch {
                delay(delay)
                try {
                    restartFailedItem(failed)
                } catch (e: Exception) {
                    Log.e(TAG, "Scheduled retry failed for $mediaId", e)
                }
            }
    }

    private fun blockWarning(block: PlaybackBlock): String =
        getString(
            when (block) {
                PlaybackBlock.REGION -> R.string.error_geo_restricted
                PlaybackBlock.RATE_LIMITED -> R.string.music_playback_warning_rate_limited
                else -> R.string.music_playback_warning_members_or_paid
            },
        )

    private fun skipOrStop(
        failed: MusicPlaybackRecoveryPlanner.FailedItem,
        gone: Boolean,
        keepSkipping: Boolean = true,
    ) {
        if (keepSkipping && failureStreak.skipAfter(gone, player.mediaItemCount)) {
            skipPastFailedItem(failed)
        } else {
            Log.w(TAG, "Stopping on ${failed.mediaId} instead of skipping through more failing songs")
            PlayerDiagnostics.logWarning(TAG, "music stopped on ${failed.mediaId} after a failure streak")
            player.pause()
        }
    }

    private fun handleFinalFailure(
        failed: MusicPlaybackRecoveryPlanner.FailedItem,
        warning: String = getString(R.string.music_playback_warning_final),
        gone: Boolean = false,
        keepSkipping: Boolean = true,
    ) {
        val mediaId = failed.mediaId
        Log.w(TAG, "All retries exhausted for $mediaId, marking as failed")
        notifyMusicWarning(warning)
        retryCountMap.remove(mediaId)
        lastPlaybackErrorAtMap.remove(mediaId)
        if (recentlyFailedSongs.size >= FAILED_SONGS_CACHE_SIZE) {
            recentlyFailedSongs.iterator().next().let { recentlyFailedSongs.remove(it) }
        }
        recentlyFailedSongs.add(mediaId)
        skipOrStop(failed, gone, keepSkipping)
    }

    private fun notifyMusicWarning(message: String) {
        io.github.aedev.flow.player.EnhancedMusicPlayerManager
            .showPlaybackWarning(message)
    }

    private fun stopPlaybackAndService() {
        HeadphoneResume.set(this, allowed = false)
        retryJobCancel()
        waitingForNetwork = false
        if (::player.isInitialized) {
            player.pause()
            player.stop()
            player.clearMediaItems()
        }
        io.github.aedev.flow.player.EnhancedMusicPlayerManager
            .onServiceStopped()
        releaseLocks()
        stopSelf()
    }

    private fun triggerRetryAfterNetworkRestore() {
        val failed = pendingNetworkRetry ?: return
        val currentRetry = retryCountMap.getOrDefault(failed.mediaId, 0)

        if (currentRetry < MAX_RETRY_PER_SONG) {
            Log.d(TAG, "Triggering retry after network restore for ${failed.mediaId}")
            performAggressiveCacheClear(failed.mediaId)

            lifecycleScope.launch {
                delay(1000)
                try {
                    restartFailedItem(failed)
                } catch (e: Exception) {
                    Log.e(TAG, "Network restore retry failed for ${failed.mediaId}", e)
                }
            }
        }
        pendingNetworkRetry = null
    }

    @OptIn(UnstableApi::class)
    private fun initializeSession() {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                action = "io.github.aedev.flow.action.OPEN_MUSIC_PLAYER"
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_music_player", true)
            }
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                1001,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        mediaLibrarySession =
            MediaLibrarySession
                .Builder(this, offloadResumingPlayer(), LibrarySessionCallback())
                .setSessionActivity(pendingIntent)
                .setBitmapLoader(sessionArtworkBitmapLoader(this))
                .build()

        setMediaNotificationProvider(CustomNotificationProvider())

        updateNotification()
    }

    /** The session's player: a resume from a controller turns offload back on before playback starts. */
    private fun offloadResumingPlayer(): Player =
        object : ForwardingPlayer(player) {
            override fun play() {
                updateOffload { it.copy(pausedLong = false) }
                super.play()
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady) updateOffload { it.copy(pausedLong = false) }
                super.setPlayWhenReady(playWhenReady)
            }
        }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession.takeUnless {
            controllerInfo.declinesMediaButtonStart(
                hasMediaItem = ::player.isInitialized && player.currentMediaItem != null,
                canResume = queuePersistence.hasSavedQueueFile() && HeadphoneResume.isAllowed(this),
            )
        }

    /**
     * Prevent aggressive OEM ROMs (Xiaomi MIUI, Samsung OneUI, Huawei EMUI, CRDroid)
     * from killing the music service when the app task is swiped from recents.
     *
     * Without this override Android calls stopSelf() via the default onTaskRemoved,
     * which destroys the foreground service and stops background music playback.
     * Overriding without calling super keeps the service alive, also while a song is still loading.
     *
     * When nothing is playing it stops the way Media3's default does: a raw stopSelf() leaves the
     * user-engaged timeout armed, so the notification update for that pause can put the stopped
     * service back into the foreground (#1025).
     */
    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val keepsPlaying = ::player.isInitialized && player.keepsPlayingOnTaskRemoved(isPlaybackOngoing())
        PlayerDiagnostics.logInfo(TAG, "onTaskRemoved keepsPlaying=$keepsPlaying ${taskRemovalSnapshot()}")
        if (keepsPlaying) return
        pauseAllPlayersAndStopSelf()
    }

    private fun taskRemovalSnapshot(): String {
        if (!::player.isInitialized) return "player=none"
        return "state=${player.playbackState} pwr=${player.playWhenReady} items=${player.mediaItemCount} " +
            "ongoing=${runCatching { isPlaybackOngoing() }.getOrDefault(false)}"
    }

    override fun onDestroy() {
        MusicServiceState.isRunning = false
        // Flush the in-flight listen session before the player goes away.
        finalizeListenSession()

        // Clear audio session ID so external processors know we're gone
        audioSessionAnnouncer?.detach()
        audioSessionAnnouncer = null
        currentAudioSessionId = 0
        Log.i(TAG, "Audio session destroyed")

        widgetPublisher.publishStopped()

        lockReleaseJob?.cancel()
        lockReleaseJob = null

        if (::connectivityObserver.isInitialized) {
            connectivityObserver.stopObserving()
        }

        pendingRetryJob?.cancel()

        if (::mediaLibrarySession.isInitialized) {
            mediaLibrarySession.release()
        }
        if (::player.isInitialized) {
            musicResume.saveCurrent(player)
            player.release()
        }
        releaseLocks()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire()
        }
        if (wifiLock?.isHeld != true) {
            wifiLock?.acquire()
        }
    }

    private fun isPlaybackActive(): Boolean {
        if (!::player.isInitialized) return false
        return player.isPlaying ||
            player.playbackState == Player.STATE_BUFFERING ||
            (
                player.playWhenReady &&
                    player.playbackState != Player.STATE_IDLE &&
                    player.playbackState != Player.STATE_ENDED
            )
    }

    private fun updateLocks(isPlaybackActive: Boolean) {
        lockReleaseJob?.cancel()
        lockReleaseJob = null

        if (isPlaybackActive) {
            acquireLocks()
            return
        }

        lockReleaseJob =
            lifecycleScope.launch {
                delay(12_000L)
                if (!isPlaybackActive()) {
                    releaseLocks()
                    // The system keeps the audio hardware awake for as long as a paused offloaded
                    // track exists (#1367); leaving offload makes the player release it.
                    if (isOffloaded) updateOffload { it.copy(pausedLong = true) }
                    if (!isAppInForeground()) {
                        stopSelf()
                    }
                }
            }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun releaseWifiLock() {
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
    }

    private fun releaseLocks() {
        releaseWakeLock()
        releaseWifiLock()
    }

    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val runningProcess = activityManager.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }
        return when (runningProcess?.importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE,
            -> true

            else -> false
        }
    }

    /**
     * Decides whether this PLAYLIST_CHANGED is a real new queue (reseed the
     * radio) or just an in-queue skip routed through playTrack (extend only).
     */
    private fun onQueueContextChanged(playingId: String) {
        val manager = io.github.aedev.flow.player.EnhancedMusicPlayerManager
        // A swapped-in video belongs to its song's queue context and seeds the radio as that song.
        val currentId = videoSwitch.listenId(playingId)
        val queueIds = manager.queue.value.map { videoSwitch.listenId(it.videoId) }
        val explicitSeedId = manager.pendingRadioSeedId?.let(videoSwitch::listenId)
        manager.pendingRadioSeedId = null

        val context =
            MusicRadioPlanner.resolveQueueContext(
                currentId = currentId,
                queueIds = queueIds,
                previousIds = lastQueueIds,
                explicitSeedId = explicitSeedId,
            )
        lastQueueIds = context.knownIds
        if (!context.reseed) {
            maybeExtendRadio()
            return
        }
        radioSeedId = currentId
        radioContinuation = null
        radioEndpoint = null
        radioAnchors = emptyList()
        usedRadioAnchors = emptySet()
        radioResumeWhenAppended = false
        manager.setRadioStationActive(context.explicit)
        startRadio(currentId)
    }

    private fun startRadio(seedId: String) {
        automixJob?.cancel()
        radioTopUpJob?.cancel()
        io.github.aedev.flow.player.EnhancedMusicPlayerManager
            .setRadioLoading(true)
        automixJob =
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    var page = YouTube.next(WatchEndpoint(playlistId = "RDAMVM$seedId")).getOrNull()
                    val nestedPlaylistId = page?.endpoint?.playlistId
                    if (page != null && page.items.size <= 1 && !nestedPlaylistId.isNullOrBlank()) {
                        page = YouTube.next(WatchEndpoint(playlistId = nestedPlaylistId)).getOrNull() ?: page
                    }
                    if (page == null || page.items.size <= 1) {
                        page = YouTube.next(WatchEndpoint(videoId = seedId)).getOrNull() ?: page
                    }

                    var mapped =
                        page
                            ?.items
                            .orEmpty()
                            .mapNotNull { InnertubeMusicService.convertToMusicTrack(it) }
                            .filterNot { it.videoId == seedId }
                            .distinctBy { it.videoId }

                    if (mapped.isEmpty()) {
                        // Related fallback carries no continuation; the pool later
                        // reseeds from the related tracks closest to the seed.
                        radioContinuation = null
                        radioEndpoint = null
                        mapped =
                            YouTubeMusicService
                                .getRelatedMusic(seedId, 20, audioOnly = true)
                                .filterNot { it.videoId == seedId }
                                .distinctBy { it.videoId }
                    } else {
                        radioContinuation = page?.continuation
                        radioEndpoint = page?.endpoint
                    }

                    radioAnchors = MusicRadioPlanner.radioAnchors(seedId, mapped)
                    usedRadioAnchors = setOf(seedId)

                    // Ordered once, here: the pool IS the up-next list the user reads, so the
                    // queue must be able to take it from the head without re-sequencing.
                    val ranked =
                        musicBrain.sequenceRadioBatch(
                            musicBrain.rankTracks(mapped, "radio"),
                            io.github.aedev.flow.player.EnhancedMusicPlayerManager.currentTrack.value,
                            MusicRadioPlanner.MAX_POOL_SIZE,
                        )
                    Log.d(TAG, "Radio seeded from $seedId: ${ranked.size} tracks, continuation=${radioContinuation != null}")
                    if (ranked.isNotEmpty()) {
                        io.github.aedev.flow.player.EnhancedMusicPlayerManager
                            .updateAutomixItems(ranked)
                        // The queue may already be short (or ended) by the time the
                        // seed arrives — move pool tracks into it right away.
                        withContext(Dispatchers.Main) { maybeExtendRadio() }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error seeding radio", e)
                } finally {
                    io.github.aedev.flow.player.EnhancedMusicPlayerManager
                        .setRadioLoading(false)
                }
            }
    }

    /**
     * Called on ordinary advances (main thread). Moves the next few pool tracks
     * into the REAL queue when it runs short — the queue only ever grows, so
     * nothing the user sees is replaced — and refills the pool in the background.
     */
    private fun maybeExtendRadio() {
        val manager = io.github.aedev.flow.player.EnhancedMusicPlayerManager
        if (!radioAutoplayEnabled && !manager.radioStationActive.value) return
        if (!::player.isInitialized) return
        // Repeat already produces an endless queue — matching desktop.
        if (player.repeatMode != Player.REPEAT_MODE_OFF) return
        val ended = player.playbackState == Player.STATE_ENDED
        // Shuffle keeps meaning "shuffle MY queue" while it plays, but once the
        // shuffled queue is exhausted the radio still has to carry on.
        if (manager.shuffleEnabled.value && !ended) return
        if (manager.currentTrack.value
                ?.videoId
                .let(LocalMediaIds::isLocal)
        ) {
            return
        }

        // At ENDED every item has played, whatever the timeline says (shuffle).
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (!ended && remaining > RADIO_MIN_UPCOMING) return

        val queueIds = manager.queue.value.mapTo(HashSet()) { videoSwitch.listenId(it.videoId) }
        val batch = MusicRadioPlanner.nextBatch(manager.automixItems.value, queueIds, RADIO_APPEND_BATCH)
        if (ended && batch.isNotEmpty() && !radioResumeWhenAppended) {
            radioResumeWhenAppended = true
            radioEndedItemCount = player.mediaItemCount
        }
        batch.forEach { track ->
            manager.addToQueue(track)
            manager.removeAutomixItem(track.videoId)
        }
        if (batch.isNotEmpty()) {
            // Our own growth must not read as a new queue on the next skip.
            lastQueueIds = manager.queue.value.map { videoSwitch.listenId(it.videoId) }
            Log.d(TAG, "Radio appended ${batch.size} tracks to the queue")
        }
        // A dead-ended queue with nothing appendable needs a fetch regardless of
        // pool size — the pool may be all duplicates of what already played.
        if (manager.automixItems.value.size < RADIO_POOL_LOW_WATER || (ended && batch.isEmpty())) extendRadioPool()
    }

    /** Fetch the next radio page and APPEND it to the pool — never replaces. */
    private fun extendRadioPool() {
        if (radioTopUpJob?.isActive == true) return
        io.github.aedev.flow.player.EnhancedMusicPlayerManager
            .setRadioLoading(true)
        radioTopUpJob =
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val manager = io.github.aedev.flow.player.EnhancedMusicPlayerManager
                    val endpoint = radioEndpoint
                    val continuation = radioContinuation
                    val page =
                        if (endpoint != null && continuation != null) {
                            YouTube.next(endpoint, continuation).getOrNull()
                        } else {
                            val anchorId =
                                MusicRadioPlanner
                                    .nextAnchor(radioAnchors, usedRadioAnchors)
                                    ?.also { usedRadioAnchors = usedRadioAnchors + it }
                                    ?: (manager.automixItems.value.lastOrNull() ?: manager.queue.value.lastOrNull())
                                        ?.videoId
                                        ?.takeUnless(LocalMediaIds::isLocal)
                                    ?: return@launch
                            YouTube.next(WatchEndpoint(playlistId = "RDAMVM$anchorId")).getOrNull()
                        }
                    if (page == null) return@launch
                    radioContinuation = page.continuation
                    radioEndpoint = page.endpoint

                    val mapped =
                        page.items
                            .mapNotNull { InnertubeMusicService.convertToMusicTrack(it) }
                            .distinctBy { it.videoId }
                    val tail = manager.automixItems.value.lastOrNull() ?: manager.queue.value.lastOrNull()
                    val ranked =
                        musicBrain.sequenceRadioBatch(
                            musicBrain.rankTracks(mapped, "radio"),
                            tail,
                            MusicRadioPlanner.MAX_POOL_SIZE,
                        )
                    Log.d(TAG, "Radio pool topped up with ${ranked.size} tracks, continuation=${radioContinuation != null}")
                    if (ranked.isNotEmpty()) {
                        manager.appendAutomixItems(ranked)
                        // If the queue ended while this fetch was in flight, feed it
                        // now — no further transition will ever call maybeExtendRadio.
                        // Re-entry is safe: this job is still active, so a nested
                        // extendRadioPool() is a no-op.
                        withContext(Dispatchers.Main) { maybeExtendRadio() }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Radio top-up failed: ${e.message}")
                } finally {
                    io.github.aedev.flow.player.EnhancedMusicPlayerManager
                        .setRadioLoading(false)
                }
            }
    }

    private fun updateNotification() {
        if (!::mediaLibrarySession.isInitialized) return

        val isLiked = io.github.aedev.flow.player.EnhancedMusicPlayerManager.isLiked.value

        val likeButton =
            CommandButton
                .Builder(if (isLiked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(getString(if (isLiked) R.string.unlike else R.string.like))
                .setCustomIconResId(if (isLiked) R.drawable.ic_like_filled else R.drawable.ic_like)
                .setSessionCommand(CommandToggleLike)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .setEnabled(true)
                .build()

        val shuffleOn = player.shuffleModeEnabled

        val (repeatIcon, repeatIconResId) =
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE to R.drawable.ic_repeat_one_on
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL to R.drawable.ic_repeat_on
                else -> CommandButton.ICON_REPEAT_OFF to R.drawable.ic_repeat
            }

        val shuffleButton =
            CommandButton
                .Builder(if (shuffleOn) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(getString(R.string.shuffle))
                .setCustomIconResId(if (shuffleOn) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
                .setSessionCommand(CommandToggleShuffle)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()

        val repeatButton =
            CommandButton
                .Builder(repeatIcon)
                .setDisplayName(getString(R.string.repeat))
                .setCustomIconResId(repeatIconResId)
                .setSessionCommand(CommandToggleRepeat)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()

        val closeButton =
            CommandButton
                .Builder(CommandButton.ICON_STOP)
                .setDisplayName(getString(R.string.close))
                .setCustomIconResId(R.drawable.ic_close)
                .setSessionCommand(CommandStop)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .setEnabled(true)
                .build()

        // The Android 13+ card shows only the first two overflow buttons next to previous and next;
        // the rest stay for older notifications, Auto and Wear (#1347).
        mediaLibrarySession.setMediaButtonPreferences(listOf(shuffleButton, repeatButton, likeButton, closeButton))
    }

    @OptIn(UnstableApi::class)
    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {
        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            lifecycleScope.launch {
                val restored = withContext(Dispatchers.IO) { queuePersistence.restoreQueue() }?.let(::restoredMusicQueue)
                when {
                    restored == null -> {
                        result.setException(IllegalStateException("No saved music queue to resume"))
                    }

                    // A play on an empty player asked for this, but a song the app or a link started
                    // while the saved queue was read is newer, and resuming would replace it.
                    isForPlayback && player.currentMediaItem != null -> {
                        Log.d(TAG, "Resumption skipped: ${player.currentMediaItem?.mediaId} started while the saved queue was read")
                        result.setException(IllegalStateException("A newer queue started while resuming"))
                    }

                    else -> {
                        // A queue saved shuffled is already in its shuffled order. System UI asking for
                        // the recent item only shows it, so the live player's flag stays as it is.
                        if (isForPlayback) {
                            applyingSavedShuffle = true
                            player.shuffleModeEnabled = restored.shuffleEnabled
                            applyingSavedShuffle = false
                        }
                        result.set(musicResumptionItems(restored))
                    }
                }
            }
            return result
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = autoBrowser.root(browser, params)

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = autoBrowser.children(parentId, params)

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = autoBrowser.item(mediaId)

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = autoBrowser.search(session, browser, query, params)

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = autoBrowser.searchResult(query, params)

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            autoBrowser.setMediaItems(mediaItems, startIndex, startPositionMs)
                ?: super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> =
            autoBrowser.addMediaItems(mediaItems) ?: super.onAddMediaItems(mediaSession, controller, mediaItems)

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // Music started from the car joins the app's queue, with its saving, radio and learning,
            // even when the app was never opened (#1221).
            if (session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)) {
                io.github.aedev.flow.player.EnhancedMusicPlayerManager
                    .initialize(applicationContext, musicDownloads::isDownloaded)
            }
            val validCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                    .buildUpon()
                    .add(CommandToggleShuffle)
                    .add(CommandToggleRepeat)
                    .add(CommandToggleLike)
                    .add(CommandStop)
                    .build()
            return MediaSession.ConnectionResult
                .AcceptedResultBuilder(session)
                .setAvailableSessionCommands(validCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_TOGGLE_LIKE) {
                io.github.aedev.flow.player.EnhancedMusicPlayerManager
                    .emitToggleLikeEvent()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            if (customCommand.customAction == ACTION_TOGGLE_SHUFFLE) {
                player.shuffleModeEnabled = !player.shuffleModeEnabled
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            if (customCommand.customAction == ACTION_TOGGLE_REPEAT) {
                val newMode =
                    when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                player.repeatMode = newMode
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            if (customCommand.customAction == ACTION_STOP) {
                stopPlaybackAndService()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    @OptIn(UnstableApi::class)
    private inner class CustomNotificationProvider : DefaultMediaNotificationProvider(this@Media3MusicService) {
        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            customLayout: ImmutableList<CommandButton>,
            showPauseButton: Boolean,
        ): ImmutableList<CommandButton> {
            val playPauseButton =
                CommandButton
                    .Builder(if (showPauseButton) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY)
                    .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                    .setCustomIconResId(if (showPauseButton) R.drawable.ic_pause else R.drawable.ic_play)
                    .setDisplayName(getString(if (showPauseButton) R.string.pause else R.string.play))
                    .setEnabled(playerCommands.contains(Player.COMMAND_PLAY_PAUSE))
                    .build()

            val prevButton =
                CommandButton
                    .Builder(CommandButton.ICON_PREVIOUS)
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .setCustomIconResId(R.drawable.ic_previous)
                    .setDisplayName(getString(R.string.previous))
                    .setEnabled(playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
                    .build()

            val nextButton =
                CommandButton
                    .Builder(CommandButton.ICON_NEXT)
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                    .setCustomIconResId(R.drawable.ic_next)
                    .setDisplayName(getString(R.string.next))
                    .setEnabled(playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT))
                    .build()

            var shuffleButton: CommandButton? = null
            var repeatButton: CommandButton? = null
            var likeButton: CommandButton? = null
            var closeButton: CommandButton? = null

            for (button in customLayout) {
                if (button.sessionCommand?.customAction == ACTION_TOGGLE_SHUFFLE) {
                    shuffleButton = button
                } else if (button.sessionCommand?.customAction == ACTION_TOGGLE_REPEAT) {
                    repeatButton = button
                } else if (button.sessionCommand?.customAction == ACTION_TOGGLE_LIKE) {
                    likeButton = button
                } else if (button.sessionCommand?.customAction == ACTION_STOP) {
                    closeButton = button
                }
            }

            val builder = ImmutableList.builder<CommandButton>()

            likeButton?.let { builder.add(it) }
            shuffleButton?.let { builder.add(it) }
            builder.add(prevButton)
            builder.add(playPauseButton)
            builder.add(nextButton)
            repeatButton?.let { builder.add(it) }
            closeButton?.let { builder.add(it) }

            return builder.build()
        }
    }
}
