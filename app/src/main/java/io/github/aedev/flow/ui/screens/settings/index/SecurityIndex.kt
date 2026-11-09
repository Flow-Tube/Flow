package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object SecurityIndex {
    private val page = SettingsDestination.SECURITY

    private fun entry(
        key: String,
        title: Int,
        summary: Int? = null,
        revealVia: String? = null,
    ) = SettingEntry(
        key = "security.$key",
        title = title,
        summary = summary,
        section = R.string.app_lock_title,
        keywords = R.string.settings_keywords_security,
        revealVia = revealVia,
        destination = page,
    )

    val appLock = entry("app_lock", R.string.app_lock_title, R.string.app_lock_summary)
    val lockAfter = entry("lock_after", R.string.app_lock_after_title, R.string.app_lock_after_description, revealVia = "security.app_lock")
    val hideInRecents =
        entry(
            "hide_in_recents",
            R.string.app_lock_hide_in_recents_title,
            R.string.app_lock_hide_in_recents_summary,
            revealVia = "security.app_lock",
        )

    val all = listOf(appLock, lockAfter, hideInRecents)
}
