package io.github.aedev.flow.ui.screens.player

import android.content.Context
import io.github.aedev.flow.data.comments.CommentsPager
import io.github.aedev.flow.data.comments.CommentsPlaybackState
import io.github.aedev.flow.data.comments.VideoCommentsSource
import io.github.aedev.flow.data.engagement.VideoEngagementUseCase
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.localmedia.LocalMediaDetails
import io.github.aedev.flow.data.localmedia.LocalSubtitles
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.LiveChatRepository
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.repository.WatchRelatedPages
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import io.github.aedev.flow.data.transcript.TranscriptRepository
import io.github.aedev.flow.data.video.AutoDownloadTrigger
import io.github.aedev.flow.data.video.OfflineSubtitleStore
import io.github.aedev.flow.data.video.SavedVideoQueue
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.innertube.models.response.ChatReplaySeed
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.stream.PlaybackLoadResolver
import io.github.aedev.flow.player.stream.UpcomingPremiereProbe
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.richVideoFor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Builds the collaborators one player screen runs on, in the order they depend on each other, and
 * holds them for the ViewModel that owns their lifetime.
 *
 * Construction only: nothing here reads, fetches, collects or prepares anything. The scope, the
 * state flow and the player manager are the ViewModel's own — this class creates no second one of
 * anything, and every collaborator it builds is built exactly once.
 */
internal class PlayerCollaborators(
    context: Context,
    repository: YouTubeRepository,
    transcriptRepository: TranscriptRepository,
    viewHistory: ViewHistory,
    engagement: VideoEngagementUseCase,
    playerPreferences: PlayerPreferences,
    videoDownloadManager: VideoDownloadManager,
    offlineSubtitleStore: OfflineSubtitleStore,
    localSubtitles: LocalSubtitles,
    sponsorBlockRepository: SponsorBlockRepository,
    liveChatRepository: LiveChatRepository,
    homeFeedCacheRepository: HomeFeedCacheRepository,
    playerManager: EnhancedPlayerManager,
    upcomingPremiereProbe: UpcomingPremiereProbe,
    private val videoStats: VideoStatsRecorder,
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    scope: CoroutineScope,
    networkDispatcher: CoroutineDispatcher,
    ioDispatcher: CoroutineDispatcher,
    playbackResolver: PlaybackLoadResolver,
    localMediaDetails: LocalMediaDetails,
    notes: PlayerNotes,
    autoDownload: AutoDownloadTrigger,
    resumePlayback: (Video) -> Unit,
    savedQueue: suspend () -> SavedVideoQueue?,
    resumeQueue: (SavedVideoQueue) -> Unit,
) {
    private val isLoadCurrent: (Long) -> Boolean = { loads.isCurrent(it) }

    val comments =
        CommentsPager(
            source = VideoCommentsSource(repository),
            scope = scope,
            playbackState =
                uiState.map {
                    CommentsPlaybackState(
                        isPlaybackLoading = it.isLoading,
                        currentVideoId = it.cachedVideo?.id,
                    )
                },
            isCurrentVideo = { videoId -> uiState.value.cachedVideo?.id == videoId },
        )

    val transcripts =
        VideoTranscriptLoader(
            repository = transcriptRepository,
            scope = scope,
            networkDispatcher = networkDispatcher,
        )

    val descriptions =
        VideoDescriptionLoader(
            repository = repository,
            scope = scope,
            networkDispatcher = networkDispatcher,
        )

    val liveCounts =
        LiveCountsRefresher(
            repository = repository,
            uiState = uiState,
            networkDispatcher = networkDispatcher,
        )

    private val playbackPreparer =
        PlaybackPreparer(
            context = context,
            playerManager = playerManager,
            playerPreferences = playerPreferences,
            cachedCategory = repository::cachedVideoCategory,
        )

    private val watchRelatedPages = WatchRelatedPages(repository, networkDispatcher)

    val secondaryMetadata =
        PlayerSecondaryMetadataLoader(
            repository = repository,
            relatedPages = watchRelatedPages,
            playerManager = playerManager,
            playerPreferences = playerPreferences,
            scope = scope,
            networkDispatcher = networkDispatcher,
            currentState = { uiState.value },
            relatedVideosFor = ::relatedVideosFor,
            shortsEnabled = { loads.shortsEnabled },
            exclusions = { loads.exclusions },
            isPlaybackCurrent = isLoadCurrent,
            onResult = { result -> sessionApplier.applySecondary(result) },
        )

    val relatedPages =
        RelatedPagesLoader(
            pages = watchRelatedPages,
            uiState = uiState,
            playerManager = playerManager,
            playerPreferences = playerPreferences,
            scope = scope,
            networkDispatcher = networkDispatcher,
            shortsEnabled = { loads.shortsEnabled },
            exclusions = { loads.exclusions },
        )

    val watchSessions =
        WatchSessionTracker(
            context = context,
            viewHistory = viewHistory,
            repository = repository,
            homeFeedCacheRepository = homeFeedCacheRepository,
            videoStats = videoStats,
            scope = scope,
            networkDispatcher = networkDispatcher,
            shortsEnabled = { loads.shortsEnabled },
            relatedVideosFor = ::relatedVideosFor,
            richVideoFor = { videoId -> uiState.value.richVideoFor(videoId) },
        )

    val liveChat =
        LiveChatController(
            repository = liveChatRepository,
            scope = scope,
            dispatcher = networkDispatcher,
            replay =
                ChatReplayController(
                    repository = liveChatRepository,
                    playback =
                        object : ChatReplayPlayback {
                            override fun isPlaying(videoId: String): Flow<Boolean> =
                                playerManager.playerState
                                    .map { it.isPlaying && it.currentVideoId == videoId }
                                    .distinctUntilChanged()

                            override suspend fun positionMs(): Long = withContext(Dispatchers.Main) { playerManager.getCurrentPosition() }
                        },
                    seed = { videoId -> repository.watchNextResponse(videoId)?.let(ChatReplaySeed::token) },
                ),
        )

    val engagementState =
        PlayerEngagementController(
            engagement = engagement,
            scope = scope,
            state = uiState,
            richVideoFor = { videoId -> uiState.value.richVideoFor(videoId) },
        )

    val upcomingPremiere =
        UpcomingPremiereController(
            context = context,
            uiState = uiState,
            playerPreferences = playerPreferences,
            probe = upcomingPremiereProbe,
            scope = scope,
            isLoadCurrent = isLoadCurrent,
            // A countdown the video's own metadata enters skips the load, so it arms what a load would.
            armMetadata = { videoId ->
                sessionApplier.armCountdownMetadata(LoadContext(videoId, loads.token), emptyList())
            },
        )

    val sessionApplier: PlaybackSessionApplier =
        PlaybackSessionApplier(
            context = context,
            uiState = uiState,
            isLoadCurrent = isLoadCurrent,
            playbackPreparer = playbackPreparer,
            streamPreparer = PlaybackStreamPreparer(originalText = { loads.originalTitles }),
            secondaryMetadata = secondaryMetadata,
            liveChat = liveChat,
            repository = repository,
            viewHistory = viewHistory,
            playerPreferences = playerPreferences,
            sponsorBlockRepository = sponsorBlockRepository,
            videoDownloadManager = videoDownloadManager,
            offlineSubtitleStore = offlineSubtitleStore,
            localSubtitles = localSubtitles,
            playerManager = playerManager,
            scope = scope,
            networkDispatcher = networkDispatcher,
            ioDispatcher = ioDispatcher,
            enterUpcoming = upcomingPremiere::enterCountdown,
            tryEnterUpcoming = upcomingPremiere::tryEnterCountdown,
        )

    val recovery =
        PlaybackRecoveryController(
            context = context,
            uiState = uiState,
            playerManager = playerManager,
            playerPreferences = playerPreferences,
            scope = scope,
            cancelLoad = { loads.cancel(invalidateToken = true) },
        )

    val settings =
        PlaybackSettingsController(
            uiState = uiState,
            playerManager = playerManager,
            playerPreferences = playerPreferences,
            scope = scope,
        )

    val presence =
        PlaybackPresenceController(
            uiState = uiState,
            playerManager = playerManager,
            playerPreferences = playerPreferences,
            viewHistory = viewHistory,
            scope = scope,
            ioDispatcher = ioDispatcher,
            resumePlayback = resumePlayback,
            savedQueue = savedQueue,
            resumeQueue = resumeQueue,
        )

    val loads: PlaybackLoadController =
        PlaybackLoadController(
            context = context,
            uiState = uiState,
            resolver = playbackResolver,
            playerManager = playerManager,
            playerPreferences = playerPreferences,
            viewHistory = viewHistory,
            localMediaDetails = localMediaDetails,
            collaborators = this,
            notes = notes,
            recovery = recovery,
            presence = presence,
            autoDownload = autoDownload,
            scope = scope,
            networkDispatcher = networkDispatcher,
            ioDispatcher = ioDispatcher,
        )

    private fun relatedVideosFor(videoId: String): List<Video> =
        uiState.value
            .takeIf { it.cachedVideo?.id == videoId }
            ?.relatedVideos
            .orEmpty()
}
