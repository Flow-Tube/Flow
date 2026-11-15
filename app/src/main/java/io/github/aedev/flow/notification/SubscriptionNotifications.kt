package io.github.aedev.flow.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.entity.NotificationEntity
import io.github.aedev.flow.notification.NotificationHelper.CHANNEL_SUBSCRIPTIONS
import io.github.aedev.flow.notification.NotificationHelper.NOTIFICATION_NEW_VIDEO
import io.github.aedev.flow.notification.NotificationHelper.NOTIFICATION_NEW_VIDEO_SUMMARY
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/** The new-upload notifications from a subscription check: one per video, grouped under a summary. */
object SubscriptionNotifications {
    private const val GROUP_NEW_VIDEOS = "new_videos"

    /**
     * Represents a single new video found during a subscription check cycle.
     */
    data class NewVideoEntry(
        val channelName: String,
        val videoTitle: String,
        val videoId: String,
        val thumbnailUrl: String?,
        val kind: NewVideoKind = NewVideoKind.Upload,
    )

    /**
     * Dispatcher for subscription update notifications.
     */
    suspend fun showSubscriptionUpdates(
        context: Context,
        videos: List<NewVideoEntry>,
    ) {
        if (!NotificationHelper.hasNotificationPermission(context)) return
        if (!PlayerPreferences(context).notifNewVideosEnabled.first()) return
        if (videos.isEmpty()) return

        videos.forEach { v ->
            NotificationHelper.storeNotification(
                context,
                NotificationEntity(
                    videoId = v.videoId,
                    title = v.videoTitle,
                    channelName = v.channelName,
                    thumbnailUrl = v.thumbnailUrl,
                    type = "NEW_VIDEO",
                ),
            )
        }

        val manager = NotificationManagerCompat.from(context)
        val multiple = videos.size > 1
        val locale = context.resources.configuration.locales[0]
        val now = System.currentTimeMillis()
        val text = { v: NewVideoEntry ->
            v.kind.text(
                title = v.videoTitle,
                startText = { startTimeText(it, now, ZoneId.systemDefault(), locale) },
                string = { res, args -> context.getString(res, *args) },
            )
        }

        videos.forEach { v ->
            val notifId = NOTIFICATION_NEW_VIDEO + v.videoId.hashCode().and(0xFFFF)
            val watchIntent =
                NotificationHelper.profileIntent(context).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("notification_video_id", v.videoId)
                    putExtra("video_id", v.videoId)
                    putExtra("video_title", v.videoTitle)
                }
            val watchPendingIntent =
                PendingIntent.getActivity(
                    context,
                    notifId,
                    watchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val builder =
                NotificationCompat
                    .Builder(context, CHANNEL_SUBSCRIPTIONS)
                    .setSmallIcon(R.drawable.ic_notification_logo)
                    .setContentTitle(v.channelName)
                    .setContentText(text(v))
                    .setContentIntent(watchPendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                    .setGroup(GROUP_NEW_VIDEOS)
            v.thumbnailUrl?.let { url ->
                NotificationHelper.getBitmapFromUrl(context, url, followThumbnailSetting = true)?.let { bm ->
                    builder.setLargeIcon(bm)
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle().bigPicture(bm).bigLargeIcon(null as Bitmap?),
                    )
                }
            }
            manager.notify(notifId, builder.build())
        }

        if (!multiple) return

        val summaryIntent =
            NotificationHelper.profileIntent(context).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val summaryPendingIntent =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_NEW_VIDEO_SUMMARY,
                summaryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val inboxStyle =
            NotificationCompat
                .InboxStyle()
                .setBigContentTitle(
                    context.resources.getQuantityString(
                        R.plurals.notification_new_videos_from_subscriptions,
                        videos.size,
                        videos.size,
                    ),
                )
        videos.take(6).forEach { v ->
            inboxStyle.addLine("${v.channelName}: ${text(v)}")
        }
        if (videos.size > 6) {
            inboxStyle.setSummaryText(context.getString(R.string.notification_more, videos.size - 6))
        }

        val summaryNotification =
            NotificationCompat
                .Builder(context, CHANNEL_SUBSCRIPTIONS)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.notification_new_videos))
                .setContentText(
                    context.resources.getQuantityString(
                        R.plurals.notification_new_videos_from_subscriptions,
                        videos.size,
                        videos.size,
                    ),
                ).setContentIntent(summaryPendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setStyle(inboxStyle)
                .setGroup(GROUP_NEW_VIDEOS)
                .setGroupSummary(true)
                .setNumber(videos.size)
                .build()

        manager.notify(NOTIFICATION_NEW_VIDEO_SUMMARY, summaryNotification)
    }
}
