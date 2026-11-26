package io.github.aedev.flow.data.local

import io.github.aedev.flow.data.local.dao.NotificationDao
import io.github.aedev.flow.data.local.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository
    @Inject
    constructor(
        private val notificationDao: NotificationDao,
    ) {
        val allNotifications: Flow<List<NotificationEntity>> = notificationDao.getAllNotifications()
        val unreadCount: Flow<Int> = notificationDao.getUnreadCount()

        suspend fun insertNotification(notification: NotificationEntity) {
            notificationDao.insertNotification(notification)
        }

        /** Stores the entries whose video the inbox doesn't hold yet, and returns them. */
        suspend fun recordNew(entries: List<NotificationEntity>): List<NotificationEntity> {
            val unique = entries.distinctBy { it.videoId }
            if (unique.isEmpty()) return emptyList()
            val known =
                unique
                    .map { it.videoId }
                    .chunked(SQLITE_VARIABLE_LIMIT)
                    .flatMapTo(HashSet()) { notificationDao.existingVideoIds(it) }
            val fresh = unique.filterNot { it.videoId in known }
            if (fresh.isNotEmpty()) notificationDao.insertNotifications(fresh)
            return fresh
        }

        suspend fun markAllAsRead() {
            notificationDao.markAllAsRead()
        }

        suspend fun deleteNotification(notification: NotificationEntity) {
            notificationDao.deleteNotification(notification)
        }

        suspend fun clearAll() {
            notificationDao.deleteAllNotifications()
        }

        private companion object {
            const val SQLITE_VARIABLE_LIMIT = 900
        }
    }
