package io.github.aedev.flow.data.video.downloader.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.MainActivity
import io.github.aedev.flow.R
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What one download's notification shows. */
sealed interface DownloadPhase {
    data object Queued : DownloadPhase

    data class Transferring(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : DownloadPhase {
        val percent: Int get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
    }

    data object Finishing : DownloadPhase

    data object Paused : DownloadPhase

    data class Failed(
        val message: String,
    ) : DownloadPhase

    data class Complete(
        val savedElsewhere: String?,
        /** The track saved in place of the chosen one, which wasn't available. */
        val savedAudioTrack: String? = null,
    ) : DownloadPhase
}

/**
 * The download queue's notifications: one per download, plus the summary that doubles as the
 * foreground notification of the work running the queue. A notification is only re-posted when
 * what it shows changes, which keeps the posts well under the system's update rate limit.
 */
@Singleton
class DownloadNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val manager = context.getSystemService(NotificationManager::class.java)
        private val lastPosted = ConcurrentHashMap<String, String>()

        fun createChannel() {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_downloads_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.notification_download_progress_description) },
            )
        }

        /** The foreground notification: how much of the queue is moving, folded away beside a single download. */
        fun summary(active: Int): Notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_channel_downloads_name))
                .setContentText(
                    if (active > 0) {
                        context.resources.getQuantityString(R.plurals.notification_downloads_active, active, active)
                    } else {
                        context.getString(R.string.download_started_toast)
                    },
                ).setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .setProgress(0, 0, true)
                .setContentIntent(openApp(SUMMARY_NOTIFICATION_ID))
                .setGroup(GROUP)
                .setGroupSummary(true)
                .build()

        fun show(
            videoId: String,
            title: String,
            phase: DownloadPhase,
        ) {
            val signature = "$title|${signatureOf(phase)}"
            if (lastPosted.put(videoId, signature) == signature) return
            manager.notify(notificationId(videoId), build(videoId, title, phase))
        }

        fun dismiss(videoId: String) {
            lastPosted.remove(videoId)
            manager.cancel(notificationId(videoId))
        }

        private fun signatureOf(phase: DownloadPhase): String =
            when (phase) {
                is DownloadPhase.Transferring -> "t${phase.percent}"
                else -> phase.toString()
            }

        private fun build(
            videoId: String,
            title: String,
            phase: DownloadPhase,
        ): Notification {
            val builder =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setContentTitle(title)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setContentIntent(openApp(notificationId(videoId)))
                    .setGroup(GROUP)
            when (phase) {
                DownloadPhase.Queued -> {
                    builder
                        .setContentText(context.getString(R.string.download_status_queued))
                        .setProgress(0, 0, true)
                        .addAction(action(videoId, DownloadActionReceiver.ACTION_PAUSE, R.string.pause))
                        .addAction(action(videoId, DownloadActionReceiver.ACTION_CANCEL, R.string.cancel))
                }

                is DownloadPhase.Transferring -> {
                    builder
                        .setContentText(
                            context.getString(
                                R.string.notification_download_progress,
                                phase.percent,
                                Formatter.formatShortFileSize(context, phase.downloadedBytes),
                                Formatter.formatShortFileSize(context, phase.totalBytes),
                            ),
                        ).setProgress(100, phase.percent, phase.totalBytes <= 0)
                        .addAction(action(videoId, DownloadActionReceiver.ACTION_PAUSE, R.string.pause))
                        .addAction(action(videoId, DownloadActionReceiver.ACTION_CANCEL, R.string.cancel))
                }

                DownloadPhase.Finishing -> {
                    builder
                        .setContentText(context.getString(R.string.download_merging_audio_video))
                        .setProgress(0, 0, true)
                }

                DownloadPhase.Paused -> {
                    builder
                        .setContentText(
                            context.getString(
                                R.string.notification_download_paused,
                                context.getString(R.string.notification_download_paused_hint),
                            ),
                        ).addAction(action(videoId, DownloadActionReceiver.ACTION_RESUME, R.string.resume))
                        .addAction(action(videoId, DownloadActionReceiver.ACTION_CANCEL, R.string.cancel))
                }

                is DownloadPhase.Failed -> {
                    builder
                        .setContentText(phase.message)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(phase.message))
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .addAction(action(videoId, DownloadActionReceiver.ACTION_RETRY, R.string.retry))
                }

                is DownloadPhase.Complete -> {
                    val text =
                        phase.savedAudioTrack?.let { context.getString(R.string.notification_download_other_audio_track, it) }
                            ?: phase.savedElsewhere?.let { context.getString(R.string.notification_download_saved_elsewhere, it) }
                            ?: context.getString(R.string.notification_download_complete)
                    builder
                        .setContentText(text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setAutoCancel(true)
                }
            }
            return builder.build()
        }

        private fun action(
            videoId: String,
            action: String,
            label: Int,
        ): NotificationCompat.Action {
            val intent =
                Intent(context, DownloadActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(DownloadActionReceiver.EXTRA_VIDEO_ID, videoId)
            val pending =
                PendingIntent.getBroadcast(
                    context,
                    "$action:$videoId".hashCode(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            return NotificationCompat.Action(0, context.getString(label), pending)
        }

        private fun openApp(requestCode: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        companion object {
            const val CHANNEL_ID = "flow_downloads"
            private const val GROUP = "flow_download_group"
            const val SUMMARY_NOTIFICATION_ID = 724

            fun notificationId(videoId: String): Int =
                when (val hash = videoId.hashCode()) {
                    0 -> 1
                    SUMMARY_NOTIFICATION_ID -> hash xor Int.MIN_VALUE
                    else -> hash
                }
        }
    }
