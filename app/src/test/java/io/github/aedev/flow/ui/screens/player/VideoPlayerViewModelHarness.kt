package io.github.aedev.flow.ui.screens.player

import android.content.Context
import io.github.aedev.flow.data.comments.CommentsPageResult
import io.github.aedev.flow.data.engagement.VideoEngagementSignals
import io.github.aedev.flow.data.engagement.VideoEngagementUseCase
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.local.entity.WatchHistoryEntity
import io.github.aedev.flow.data.localmedia.LocalSubtitles
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.repository.LiveChatRepository
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.transcript.TranscriptRepository
import io.github.aedev.flow.data.video.AutoDownloadTrigger
import io.github.aedev.flow.data.video.OfflineSubtitleStore
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.LifecyclePlaybackSettings
import io.github.aedev.flow.player.error.PlayerDiagnostics
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.CaptionTrackResolver
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import io.github.aedev.flow.player.stream.PlaybackLoadResolver
import io.github.aedev.flow.player.stream.UpcomingPremiereProbe
import io.github.aedev.flow.utils.NetworkState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import org.schabi.newpipe.extractor.Page

/**
 * Characterisation harness for [VideoPlayerViewModel]: every constructor dependency is a relaxed
 * mock, every static object the ViewModel reaches is a mockk object spy, and every flow the init
 * block collects is backed by a real [MutableStateFlow]/[MutableSharedFlow] so tests can drive it.
 *
 * The defaults describe a healthy, online, empty device: no downloads, no restored session, no
 * music playing, RYD disabled, Auto quality. Individual tests override what they need.
 */
internal class VideoPlayerViewModelHarness(
    private val testDispatcher: TestDispatcher,
) {
    val context: Context = mockk(relaxed = true)
    val repository: YouTubeRepository = mockk(relaxed = true)
    val transcriptRepository: TranscriptRepository = mockk(relaxed = true)
    val viewHistory: ViewHistory = mockk(relaxed = true)
    val subscriptionRepository: SubscriptionRepository = mockk(relaxed = true)
    val likedVideosRepository: LikedVideosRepository = mockk(relaxed = true)
    val playlistRepository: PlaylistRepository = mockk(relaxed = true)
    val playerPreferences: PlayerPreferences = mockk(relaxed = true)
    val autoDownload: AutoDownloadTrigger = mockk(relaxed = true)
    val videoDownloadManager: VideoDownloadManager = mockk(relaxed = true)
    val offlineSubtitleStore: OfflineSubtitleStore = mockk(relaxed = true)
    val localSubtitles: LocalSubtitles = mockk(relaxed = true)
    val sponsorBlockRepository: SponsorBlockRepository = mockk(relaxed = true)
    val liveChatRepository: LiveChatRepository = mockk(relaxed = true)
    val homeFeedCacheRepository: HomeFeedCacheRepository = mockk(relaxed = true)
    val playerManager: EnhancedPlayerManager = mockk(relaxed = true)
    var startVideosPaused = false
    var openVideosFullscreen = false
    val videoStats: io.github.aedev.flow.data.stats.VideoStatsRecorder = mockk(relaxed = true)

    /**
     * The real use case over the mocked repositories: every engagement assertion in the suite is
     * written against [subscriptionRepository]/[likedVideosRepository], so the seam under test
     * stays the repository call, not the use case.
     */
    val engagement: VideoEngagementUseCase by lazy {
        VideoEngagementUseCase(
            subscriptionRepository = subscriptionRepository,
            likedVideosRepository = likedVideosRepository,
            signals = VideoEngagementSignals(context, repository),
            videoStats = videoStats,
        )
    }

    val playerState = MutableStateFlow(EnhancedPlayerState())
    val playbackAbandonedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val queueVideos = MutableStateFlow<List<Video>>(emptyList())
    val videoQueueStore = mockk<io.github.aedev.flow.data.video.VideoQueueStore>(relaxed = true)
    val musicCurrentTrack = MutableStateFlow<MusicTrack?>(null)
    val autoplayEnabled = MutableStateFlow(true)
    val continueWatchingEnabled = MutableStateFlow(true)
    val rytdEnabled = MutableStateFlow(false)
    val isSubscribed = MutableStateFlow(false)
    val subscription = MutableStateFlow<ChannelSubscription?>(null)
    val likeState = MutableStateFlow<String?>(null)

    init {
        mockkObject(EnhancedPlayerManager.Companion)
        every { EnhancedPlayerManager.getInstance() } returns playerManager
        every { playerManager.playerState } returns playerState
        every { playerManager.playbackAbandonedEvent } returns playbackAbandonedEvent
        every { playerManager.queueVideos } returns queueVideos
        every { playerManager.currentQueueIndexState } returns MutableStateFlow(-1)
        every { playerManager.getPlayer() } returns null
        every { playerManager.isPreparedForPlayback(any()) } returns false
        every { playerManager.isReachedByQueueAdvance(any()) } returns false

        // Reset the real singleton before spying it so the reset is not a recorded call.
        GlobalPlayerState.setCurrentVideo(null)
        GlobalPlayerState.setExplicitBackgroundPlaybackActive(false)
        GlobalPlayerState.hideMiniPlayer()
        mockkObject(GlobalPlayerState)

        mockkObject(EnhancedMusicPlayerManager)
        every { EnhancedMusicPlayerManager.currentTrack } returns musicCurrentTrack
        every { EnhancedMusicPlayerManager.stop() } just Runs
        every { EnhancedMusicPlayerManager.clearCurrentTrack() } just Runs

        mockkObject(NetworkState)
        every { NetworkState.isOnWifi(any()) } returns false
        every { NetworkState.isOnline(any()) } returns true

        mockkObject(InnerTubeVideoStreamExtractor)
        coEvery { InnerTubeVideoStreamExtractor.extract(any(), any()) } returns null

        mockkObject(YouTube)
        coEvery {
            YouTube.player(any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(IllegalStateException("premiere probe stubbed"))

        mockkObject(PlayerDiagnostics)
        every { PlayerDiagnostics.logWarning(any(), any()) } just Runs

        mockkObject(FlowNeuroEngine.Companion)
        coEvery { FlowNeuroEngine.onVideoInteraction(any<Context>(), any(), any(), any()) } just Runs
        every { FlowNeuroEngine.onVideoInteractionAsync(any(), any(), any(), any()) } just Runs

        every { context.applicationContext } returns context
        every { context.getString(any()) } answers { "res:${firstArg<Int>()}" }
        every { context.getString(any(), *anyVararg()) } answers { "res:${firstArg<Int>()}" }

        every { playerPreferences.shortsContentEnabled } returns flowOf(true)
        every { playerPreferences.originalTitles } returns flowOf(false)
        every { playerPreferences.effectiveVideoNotesEnabled } returns flowOf(false)
        every { playerPreferences.miniPlayerContinueWatchingEnabled } returns continueWatchingEnabled
        every { playerPreferences.autoplayEnabled } returns autoplayEnabled
        every { playerPreferences.upcomingVideoReminderIds } returns flowOf(emptySet())
        every { playerPreferences.rytdEnabled } returns rytdEnabled
        every { playerPreferences.defaultQualityWifi } returns flowOf(VideoQuality.AUTO)
        every { playerPreferences.defaultQualityCellular } returns flowOf(VideoQuality.AUTO)
        every { playerPreferences.preferredAudioLanguage } returns flowOf("original")
        every { playerPreferences.preferredSubtitleLanguage } returns flowOf(CaptionTrackResolver.NO_PREFERRED_LANGUAGE)
        every { playerPreferences.videoCodecPriority } returns flowOf("auto")
        every { playerPreferences.rememberPlaybackSpeed } returns flowOf(false)
        every { playerPreferences.playbackSpeed } returns flowOf(1f)
        every { playerPreferences.musicAtNormalSpeed } returns flowOf(false)
        every { playerPreferences.speedPerChannel } returns flowOf(false)

        coEvery { viewHistory.getLatestUnfinishedVideo() } returns null
        every { viewHistory.getPlaybackPosition(any()) } returns flowOf(0L)
        coEvery { viewHistory.getSavedPosition(any()) } returns 0L

        coEvery { videoDownloadManager.findLocalCopy(any()) } returns null
        coEvery { videoDownloadManager.getSponsorBlockData(any()) } returns null
        coEvery { offlineSubtitleStore.load(any()) } returns emptyList()
        coEvery { localSubtitles.picked(any()) } returns emptyList()
        coEvery { localSubtitles.beside(any()) } returns emptyList()

        coEvery { repository.getComments(any()) } returns (emptyList<Comment>() to null as Page?)
        coEvery { repository.getVideoComments(any(), any()) } returns CommentsPageResult.EMPTY

        every { subscriptionRepository.isSubscribed(any()) } returns isSubscribed
        every { subscriptionRepository.getSubscription(any()) } returns subscription
        every { likedVideosRepository.getLikeState(any()) } returns likeState
    }

    private fun lifecyclePlaybackSettings() =
        LifecyclePlaybackSettings(startVideosPaused = startVideosPaused, openVideosFullscreen = openVideosFullscreen)

    fun createViewModel(): VideoPlayerViewModel =
        VideoPlayerViewModel(
            context = context,
            repository = repository,
            transcriptRepository = transcriptRepository,
            viewHistory = viewHistory,
            engagement = engagement,
            playlistRepository = playlistRepository,
            playerPreferences = playerPreferences,
            videoDownloadManager = videoDownloadManager,
            videoQueueStore = videoQueueStore,
            watchLaterCleanup = mockk(relaxed = true),
            offlineSubtitleStore = offlineSubtitleStore,
            localSubtitles = localSubtitles,
            sponsorBlockRepository = sponsorBlockRepository,
            liveChatRepository = liveChatRepository,
            homeFeedCacheRepository = homeFeedCacheRepository,
            playerManager = playerManager,
            upcomingPremiereProbe = UpcomingPremiereProbe(),
            playbackResolver =
                PlaybackLoadResolver(
                    repository = repository,
                    viewHistory = viewHistory,
                    playerPreferences = playerPreferences,
                    videoDownloadManager = videoDownloadManager,
                    sponsorBlockRepository = sponsorBlockRepository,
                    networkDispatcher = testDispatcher,
                    ioDispatcher = testDispatcher,
                ),
            notesRepository = mockk(relaxed = true),
            videoStats = videoStats,
            localMediaDetails = mockk { coEvery { enrich(any()) } returns null },
            lifecyclePlayback = mockk { every { settings } answers { lifecyclePlaybackSettings() } },
            autoDownload = autoDownload,
            resetYouTubeSession = mockk(relaxed = true),
            networkDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )

    fun close() {
        unmockkAll()
    }

    companion object {
        fun video(
            id: String,
            title: String = "Title $id",
            channelId: String = "channel_$id",
            isUpcoming: Boolean = false,
        ): Video =
            Video(
                id = id,
                title = title,
                channelName = "Channel $id",
                channelId = channelId,
                thumbnailUrl = "https://example.invalid/$id.jpg",
                duration = 120,
                viewCount = 1L,
                uploadDate = "2026-01-01",
                channelThumbnailUrl = "https://example.invalid/$channelId-avatar.jpg",
                isUpcoming = isUpcoming,
            )

        fun historyEntity(
            id: String,
            positionMs: Long = 30_000L,
            durationMs: Long = 120_000L,
        ): WatchHistoryEntity =
            WatchHistoryEntity(
                videoId = id,
                position = positionMs,
                duration = durationMs,
                timestamp = 1_000L,
                title = "Title $id",
                thumbnailUrl = "https://example.invalid/$id.jpg",
                channelName = "Channel $id",
                channelId = "channel_$id",
                isMusic = false,
            )
    }
}
