package io.github.aedev.flow.ui.screens.settings.wellbeing

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.wellbeing.DailyLimitPolicy
import io.github.aedev.flow.data.wellbeing.DailyLimitSettings
import io.github.aedev.flow.ui.components.settings.SettingsListScope
import io.github.aedev.flow.ui.components.settings.choice
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog
import io.github.aedev.flow.ui.components.shared.FlowChoice
import io.github.aedev.flow.ui.components.shared.FlowChoiceDialog
import io.github.aedev.flow.ui.components.stats.spentTimeLabel
import io.github.aedev.flow.ui.screens.settings.index.WellbeingIndex

private const val MILLIS_PER_MINUTE = 60_000L

/** The daily limit switch, and while it is on, how long it allows and whether music counts. */
internal fun SettingsListScope.dailyLimitGroup(
    settings: DailyLimitSettings,
    onEnabledChange: (Boolean) -> Unit,
    onPickMinutes: () -> Unit,
    onChange: (DailyLimitSettings) -> Unit,
) {
    group(key = "wellbeing.daily_limit", footer = R.string.daily_limit_footer) {
        switch(WellbeingIndex.dailyLimit, settings.enabled, onEnabledChange, icon = Icons.Outlined.HourglassBottom)
        if (settings.enabled) {
            choice(WellbeingIndex.dailyLimitTime, onClick = onPickMinutes, icon = Icons.Outlined.Timelapse) {
                spentTimeLabel(settings.minutes * MILLIS_PER_MINUTE)
            }
            switch(
                WellbeingIndex.dailyLimitMusic,
                settings.countMusic,
                { onChange(settings.copy(countMusic = it)) },
                icon = Icons.Outlined.MusicNote,
            )
        }
    }
}

@Composable
internal fun DailyLimitMinutesDialog(
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    FlowChoiceDialog(
        title = stringResource(R.string.daily_limit_time_title),
        description = stringResource(R.string.daily_limit_time_description),
        options = DailyLimitPolicy.minuteChoices.map { FlowChoice(it, spentTimeLabel(it * MILLIS_PER_MINUTE)) },
        selected = selected,
        onSelect = onSelect,
        onDismiss = onDismiss,
        searchable = false,
    )
}

/** Holds a change to a limit already used up today until its wait has passed. */
@Composable
internal fun DailyLimitWaitDialog(
    pending: PendingLimitChange,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val secondsLeft = rememberSecondsUntil(pending.waitEndsAt)
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.daily_limit_wait_title)) },
        text = { Text(stringResource(R.string.daily_limit_wait_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = secondsLeft == 0) {
                Text(
                    if (secondsLeft == 0) {
                        stringResource(R.string.daily_limit_wait_confirm)
                    } else {
                        stringResource(R.string.daily_limit_wait_countdown, secondsLeft)
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
