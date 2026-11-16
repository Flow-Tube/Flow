package io.github.aedev.flow.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LocalDataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Posts the bedtime reminder while it is still on in the running profile. A break alarm armed by an older build lands
 * here once and is ignored; [BreakReminderTimer] owns that reminder now.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.getStringExtra("type") != "bedtime") return
        val result = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                remindBedtime(app, LocalDataManager(app))
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun remindBedtime(
        context: Context,
        settings: LocalDataManager,
    ) {
        if (!settings.bedtimeReminder.first()) return
        NotificationHelper.showReminderNotification(
            context,
            context.getString(R.string.reminder_bedtime_title),
            context.getString(R.string.reminder_bedtime_body),
        )
    }
}
