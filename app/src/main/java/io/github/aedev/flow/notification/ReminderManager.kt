package io.github.aedev.flow.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.util.Calendar

object ReminderManager {
    private const val ALARM_ID_BEDTIME = 101

    fun scheduleBedtimeReminder(
        context: Context,
        hour: Int,
        minute: Int,
    ) {
        if (!canScheduleExactAlarms(context)) {
            // If we can't schedule exact alarms, we can try to schedule inexact ones or just return.
            // For now, we'll try to schedule, and if it fails, we catch the exception.
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent =
            Intent(context, ReminderReceiver::class.java).apply {
                putExtra("type", "bedtime")
            }

        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                ALARM_ID_BEDTIME,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        // Set calendar to next occurrence of hour:minute
        val calendar =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
            }

        // If time has passed today, schedule for tomorrow
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        scheduleAlarmSafe(context, alarmManager, calendar.timeInMillis, pendingIntent)
    }

    fun cancelBedtimeReminder(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent =
            Intent(context, ReminderReceiver::class.java).apply {
                putExtra("type", "bedtime") // Extras identify the pending intent filter equality in some cases, good practice to match
            }
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                ALARM_ID_BEDTIME,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        alarmManager.cancel(pendingIntent)
    }

    private fun canScheduleExactAlarms(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    private fun scheduleAlarmSafe(
        context: Context,
        alarmManager: AlarmManager,
        triggerTime: Long,
        pendingIntent: PendingIntent,
    ) {
        try {
            if (canScheduleExactAlarms(context)) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent,
                )
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
            // Fallback for when permission is revoked in bg
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
