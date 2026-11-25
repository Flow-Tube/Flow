package io.github.aedev.flow.ui.screens.player

import android.content.Context
import android.util.Log
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.localmedia.LocalMediaDetails
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.video.AutoDownloadResolution
import io.github.aedev.flow.data.video.AutoDownloadTrigger
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.analytics.VideoStartupTrace
import io.github.aedev.flow.player.stream.PlaybackLoadResolver
import io.github.aedev.flow.player.stream.PlaybackResolutionRequest
import io.github.aedev.flow.player.stream.ResolvedPlayback
import io.github.aedev.flow.ui.screens.player.state.PlayerNavigationHistory
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.beginLoadFor
import io.github.aedev.flow.ui.screens.player.state.blocksLatePrepare
import io.github.aedev.flow.ui.screens.player.state.holdsVideo
import io.github.aedev.flow.ui.screens.player.state.loadSkipReason
import io.github.aedev.flow.ui.screens.player.state.startLocalPlaybackOf
import io.github.aedev.flow.ui.screens.player.state.withDeviceFileDetails
import io.github.aedev.flow.utils.NetworkState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "VideoPlayerViewModel"

/**
 * Starts the player screen's stream loads and keeps the token that says which one is current.
 *
 * One load runs at a time: a new one cancels the last, and whatever a stale load would still write
 * is dropped by the token check [PlaybackSessionApplier] makes on every step. What a resolution
 * reads (the Shorts setting, what the viewer hid) and the back history a load records live here too.
 */
internal class PlaybackLoadController(
    private val context: Context,
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    private val resolver: PlaybackLoadResolver,
    private val playerManager: EnhancedPlayerManager,
    private val playerPreferences: PlayerPreferences,
    private val viewHistory: ViewHistory,
    private val localMediaDetails: LocalMediaDetails,
    private val collaborators: PlayerCollaborators,
    private val notes: PlayerNotes,
    private val recovery: PlaybackRecoveryController,
    private val presence: PlaybackPresenceController,
    private val autoDownload: AutoDownloadTrigger,
    private val scope: CoroutineScope,
    private val networkDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val navigationHistory = PlayerNavigationHistory()
    private val _canGoPrevious = MutableStateFlow(false)
    val canGoPrevious: StateFlow<Boolean> = _canGoPrevious.asStateFlow()

    private var activeLoadJob: Job? = null
    private var loadingVideoId: String? = null

    var token: Long = 0L
        private set

    @Volatile
    var shortsEnabled: Boolean = true
        private set

    @Volatile
    var originalTitles: Boolean = false
        private set

    /**
     * What the viewer hid, so the related list and autoplay drop it the way search and the home
     * feed do. The engine publishes no change signal, so this is re-read when a video loads —
     * the same cadence search re-reads it at, and cheap beside the work a load already does.
     */
    @Volatile
    var exclusions: FeedExclusions = FeedExclusions.NONE
        private set

    val isInFlight: Boolean
        get() = activeLoadJob?.isActive == true

    fun nextToken(): Long {
        token += 1L
        return token
    }

    fun isCurrent(token: Long): Boolean = this.token == token

    fun cancel(invalidateToken: Boolean = false) {
        if (invalidateToken) {
            nextToken()
        }
        activeLoadJob?.cancel()
        activeLoadJob = null
        loadingVideoId = null
        collaborators.secondaryMetadata.cancel()
    }

    fun start() {
        refreshFeedExclusions()
        playerPreferences.shortsContentEnabled
            .onEach { shortsEnabled = it }
            .launchIn(scope)
        playerPreferences.originalTitles
            .onEach { originalTitles = it }
            .launchIn(scope)
    }

    fun clearHistory() {
        navigationHistory.clear()
        _canGoPrevious.value = false
    }

    fun previousVideoId(): String? =
        navigationHistory.previous()?.also {
            _canGoPrevious.value = navigationHistory.canGoPrevious
        }

    /**
     * Resolves [videoId]'s streams and hands each step to [PlaybackSessionApplier].
     * @param forceRefresh If true, forces a fresh load even if the video appears to be already loaded
     * @param escalateToSabr If true (a 403-expiry reload), skip the fast direct-URL clients and
     *   extract straight through the durable WEB+PoToken+SABR path — fast clients return the same
     *   session-gated URLs that just 403'd, so re-trying them loops.
     * @param freshStreams If true (a retry), never play an extraction kept from an earlier open.
     */
    fun load(
        videoId: String,
        isWifi: Boolean,
        forceRefresh: Boolean,
        escalateToSabr: Boolean,
        resumePositionOverrideMs: Long?,
        freshStreams: Boolean = false,
    ) {
        notes.observe(videoId)
        if (LocalMediaIds.isLocal(videoId)) {
            // A device file never touches the network; a queue of them arrives here one by one.
            val uri = LocalMediaIds.videoUri(videoId) ?: return
            val video = uiState.value.cachedVideo?.takeIf { it.id == videoId } ?: return
            prepareDeviceFile(video, uri.toString(), resumePositionOverrideMs)
            return
        }
        val currentState = uiState.value
        Log.d(
            TAG,
            "loadVideoInfo: Request=$videoId. Current=${currentState.cachedVideo?.id}, " +
                "IsLoading=${currentState.isLoading}, ForceRefresh=$forceRefresh, " +
                "escalateToSabr=$escalateToSabr",
        )
        recovery.onLoadStarted(videoId)

        if (collaborators.upcomingPremiere.applyCachedCountdown(videoId)) return

        currentState.loadSkipReason(videoId, forceRefresh)?.let { skip ->
            Log.d(TAG, "Video $videoId skipped: $skip")
            return
        }

        VideoStartupTrace.onRequested(videoId)
        refreshFeedExclusions()
        navigationHistory.push(videoId)
        _canGoPrevious.value = navigationHistory.canGoPrevious

        uiState.value = uiState.value.beginLoadFor(videoId)
        collaborators.liveChat.stop()

        if (activeLoadJob?.isActive == true && loadingVideoId == videoId) {
            Log.d(TAG, "loadVideoInfo: extraction already in flight for $videoId — ignoring redundant trigger")
            return
        }

        cancel()
        val loadToken = nextToken()
        loadingVideoId = videoId
        autoDownload.onLoadStarted(videoId, loadToken)

        val load = LoadContext(videoId, loadToken)
        val sessionApplier = collaborators.sessionApplier
        activeLoadJob =
            scope.launch(networkDispatcher) {
                Log.d(TAG, "Starting loadVideoInfo for $videoId")
                sessionApplier.startDislikeLoad(load)
                try {
                    resolver.resolve(
                        scope = this,
                        request =
                            PlaybackResolutionRequest(
                                videoId = videoId,
                                isWifi = isWifi,
                                escalateToSabr = escalateToSabr,
                                resumePositionOverrideMs = resumePositionOverrideMs,
                                allowShorts = shortsEnabled,
                                blockedChannelIds = exclusions.blockedChannelIds,
                                freshStreams = freshStreams,
                            ),
                        isCurrent = { isCurrent(loadToken) },
                        resolveUpcoming = collaborators.upcomingPremiere::resolve,
                        onStep = { step ->
                            VideoStartupTrace.onResolved(videoId)
                            sessionApplier.apply(step, load)
                            if (step is ResolvedPlayback.VodFromInnerTube && isCurrent(loadToken)) {
                                withContext(Dispatchers.Main) { collaborators.liveChat.startReplay(videoId) }
                            }
                            // A download resumes from history inside the applier; a chosen time wins.
                            if (step is ResolvedPlayback.LocalCopyReady && resumePositionOverrideMs != null && isCurrent(loadToken)) {
                                withContext(Dispatchers.Main) { playerManager.seekTo(resumePositionOverrideMs) }
                            }
                            autoDownload.onResolved(videoId, loadToken, step.autoDownloadResolution(), uiState.value.cachedVideo)
                        },
                    )
                } finally {
                    if (isCurrent(loadToken)) {
                        activeLoadJob = null
                    }
                }
            }
    }

    /** Plays a file on the device, keeping whatever queue it belongs to. */
    fun prepareDeviceFile(
        video: Video,
        contentUri: String,
        startPositionMs: Long? = null,
    ) {
        val loadToken = nextToken()
        uiState.value = uiState.value.startLocalPlaybackOf(video, contentUri)
        GlobalPlayerState.setCurrentVideo(video)
        GlobalPlayerState.setExplicitBackgroundPlaybackActive(false)
        presence.armNotificationFor(video)

        scope.launch {
            collaborators.sessionApplier.prepareLocalMedia(
                load = LoadContext(video.id, loadToken),
                localFilePath = contentUri,
                offlineSegments = null,
                savedPosition = startPositionMs ?: runCatching { viewHistory.getSavedPosition(video.id) }.getOrDefault(0L),
            )
        }
        scope.launch {
            val detailed = localMediaDetails.enrich(video) ?: return@launch
            uiState.update { it.withDeviceFileDetails(detailed) }
            if (GlobalPlayerState.currentVideo.value?.id == detailed.id) GlobalPlayerState.setCurrentVideo(detailed)
        }
    }

    fun syncWith(video: Video) {
        val state = uiState.value
        val alreadySynced =
            state.cachedVideo?.id == video.id &&
                (state.isLoading || state.isLive || !state.hlsUrl.isNullOrEmpty() || state.localFileVideoId == video.id)
        if (alreadySynced) return

        if (collaborators.upcomingPremiere.applyCountdown(video)) {
            return
        }

        uiState.update { it.resetForVideo(video) }
        load(
            video.id,
            isWifi = NetworkState.isOnWifi(context),
            forceRefresh = true,
            escalateToSabr = false,
            resumePositionOverrideMs = null,
        )
    }

    fun ensurePrepared(videoId: String) {
        val state = uiState.value
        if (state.blocksLatePrepare() || !state.holdsVideo(videoId)) return
        if (playerManager.isPreparedForPlayback(videoId)) return

        scope.launch {
            val latest = uiState.value
            if (latest.blocksLatePrepare()) return@launch
            if (playerManager.isPreparedForPlayback(videoId)) return@launch
            collaborators.sessionApplier.armLatePrepare(LoadContext(videoId, token), latest)
        }
    }

    private fun refreshFeedExclusions() {
        scope.launch(ioDispatcher) {
            runCatching { FlowNeuroEngine.getInstance(context).feedExclusions() }.onSuccess { exclusions = it }
        }
    }
}

private fun ResolvedPlayback.autoDownloadResolution(): AutoDownloadResolution =
    when (this) {
        is ResolvedPlayback.VodFromInnerTube -> AutoDownloadResolution.VIDEO

        is ResolvedPlayback.Failed -> AutoDownloadResolution.FAILED

        is ResolvedPlayback.LocalCopyReady,
        is ResolvedPlayback.Live,
        is ResolvedPlayback.Upcoming,
        -> AutoDownloadResolution.NOT_DOWNLOADABLE
    }
