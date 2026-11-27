package io.github.aedev.flow.ui.screens.notifications

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.engagement.VideoFeedbackUseCase
import io.github.aedev.flow.data.local.NotificationRepository
import io.github.aedev.flow.data.local.entity.NotificationEntity
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.subscriptions.SubscriptionFeedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationViewModel
    @Inject
    constructor(
        private val repository: NotificationRepository,
        // Lazy: the activity-scoped instance behind the badge never saves anything.
        private val feed: Lazy<SubscriptionFeedRepository>,
        private val videoFeedback: Lazy<VideoFeedbackUseCase>,
    ) : ViewModel() {
        val notifications: StateFlow<List<NotificationEntity>> =
            repository.allNotifications
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val unreadCount: StateFlow<Int> =
            repository.unreadCount
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

        private val _newIds = MutableStateFlow<Set<Int>>(emptySet())

        /** What was unread when the inbox opened; it stays marked new for the rest of the visit. */
        val newIds: StateFlow<Set<Int>> = _newIds.asStateFlow()

        private var inboxOpened = false
        private var lastRemoved: List<NotificationEntity> = emptyList()
        private var lastSaved: Video? = null

        /**
         * Takes the unread snapshot, then clears the badge, and refreshes the subscription feed if it is
         * due, which brings new uploads into the inbox. Only the inbox route calls this: the
         * activity-scoped instance behind the badge must never mark anything read.
         */
        fun openInbox() {
            if (inboxOpened) return
            inboxOpened = true
            viewModelScope.launch {
                _newIds.value =
                    repository.allNotifications
                        .first()
                        .filterNot { it.isRead }
                        .mapTo(HashSet()) { it.id }
                repository.markAllAsRead()
            }
            viewModelScope.launch {
                try {
                    val feed = feed.get()
                    feed.refresh(feed.planAllRefresh(force = false)).collect()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Inbox feed refresh failed", e)
                }
            }
        }

        /**
         * Removes [notifications] together, so one undo brings them all back. False when they are
         * already the removal waiting on undo: a repeated dismiss of the same row changes nothing.
         */
        fun deleteNotifications(notifications: List<NotificationEntity>): Boolean {
            if (notifications.isEmpty() || lastRemoved.map { it.id }.containsAll(notifications.map { it.id })) return false
            lastRemoved = notifications
            viewModelScope.launch { repository.deleteNotifications(notifications) }
            return true
        }

        fun undoDelete() {
            val removed = lastRemoved.ifEmpty { return }
            lastRemoved = emptyList()
            viewModelScope.launch { repository.restoreNotifications(removed.map { it.copy(isRead = true) }) }
        }

        /** Saves [notification]'s video to Watch later; [onResult] gets false when it was already there. */
        fun saveToWatchLater(
            notification: NotificationEntity,
            onResult: (Boolean) -> Unit,
        ) {
            viewModelScope.launch {
                val video = videoFor(notification)
                val saved = videoFeedback.get().saveToWatchLater(video)
                lastSaved = video.takeIf { saved }
                onResult(saved)
            }
        }

        fun undoSave() {
            val video = lastSaved ?: return
            lastSaved = null
            viewModelScope.launch { videoFeedback.get().setWatchLater(video, saved = false) }
        }

        /** The feed's copy carries the channel and length a notification never stored. */
        suspend fun videoFor(notification: NotificationEntity): Video =
            feed.get().cachedVideo(notification.videoId) ?: notification.toVideo()

        fun clearAll() {
            lastRemoved = emptyList()
            viewModelScope.launch { repository.clearAll() }
        }
    }

private const val TAG = "NotificationViewModel"

private fun NotificationEntity.toVideo() =
    Video(
        id = videoId,
        title = title,
        channelName = channelName,
        channelId = "",
        thumbnailUrl = thumbnailUrl.orEmpty(),
        duration = 0,
        viewCount = 0,
        uploadDate = "",
        timestamp = timestamp,
    )
