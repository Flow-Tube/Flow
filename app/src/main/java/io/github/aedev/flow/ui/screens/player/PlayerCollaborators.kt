package io.github.aedev.flow.ui.screens.player

import android.content.Context
import io.github.aedev.flow.data.comments.CommentsPager
import io.github.aedev.flow.data.comments.CommentsPlaybackState
import io.github.aedev.flow.data.engagement.VideoEngagementUseCase
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.localmedia.LocalSubtitles
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.data.repository.LiveChatRepository
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import io.github.aedev.flow.data.transcript.TranscriptRepository
import io.github.aedev.flow.data.video.OfflineSubtitleStore
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.stream.UpcomingPremiereProbe
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.richVideoFor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

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
    isLoadCurrent: (Long) -> Boolean,
    currentLoadToken: () -> Long,
    shortsEnabled: () -> Boolean,
    originalTitles: () -> Boolean,
    exclusions: () -> FeedExclusions,
) {
    val comments =
        CommentsPager(
            repository = repository,
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

    private val playbackPreparer =
        PlaybackPreparer(
            context = context,
            playerManager = playerManager,
            playerPreferences = playerPreferences,
            offlineSubtitleStore = offlineSubtitleStore,
            localSubtitles = localSubtitles,
            cachedCategory = repository::cachedVideoCategory,
        )

    val secondaryMetadata =
        PlayerSecondaryMetadataLoader(
            repository = repository,
            playerManager = playerManager,
            playerPreferences = playerPreferences,
            scope = scope,
            networkDispatcher = networkDispatcher,
            currentState = { uiState.value },
            relatedVideosFor = ::relatedVideosFor,
            shortsEnabled = shortsEnabled,
            exclusions = exclusions,
            isPlaybackCurrent = isLoadCurrent,
            onResult = { result -> sessionApplier.applySecondary(result) },
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
            shortsEnabled = shortsEnabled,
            relatedVideosFor = ::relatedVideosFor,
            richVideoFor = { videoId -> uiState.value.richVideoFor(videoId) },
        )

    val liveChat =
        LiveChatController(
            repository = liveChatRepository,
            scope = scope,
            dispatcher = networkDispatcher,
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
                sessionApplier.armCountdownMetadata(LoadContext(videoId, currentLoadToken()), emptyList())
            },
        )

    val sessionApplier: PlaybackSessionApplier =
        PlaybackSessionApplier(
            context = context,
            uiState = uiState,
            isLoadCurrent = isLoadCurrent,
            playbackPreparer = playbackPreparer,
            streamPreparer = PlaybackStreamPreparer(originalText = originalTitles),
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

    private fun relatedVideosFor(videoId: String): List<Video> =
        uiState.value
            .takeIf { it.cachedVideo?.id == videoId }
            ?.relatedVideos
            .orEmpty()
}
