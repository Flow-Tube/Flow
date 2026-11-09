package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object WellbeingIndex {
    private val page = SettingsDestination.WELLBEING

    private fun entry(
        key: String,
        title: Int,
        summary: Int? = null,
        revealVia: String? = null,
    ) = SettingEntry(
        key = "wellbeing.$key",
        title = title,
        summary = summary,
        section = R.string.tools_to_manage_time,
        keywords = R.string.settings_keywords_wellbeing,
        revealVia = revealVia,
        destination = page,
    )

    val bedtime = entry("bedtime", R.string.bedtime_reminder_title, R.string.bedtime_reminder_subtitle)
    val bedtimeStart = entry("bedtime_start", R.string.bedtime_start_title, revealVia = "wellbeing.bedtime")
    val bedtimeEnd = entry("bedtime_end", R.string.bedtime_end_title, revealVia = "wellbeing.bedtime")
    val breaks = entry("break", R.string.break_reminder_title, R.string.break_reminder_subtitle)
    val breakFrequency =
        entry("break_frequency", R.string.reminder_frequency, R.string.frequency_description, revealVia = "wellbeing.break")

    val dailyLimit = entry("daily_limit", R.string.daily_limit_title, R.string.daily_limit_subtitle)
    val dailyLimitTime =
        entry(
            "daily_limit_time",
            R.string.daily_limit_time_title,
            R.string.daily_limit_time_description,
            revealVia = "wellbeing.daily_limit",
        )
    val dailyLimitMusic =
        entry(
            "daily_limit_music",
            R.string.daily_limit_music_title,
            R.string.daily_limit_music_subtitle,
            revealVia = "wellbeing.daily_limit",
        )

    val all = listOf(bedtime, bedtimeStart, bedtimeEnd, breaks, breakFrequency, dailyLimit, dailyLimitTime, dailyLimitMusic)
}
