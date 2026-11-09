package io.github.aedev.flow.ui.screens.settings.wellbeing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FreeBreakfast
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.choice
import io.github.aedev.flow.ui.components.settings.notice
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.shared.FlowChoice
import io.github.aedev.flow.ui.components.shared.FlowChoiceDialog
import io.github.aedev.flow.ui.components.stats.spentTimeLabel
import io.github.aedev.flow.ui.screens.settings.index.WellbeingIndex
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class Reminder { BEDTIME, BREAK, DAILY_LIMIT }

private enum class WellbeingDialog { BEDTIME_START, BEDTIME_END, BREAK_FREQUENCY, DAILY_LIMIT_MINUTES }

private val BreakFrequencies = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
private const val MINUTES_PER_DAY = 24 * 60

/** Watch time over the last week, and the reminders and daily limit that help keep it in check. */
@Composable
internal fun WellbeingScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: WellbeingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val bedtimeEnabled by viewModel.bedtimeEnabled.collectAsStateWithLifecycle()
    val breakEnabled by viewModel.breakEnabled.collectAsStateWithLifecycle()
    val bedtimeStart by viewModel.bedtimeStart.collectAsStateWithLifecycle()
    val bedtimeEnd by viewModel.bedtimeEnd.collectAsStateWithLifecycle()
    val breakMinutes by viewModel.breakMinutes.collectAsStateWithLifecycle()
    val dailyLimit by viewModel.dailyLimit.collectAsStateWithLifecycle()
    val pendingLimit by viewModel.pendingLimit.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<WellbeingDialog?>(null) }
    var pending by rememberSaveable { mutableStateOf<Reminder?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val permissionMessage = stringResource(R.string.settings_notification_permission_needed)

    fun enable(reminder: Reminder) =
        when (reminder) {
            Reminder.BEDTIME -> viewModel.setBedtimeEnabled(true)
            Reminder.BREAK -> viewModel.setBreakEnabled(true)
            Reminder.DAILY_LIMIT -> viewModel.changeDailyLimit(dailyLimit.copy(enabled = true))
        }

    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val reminder = pending
            pending = null
            when {
                reminder == null -> {
                    Unit
                }

                granted -> {
                    enable(reminder)
                }

                else -> {
                    // The limit still pauses without notifications; only its warning needs them.
                    if (reminder == Reminder.DAILY_LIMIT) enable(reminder)
                    scope.launch { snackbarHostState.showSnackbar(permissionMessage) }
                }
            }
        }

    fun toggle(
        reminder: Reminder,
        on: Boolean,
    ) {
        val needsPermission =
            on &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        when {
            needsPermission -> {
                pending = reminder
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            on -> {
                enable(reminder)
            }

            reminder == Reminder.BEDTIME -> {
                viewModel.setBedtimeEnabled(false)
            }

            reminder == Reminder.DAILY_LIMIT -> {
                viewModel.changeDailyLimit(dailyLimit.copy(enabled = false))
            }

            else -> {
                viewModel.setBreakEnabled(false)
            }
        }
    }

    val timeFormatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val sleepWindow = sleepWindowLabel(bedtimeStart, bedtimeEnd)
    val bedtimeSummary =
        if (bedtimeEnabled) stringResource(R.string.settings_sleep_window_summary, sleepWindow) else null

    SettingsPage(
        title = stringResource(R.string.settings_wellbeing_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
    ) {
        item("wellbeing.watch_time") { WatchTimeCard(summary) }
        notice("wellbeing.disclaimer", text = { stringResource(R.string.stats_disclaimer) })
        header("wellbeing.tools", R.string.tools_to_manage_time)
        group(key = "wellbeing.bedtime", footer = R.string.bedtime_notification_note) {
            switch(
                WellbeingIndex.bedtime,
                bedtimeEnabled,
                { toggle(Reminder.BEDTIME, it) },
                summary = bedtimeSummary,
                icon = Icons.Outlined.Bedtime,
            )
            if (bedtimeEnabled) {
                choice(WellbeingIndex.bedtimeStart, onClick = { dialog = WellbeingDialog.BEDTIME_START }, icon = Icons.Outlined.Alarm) {
                    bedtimeStart.format(timeFormatter)
                }
                choice(WellbeingIndex.bedtimeEnd, onClick = { dialog = WellbeingDialog.BEDTIME_END }, icon = Icons.Outlined.WbSunny) {
                    bedtimeEnd.format(timeFormatter)
                }
            }
        }
        group(key = "wellbeing.break") {
            switch(WellbeingIndex.breaks, breakEnabled, { toggle(Reminder.BREAK, it) }, icon = Icons.Outlined.FreeBreakfast)
            if (breakEnabled) {
                choice(WellbeingIndex.breakFrequency, onClick = { dialog = WellbeingDialog.BREAK_FREQUENCY }, icon = Icons.Outlined.Timer) {
                    pluralStringResource(R.plurals.every_min_template, breakMinutes, breakMinutes)
                }
            }
        }
        dailyLimitGroup(
            settings = dailyLimit,
            onEnabledChange = { toggle(Reminder.DAILY_LIMIT, it) },
            onPickMinutes = { dialog = WellbeingDialog.DAILY_LIMIT_MINUTES },
            onChange = viewModel::changeDailyLimit,
        )
    }

    pendingLimit?.let { pending ->
        DailyLimitWaitDialog(pending, onConfirm = viewModel::confirmPendingLimit, onDismiss = viewModel::dismissPendingLimit)
    }

    when (dialog) {
        WellbeingDialog.BEDTIME_START -> {
            ReminderTimeDialog(
                title = stringResource(R.string.bedtime_start_title),
                initial = bedtimeStart,
                is24Hour = DateFormat.is24HourFormat(context),
                onConfirm = viewModel::setBedtimeStart,
                onDismiss = { dialog = null },
            )
        }

        WellbeingDialog.BEDTIME_END -> {
            ReminderTimeDialog(
                title = stringResource(R.string.bedtime_end_title),
                initial = bedtimeEnd,
                is24Hour = DateFormat.is24HourFormat(context),
                onConfirm = viewModel::setBedtimeEnd,
                onDismiss = { dialog = null },
            )
        }

        WellbeingDialog.BREAK_FREQUENCY -> {
            FlowChoiceDialog(
                title = stringResource(R.string.frequency_dialog_title),
                description = stringResource(R.string.frequency_description),
                options = BreakFrequencies.map { FlowChoice(it, pluralStringResource(R.plurals.every_minutes_template, it, it)) },
                selected = breakMinutes,
                onSelect = viewModel::setBreakMinutes,
                onDismiss = { dialog = null },
            )
        }

        WellbeingDialog.DAILY_LIMIT_MINUTES -> {
            DailyLimitMinutesDialog(
                selected = dailyLimit.minutes,
                onSelect = { viewModel.changeDailyLimit(dailyLimit.copy(minutes = it)) },
                onDismiss = { dialog = null },
            )
        }

        null -> {
            Unit
        }
    }
}

/** How long the window from [start] to [end] lasts, wrapping past midnight. */
@Composable
private fun sleepWindowLabel(
    start: LocalTime,
    end: LocalTime,
): String {
    val minutes = Math.floorMod(Duration.between(start, end).toMinutes().toInt(), MINUTES_PER_DAY)
    return spentTimeLabel(minutes * MILLIS_PER_MINUTE_LONG)
}

private const val MILLIS_PER_MINUTE_LONG = 60_000L
