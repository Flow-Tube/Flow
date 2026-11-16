package io.github.aedev.flow.sync.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.aedev.flow.R

/**
 * Keeps the LAN transfer alive while the screen is off / app is backgrounded. Declared with `foregroundServiceType="dataSync"` in the manifest. The actual protocol runs
 * in the SyncManager's process-scoped coroutine; this service only holds the foreground promotion
 * + ongoing notification.
 */
class SyncForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ensureChannel(this)
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.sync_notification_title))
                .setContentText(getString(R.string.sync_notification_text))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        val promoted = runCatching { startForegroundCompat(notification) }.isSuccess
        if (gate.promoted() || !promoted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        /** The low-importance Device sync channel, which linked sync shares. */
        const val CHANNEL_ID = "flow_device_sync"
        private const val NOTIF_ID = 4096

        private val gate = SyncForegroundGate()

        /** Returns the token [stop] needs, so a stop from an earlier session can't end this one. */
        fun start(context: Context): Long {
            val token = gate.requestStart()
            val intent = Intent(context, SyncForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: RuntimeException) {
                gate.startFailed()
                throw e
            }
            return token
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                    mgr.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            context.getString(R.string.sync_notification_channel),
                            NotificationManager.IMPORTANCE_LOW,
                        ),
                    )
                }
            }
        }

        fun stop(
            context: Context,
            token: Long,
        ) {
            if (gate.requestStop(token) == SyncForegroundGate.Stop.NOW) {
                context.stopService(Intent(context, SyncForegroundService::class.java))
            }
        }
    }
}
