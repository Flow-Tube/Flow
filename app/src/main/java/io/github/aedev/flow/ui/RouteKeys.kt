package io.github.aedev.flow.ui

import io.github.aedev.flow.ui.components.layout.navigation.DOWNLOADS_ROUTE
import io.github.aedev.flow.ui.components.layout.navigation.DOWNLOADS_TAB_ROUTE
import io.github.aedev.flow.ui.screens.recap.RecapRoutes
import io.github.aedev.flow.ui.screens.search.SEARCH_ROUTE_PATTERN
import io.github.aedev.flow.ui.screens.settings.wellbeing.TIMES_UP_ROUTE
import io.github.aedev.flow.ui.screens.update.UPDATE_ROUTE

private const val MUSIC_PLAYER_KEY = "musicPlayer"

private val ROUTE_KEYS: Map<String, String> =
    mapOf(
        "onboarding" to "onboarding",
        "home" to "home",
        UPDATE_ROUTE to UPDATE_ROUTE,
        TIMES_UP_ROUTE to TIMES_UP_ROUTE,
        "notifications" to "notifications",
        SHORTS_ROUTE_PATTERN to SHORTS_ROUTE_KEY,
        "subscriptions" to "subscriptions",
        "library" to "library",
        SEARCH_ROUTE_PATTERN to "search",
        EQUALIZER_ROUTE to EQUALIZER_ROUTE,
        "categories" to "categories",
        "settings?target={target}" to "settings",
        RecapRoutes.STATS to "recap",
        RecapRoutes.STORY to "recap_story",
        "donations" to "donations",
        "channel?url={channelUrl}" to "channel",
        "history" to "history",
        "playlists?kind={kind}" to "playlists",
        "playlist/{playlistId}" to "playlist",
        "savedShorts" to "savedShorts",
        "notes" to "notes",
        "localMedia" to "localMedia",
        "music" to "music",
        "moodsAndGenres" to "moodsAndGenres",
        "musicSearch?query={query}" to "musicSearch",
        "musicRecognize" to "musicRecognize",
        "recognitionHistory" to "recognitionHistory",
        "youtube_browse/{browseId}?params={params}" to "youtube_browse",
        MUSIC_PLAYER_ROUTE_PATTERN to MUSIC_PLAYER_KEY,
        ON_REPEAT_SHUFFLE_ROUTE to MUSIC_PLAYER_KEY,
        WIDGET_PLAYLIST_ROUTE to MUSIC_PLAYER_KEY,
        DOWNLOADS_ROUTE to DOWNLOADS_ROUTE,
        DOWNLOADS_TAB_ROUTE to DOWNLOADS_TAB_ROUTE,
    )

/**
 * The shell's key for the screen at [route], or null for screens that leave the key as it was: an
 * artist, album or player page keeps the key of the page that opened it, as it always has.
 */
internal fun routeKeyFor(route: String?): String? = route?.let(ROUTE_KEYS::get)
