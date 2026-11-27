package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.data.scrobble.ScrobbleService
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object ScrobblingIndex {
    private val page = SettingsDestination.SCROBBLING

    private fun entry(
        key: String,
        title: Int,
        section: Int,
        summary: Int? = null,
    ) = SettingEntry(
        key = "scrobbling.$key",
        title = title,
        summary = summary,
        keywords = R.string.settings_keywords_scrobbling,
        section = section,
        destination = page,
    )

    private val accounts =
        mapOf(
            ScrobbleService.LASTFM to entry("lastfm", R.string.scrobbling_account, R.string.scrobbling_lastfm),
            ScrobbleService.LIBREFM to entry("librefm", R.string.scrobbling_account, R.string.scrobbling_librefm),
            ScrobbleService.LISTENBRAINZ to entry("listenbrainz", R.string.scrobbling_account, R.string.scrobbling_listenbrainz),
        )

    fun account(service: ScrobbleService): SettingEntry = accounts.getValue(service)

    private val imports =
        ScrobbleService.entries.associateWith { service ->
            entry(
                "${service.name.lowercase()}_import",
                R.string.scrobbling_import,
                accounts.getValue(service).section ?: R.string.scrobbling_title,
                R.string.scrobbling_import_summary,
            )
        }

    fun import(service: ScrobbleService): SettingEntry = imports.getValue(service)

    val ownKey = entry("own_key", R.string.scrobbling_own_key, R.string.scrobbling_lastfm, R.string.scrobbling_own_key_summary)
    val ownKeyValues = entry("own_key_values", R.string.scrobbling_own_key_values, R.string.scrobbling_lastfm)
    val nowPlaying =
        entry("now_playing", R.string.scrobbling_now_playing, R.string.scrobbling_options, R.string.scrobbling_now_playing_summary)
    val sendLikes = entry("send_likes", R.string.scrobbling_send_likes, R.string.scrobbling_options, R.string.scrobbling_send_likes_summary)
    val local = entry("local", R.string.scrobbling_local, R.string.scrobbling_options, R.string.scrobbling_local_summary)
    val sendNow = entry("send_now", R.string.scrobbling_send_now, R.string.scrobbling_options)
    val addWebhook =
        entry("webhook_add", R.string.scrobbling_webhook_add, R.string.scrobbling_webhooks, R.string.scrobbling_webhook_add_summary)

    val all =
        ScrobbleService.entries.map(::account) + ScrobbleService.entries.map(::import) +
            listOf(ownKey, ownKeyValues, addWebhook, nowPlaying, sendLikes, local, sendNow)
}
