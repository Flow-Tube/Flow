package io.github.aedev.flow.ui.components.shared.quickactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.R
import io.github.aedev.flow.data.engagement.LikedMediaUseCase
import io.github.aedev.flow.data.engagement.VideoEngagementUseCase
import io.github.aedev.flow.data.engagement.VideoFeedbackUseCase
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.video.MusicVideoVersions
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.video.AutoDownloadTrigger
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.VideoDownloadOptions
import io.github.aedev.flow.data.video.VideoDownloadOptionsLoader
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

private const val SHARING_TIMEOUT_MS = 5_000L
private const val MAX_AVATARS = 3

/**
 * State and actions behind every video menu and card: Watch later, subscriptions, downloads, feed
 * feedback and the queue. It never speaks to the viewer itself; each outcome is a
 * [QuickActionMessage] the shell shows on the app's snackbar, with an undo where one exists.
 */
@HiltViewModel
class QuickActionsViewModel
    @Inject
    constructor(
        private val repository: YouTubeRepository,
        private val playlistRepository: PlaylistRepository,
        videoDownloadManager: VideoDownloadManager,
        private val engagement: VideoEngagementUseCase,
        private val feedback: VideoFeedbackUseCase,
        private val downloadOptions: VideoDownloadOptionsLoader,
        private val likedMedia: LikedMediaUseCase,
        private val playerManager: Provider<EnhancedPlayerManager>,
        private val musicVideos: MusicVideoVersions,
        private val autoDownload: AutoDownloadTrigger,
    ) : ViewModel() {
        val watchLaterIds: StateFlow<Set<String>> =
            playlistRepository
                .getWatchLaterIdsFlow()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_TIMEOUT_MS), emptySet())

        private val _subscribedChannelIds = MutableStateFlow<Set<String>>(emptySet())
        val subscribedChannelIds: StateFlow<Set<String>> = _subscribedChannelIds.asStateFlow()

        val downloadedVideoIds: StateFlow<Set<String>> =
            videoDownloadManager.allDownloads
                .map { list ->
                    list
                        .filter { it.overallStatus == DownloadItemStatus.COMPLETED && it.items.isNotEmpty() }
                        .map { it.download.videoId }
                        .toSet()
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARING_TIMEOUT_MS), emptySet())

        private val _messages = MutableSharedFlow<QuickActionMessage>(extraBufferCapacity = 4)
        val messages: SharedFlow<QuickActionMessage> = _messages.asSharedFlow()

        init {
            autoDownload.queued
                .onEach { videoId -> emit(R.string.auto_download_queued, undo = QuickActionUndo.AutoDownload(videoId)) }
                .launchIn(viewModelScope)
        }

        private val _pendingDownload = MutableStateFlow<VideoDownloadOptions?>(null)

        /** The video whose download dialog is open, with its formats already loaded. */
        val pendingDownload: StateFlow<VideoDownloadOptions?> = _pendingDownload.asStateFlow()

        private var downloadJob: Job? = null

        /** Channels already being observed; each collector lives as long as this ViewModel, so one per channel. */
        private val observedChannelIds = mutableSetOf<String>()

        fun loadSubscriptionState(channelId: String) {
            if (!observedChannelIds.add(channelId)) return
            viewModelScope.launch {
                engagement.subscriptionState(channelId).collect { subscribed ->
                    _subscribedChannelIds.update { if (subscribed) it + channelId else it - channelId }
                }
            }
        }

        /** Reads the stored state at the moment of the tap, so a tap before the observer's first value can't subscribe twice. */
        fun toggleSubscription(
            channelId: String,
            channelName: String,
            channelThumbnail: String,
        ) {
            viewModelScope.launch {
                val subscribe = !engagement.subscriptionState(channelId).first()
                setSubscription(channelId, channelName, channelThumbnail, subscribe, announce = true)
            }
        }

        private suspend fun setSubscription(
            channelId: String,
            channelName: String,
            channelThumbnail: String,
            subscribe: Boolean,
            announce: Boolean,
        ) {
            runAction {
                val avatar =
                    if (subscribe) {
                        channelThumbnail.takeIf { it.isNotBlank() && !ThumbnailUrlResolver.isYoutubeVideoThumbnail(it) }
                            ?: repository.fetchChannelAvatarById(channelId)
                    } else {
                        channelThumbnail
                    }
                engagement.applySubscription(
                    channelId = channelId,
                    channelName = channelName,
                    channelThumbnail = avatar,
                    subscribed = subscribe,
                ) { subscribed ->
                    _subscribedChannelIds.update { if (subscribed) it + channelId else it - channelId }
                }
                if (announce) {
                    emit(
                        text = if (subscribe) R.string.toast_subscribed_to else R.string.toast_unsubscribed_from,
                        arg = channelName,
                        undo = QuickActionUndo.Subscription(channelId, channelName, avatar, subscribed = !subscribe),
                    )
                }
            }
        }

        fun toggleWatchLater(video: Video) {
            viewModelScope.launch {
                runAction {
                    val saved = feedback.toggleWatchLater(video)
                    emit(
                        text = if (saved) R.string.toast_added_to_watch_later else R.string.toast_removed_from_watch_later,
                        undo = QuickActionUndo.WatchLater(video, saved = !saved),
                    )
                }
            }
        }

        fun removeFromSavedShorts(video: Video) {
            viewModelScope.launch {
                runAction {
                    val removed = playlistRepository.takeVideosFromPlaylist(PlaylistRepository.SAVED_SHORTS_ID, setOf(video.id))
                    if (removed.isNotEmpty()) emit(R.string.shorts_unsaved, undo = QuickActionUndo.PlaylistRemoval(removed))
                }
            }
        }

        fun blockChannel(video: Video) {
            viewModelScope.launch {
                runAction {
                    val blocked = feedback.blockChannel(video)
                    emit(R.string.channel_blocked_toast, blocked.channelName, QuickActionUndo.ChannelBlock(blocked.channelId))
                }
            }
        }

        fun markNotInterested(video: Video) {
            viewModelScope.launch {
                runAction {
                    feedback.markNotInterested(video)
                    emit(R.string.not_interested_toast)
                }
            }
        }

        fun markAsWatched(video: Video) {
            viewModelScope.launch {
                runAction {
                    feedback.markWatched(video)
                    emit(R.string.mark_as_watched_toast)
                }
            }
        }

        fun markAsUnwatched(video: Video) {
            viewModelScope.launch {
                runAction {
                    feedback.markUnwatched(video)
                    emit(R.string.mark_as_unwatched_toast)
                }
            }
        }

        fun markAsInteresting(video: Video) {
            viewModelScope.launch {
                runAction {
                    feedback.markInterested(video)
                    emit(R.string.i_like_this_toast)
                }
            }
        }

        fun playVideoNext(video: Video) {
            playerManager.get().addVideoToQueueNext(video)
            emit(R.string.play_next_toast)
        }

        /**
         * [video], known only by its id as a shared link brings it, filled in from the watch response.
         * That response is cached, so playing the video afterwards does not fetch it again.
         */
        suspend fun loadLinkedVideo(video: Video): Video? = repository.enrichFromWatchMetadata(video)

        fun addVideoToQueue(video: Video) {
            playerManager.get().addVideoToQueue(video)
            emit(R.string.added_to_queue_toast)
        }

        /** A video shared to Download with Flow: its title and channel first, which name the file. */
        fun requestLinkedDownload(video: Video) {
            viewModelScope.launch { requestDownload(loadLinkedVideo(video) ?: video) }
        }

        /** Loads [video]'s formats, then opens the download dialog; a second tap while loading is ignored. */
        fun requestDownload(video: Video) {
            if (downloadJob?.isActive == true) return
            emit(R.string.toast_fetching_download_links)
            downloadJob =
                viewModelScope.launch {
                    val options = downloadOptions.load(video)
                    if (options == null) emit(R.string.toast_no_download_source) else _pendingDownload.value = options
                }
        }

        /** Confirms something the menu did without the ViewModel, such as a copy on an Android that shows no confirmation. */
        fun announce(
            text: Int,
            arg: String? = null,
        ) = emit(text, arg)

        /** Shows a message a screen already resolved, for wording this menu doesn't own. */
        fun announce(
            text: String,
            undo: QuickActionUndo? = null,
        ) {
            _messages.tryEmit(QuickActionMessage(plainText = text, undo = undo))
        }

        fun dismissDownload() {
            _pendingDownload.value = null
        }

        fun undo(undo: QuickActionUndo) {
            viewModelScope.launch {
                runAction {
                    when (undo) {
                        is QuickActionUndo.WatchLater -> {
                            feedback.setWatchLater(undo.video, undo.saved)
                        }

                        is QuickActionUndo.ChannelBlock -> {
                            feedback.unblockChannel(undo.channelId)
                        }

                        is QuickActionUndo.Subscription -> {
                            setSubscription(undo.channelId, undo.channelName, undo.channelThumbnail, undo.subscribed, announce = false)
                        }

                        is QuickActionUndo.PlaylistRemoval -> {
                            playlistRepository.restorePlaylistVideos(undo.entries)
                        }

                        is QuickActionUndo.Unlike -> {
                            likedMedia.restore(undo.likes)
                        }

                        is QuickActionUndo.RestoreFromTrash -> {
                            Unit
                        }

                        is QuickActionUndo.QueueRemoval -> {
                            playerManager.get().restoreRemovedVideo(undo.entry)
                        }

                        is QuickActionUndo.MusicQueueRemoval -> {
                            EnhancedMusicPlayerManager.restoreToQueue(undo.removed)
                        }

                        is QuickActionUndo.AutoDownload -> {
                            autoDownload.undo(undo.videoId)
                        }
                    }
                }
            }
        }

        /** Avatars for up to three channels, keyed by id, in one bounded round; the repository caches each one. */
        suspend fun channelAvatars(channelIds: List<String>): Map<String, String> {
            val ids = channelIds.filter { it.isNotBlank() }.distinct().take(MAX_AVATARS)
            return ids
                .map { id -> viewModelScope.async { id to runCatching { repository.fetchChannelAvatarById(id) }.getOrDefault("") } }
                .awaitAll()
                .toMap()
        }

        private suspend fun runAction(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(R.string.quick_action_failed)
            }
        }

        /** Opens [track] as a video: itself when it is one, its official music video otherwise. */
        fun watchVideo(
            track: MusicTrack,
            open: (videoId: String) -> Unit,
        ) {
            viewModelScope.launch {
                try {
                    musicVideos.videoFor(track)?.let { open(it.videoId) } ?: emit(R.string.music_video_unavailable)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emit(R.string.music_video_failed)
                }
            }
        }

        private fun emit(
            text: Int,
            arg: String? = null,
            undo: QuickActionUndo? = null,
        ) {
            _messages.tryEmit(QuickActionMessage(text, arg, undo))
        }
    }
