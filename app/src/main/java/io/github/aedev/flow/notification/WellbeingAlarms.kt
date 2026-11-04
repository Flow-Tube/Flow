package io.github.aedev.flow.notification

import android.content.Context
import io.github.aedev.flow.data.local.LocalDataManager
import kotlinx.coroutines.flow.first

/**
 * The bedtime reminder of the running profile. Alarms outlive the process, so a switch cancels it and the next profile
 * arms its own from its settings.
 */
internal object WellbeingAlarms {
    suspend fun arm(
        context: Context,
        settings: LocalDataManager,
    ) {
        if (settings.bedtimeReminder.first()) {
            ReminderManager.scheduleBedtimeReminder(context, settings.bedtimeStartHour.first(), settings.bedtimeStartMinute.first())
        } else {
            ReminderManager.cancelBedtimeReminder(context)
        }
    }

    fun cancel(context: Context) {
        ReminderManager.cancelBedtimeReminder(context)
    }
}
