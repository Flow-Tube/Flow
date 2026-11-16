package io.github.aedev.flow.ui

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.ui.components.layout.navigation.DOWNLOADS_ROUTE
import io.github.aedev.flow.ui.components.layout.navigation.FlowTab
import io.github.aedev.flow.ui.screens.search.SEARCH_ROUTE_PATTERN
import java.net.URI
import java.net.URLEncoder

/** The tab whose root screen [route] is, or null for every screen that is not a tab root. */
internal fun flowTabForDestination(
    route: String?,
    shortsSourceArg: String?,
): FlowTab? =
    when (route) {
        null -> null
        SHORTS_ROUTE_PATTERN -> FlowTab.Shorts.takeIf { ShortsQueueSource.decode(shortsSourceArg) == ShortsQueueSource.Feed }
        SEARCH_ROUTE_PATTERN -> FlowTab.Search
        else -> FlowTab.entries.firstOrNull { it.route == route }
    }

internal fun NavBackStackEntry.flowTab(): FlowTab? = flowTabForDestination(destination.route, arguments?.getString(SHORTS_ROUTE_ARG))

/** The tab a route handed in from outside the graph (a widget, a shortcut) names, if any. */
internal fun flowTabForRoute(
    route: String,
    downloadsTab: Boolean,
): FlowTab? =
    FlowTab.entries.firstOrNull { it.route == route }
        ?: FlowTab.Downloads.takeIf { route == DOWNLOADS_ROUTE && downloadsTab }

/** Downloads as its tab when the bar has one, else as a page over the current screen. */
internal fun NavController.openDownloads(
    tabs: List<FlowTab>,
    startRoute: String,
) {
    if (FlowTab.Downloads in tabs) {
        navigateToTab(FlowTab.Downloads, startRoute)
    } else {
        navigate(DOWNLOADS_ROUTE) { launchSingleTop = true }
    }
}

/** Set on the Subscriptions entry to open its management view on the Music tab once. */
internal const val OPEN_MUSIC_SUBSCRIPTIONS = "open_music_subscriptions"

/** Switches tabs the way the bar does: one copy per tab, each keeping its own saved state. */
internal fun NavController.navigateToTab(
    tab: FlowTab,
    startRoute: String,
) {
    navigate(tab.route) {
        popUpTo(startRoute) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** What a tap on a bar or rail tab does, from where the user is. */
internal enum class TabTap {
    /** Already on the tab's first screen: it scrolls to the top. */
    SCROLL_TO_TOP,

    /** On a page inside the tapped tab: back to that tab's first screen. */
    POP_TO_ROOT,

    /** On Search as a page, or tapping the start tab from the Search tab: Search closes first, as its back arrow does. */
    LEAVE_SEARCH,

    SWITCH,
}

/**
 * Search is a page over the tab it was opened from unless the bar has a Search tab ([searchIsTab]).
 * As a page it is never kept in a tab's saved stack, or tapping that tab would bring it straight back.
 * The start tab always leaves Search like Back: a Search page left under it from before the Search tab
 * was turned on is part of its saved stack, so switching to it would restore that page.
 */
internal fun tabTapAction(
    tapped: FlowTab,
    currentTab: FlowTab?,
    selectedTab: FlowTab,
    searchIsTab: Boolean,
    startTab: FlowTab,
): TabTap =
    when {
        currentTab == FlowTab.Search && tapped != FlowTab.Search && (!searchIsTab || tapped == startTab) -> TabTap.LEAVE_SEARCH
        currentTab == tapped -> TabTap.SCROLL_TO_TOP
        currentTab == null && tapped == selectedTab -> TabTap.POP_TO_ROOT
        else -> TabTap.SWITCH
    }

/** Carries out a [TabTap] that changes the screen; [selectedTab] is the tab whose pages are showing. */
internal fun NavController.tapTab(
    action: TabTap,
    tab: FlowTab,
    selectedTab: FlowTab,
    startRoute: String,
) {
    when (action) {
        TabTap.LEAVE_SEARCH -> {
            closeSearch()
            if (currentBackStackEntry?.flowTab() == FlowTab.Search || tab != selectedTab) navigateToTab(tab, startRoute)
        }

        TabTap.POP_TO_ROOT -> {
            if (!popBackStack(tab.route, inclusive = false)) navigateToTab(tab, startRoute)
        }

        TabTap.SWITCH -> {
            navigateToTab(tab, startRoute)
        }

        TabTap.SCROLL_TO_TOP -> {
            Unit
        }
    }
}

/** Closes the Search pages on top without keeping them; Search as the opening screen has nothing under it and stays. */
internal fun NavController.closeSearch() {
    while (currentBackStackEntry?.flowTab() == FlowTab.Search && previousBackStackEntry != null) popBackStack()
}

/** The first visible tab that is not [leaving]: where a video from the Shorts tab plays, and where back leads from Search as the opening tab. */
internal fun exitTab(
    tabs: List<FlowTab>,
    leaving: FlowTab,
): FlowTab = tabs.firstOrNull { it != leaving } ?: FlowTab.Home

/**
 * The pages inside a tab that keep the bar when the user asks for it: content people browse. Settings,
 * the players and full-screen flows stay without it.
 */
internal val PAGES_WITH_NAVIGATION_BAR: Set<String> =
    setOf(
        "channel?url={channelUrl}",
        "playlist/{playlistId}",
        "playlists?kind={kind}",
        MUSIC_ARTIST_ROUTE_PATTERN,
        "artistItems/{channelId}/{browseId}?params={params}",
        MUSIC_PLAYLIST_ROUTE_PATTERN,
        "youtube_browse/{browseId}?params={params}",
        "moodsAndGenres",
        "musicSearch?query={query}",
        "history",
        DOWNLOADS_ROUTE,
        "localMedia",
        "savedShorts",
        "notes",
        "notifications",
        "recognitionHistory",
        ON_REPEAT_SHUFFLE_ROUTE,
    )

/** Whether the bar shows on the screen at [route]: every tab's first screen, Search when [onSearch], listed pages when [onInnerPages]. */
internal fun navigationBarShown(
    currentTab: FlowTab?,
    route: String?,
    onSearch: Boolean,
    onInnerPages: Boolean,
): Boolean = currentTab.showsNavigationBar(onSearch) || (onInnerPages && currentTab == null && route in PAGES_WITH_NAVIGATION_BAR)

/** Search is a tab, but it keeps the back-button layout of the other search screens, so no bar unless [onSearch] asks for it. */
internal fun FlowTab?.showsNavigationBar(onSearch: Boolean = false): Boolean = this != null && (this != FlowTab.Search || onSearch)

internal fun youtubeChannelUrl(channelIdOrHandle: String): String? {
    val value = channelIdOrHandle.trim()
    if (value.isEmpty()) return null
    return when {
        value.startsWith("http://") || value.startsWith("https://") -> normalizeYoutubeChannelUrl(value)
        value.startsWith("UC") -> "https://www.youtube.com/channel/$value"
        value.startsWith("@") -> "https://www.youtube.com/$value"
        else -> "https://www.youtube.com/@$value"
    }
}

/**
 * The channel id the nav route carried, or null when it carried an @handle, `/c/` or `/user/` link.
 * Browse answers 400 to those, so they are resolved to an id first.
 */
internal fun youtubeChannelBrowseId(channelIdOrUrl: String): String? {
    val value = channelIdOrUrl.trim()
    if (value.isEmpty()) return null
    if (value.startsWith("UC") && !value.contains('/')) return value

    val segments =
        youtubeChannelUrl(value)
            ?.substringAfter("youtube.com/", "")
            ?.split('/')
            ?.filter(String::isNotBlank)
            ?: return null
    return segments
        .takeIf { it.firstOrNull() == "channel" }
        ?.getOrNull(1)
        ?.takeIf { it.startsWith("UC") }
}

internal fun youtubeChannelRoute(channelIdOrHandle: String): String? =
    youtubeChannelUrl(channelIdOrHandle)?.let { channelUrl ->
        "channel?url=${URLEncoder.encode(channelUrl, Charsets.UTF_8.name())}"
    }

private fun normalizeYoutubeChannelUrl(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    val host = uri.host?.lowercase().orEmpty()
    if (host != "youtube.com" && !host.endsWith(".youtube.com")) return url

    val segments =
        uri.path
            .orEmpty()
            .split('/')
            .filter(String::isNotBlank)
    if (segments.isEmpty()) return url

    val channelValue =
        when {
            segments.first() == "channel" -> segments.getOrNull(1)
            segments.first().startsWith("@") -> segments.first()
            else -> null
        } ?: return url

    return when {
        channelValue.startsWith("UC") -> "https://www.youtube.com/channel/$channelValue"
        channelValue.startsWith("@") -> "https://www.youtube.com/$channelValue"
        else -> "https://www.youtube.com/@$channelValue"
    }
}
