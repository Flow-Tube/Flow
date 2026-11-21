package io.github.aedev.flow.ui

import io.github.aedev.flow.ui.components.layout.navigation.FlowTab
import io.github.aedev.flow.ui.screens.search.SEARCH_ROUTE_PATTERN
import org.junit.Assert.assertEquals
import org.junit.Test

class NavTransitionKindTest {
    private fun kind(
        from: String,
        to: String,
        searchIsTab: Boolean = false,
    ) = navTransitionKind(
        fromRoute = from,
        fromTab = flowTabForDestination(from, shortsSourceArg = null),
        toRoute = to,
        toTab = flowTabForDestination(to, shortsSourceArg = null),
        searchIsTab = searchIsTab,
    )

    @Test
    fun switchingTabsFadesThrough() {
        assertEquals(NavTransitionKind.TAB, kind("home", "library"))
        assertEquals(NavTransitionKind.TAB, kind("subscriptions", "music"))
        assertEquals(NavTransitionKind.TAB, kind("music", SHORTS_ROUTE_PATTERN))
    }

    @Test
    fun openingOrLeavingAPageSlides() {
        assertEquals(NavTransitionKind.PAGE, kind("home", "channel?url={channelUrl}"))
        assertEquals(NavTransitionKind.PAGE, kind("playlist/{playlistId}", "library"))
        assertEquals(NavTransitionKind.PAGE, kind("channel?url={channelUrl}", "channel?url={channelUrl}"))
    }

    @Test
    fun searchSlidesUnlessItIsATab() {
        assertEquals(NavTransitionKind.PAGE, kind("home", SEARCH_ROUTE_PATTERN))
        assertEquals(NavTransitionKind.PAGE, kind(SEARCH_ROUTE_PATTERN, "home"))
        assertEquals(NavTransitionKind.TAB, kind("home", SEARCH_ROUTE_PATTERN, searchIsTab = true))
        assertEquals(NavTransitionKind.PAGE, kind(SEARCH_ROUTE_PATTERN, SEARCH_ROUTE_PATTERN, searchIsTab = true))
    }

    @Test
    fun routesThatLeaveAtOnceDoNotMove() {
        assertEquals(NavTransitionKind.NONE, kind("home", PLAYER_ROUTE_PATTERN))
        assertEquals(NavTransitionKind.NONE, kind(PLAYER_ROUTE_PATTERN, "home"))
        assertEquals(NavTransitionKind.NONE, kind("library", MUSIC_PLAYER_ROUTE_PATTERN))
        assertEquals(NavTransitionKind.NONE, kind("home", WIDGET_PLAYLIST_ROUTE))
        assertEquals(NavTransitionKind.NONE, kind("home", ON_REPEAT_SHUFFLE_ROUTE))
        assertEquals(NavTransitionKind.NONE, kind("home", LINKED_PLAYLIST_ROUTE_PATTERN))
    }

    @Test
    fun aTabsOwnRootIsNotATabSwitch() {
        assertEquals(NavTransitionKind.PAGE, kind("home", "home"))
    }
}
