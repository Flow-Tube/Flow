package io.github.aedev.flow.ui

import io.github.aedev.flow.ui.components.layout.navigation.FlowTab
import io.github.aedev.flow.ui.components.layout.navigation.NavigationVisibility
import io.github.aedev.flow.ui.components.layout.navigation.visibleFlowTabs
import io.github.aedev.flow.ui.screens.update.UPDATE_ROUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDestinationsTest {
    @Test
    fun tabRootsMapToTheirTab() {
        FlowTab.entries.filter { it != FlowTab.Shorts }.forEach { tab ->
            assertEquals(tab, flowTabForDestination(tab.route, shortsSourceArg = null))
        }
    }

    @Test
    fun onlyTheShortsFeedIsTheShortsTab() {
        assertEquals(FlowTab.Shorts, flowTabForDestination(SHORTS_ROUTE_PATTERN, shortsSourceArg = null))
        assertEquals(FlowTab.Shorts, flowTabForDestination(SHORTS_ROUTE_PATTERN, shortsSourceArg = "feed"))
        assertNull(flowTabForDestination(SHORTS_ROUTE_PATTERN, shortsSourceArg = "feed:dQw4w9WgXcQ"))
        assertNull(flowTabForDestination(SHORTS_ROUTE_PATTERN, shortsSourceArg = "saved:"))
    }

    @Test
    fun aVideoOpenedFromShortsPlaysOverTheFirstOtherTab() {
        assertEquals(FlowTab.Music, exitTab(listOf(FlowTab.Shorts, FlowTab.Music, FlowTab.Library), FlowTab.Shorts))
        assertEquals(FlowTab.Home, exitTab(listOf(FlowTab.Home, FlowTab.Shorts), FlowTab.Shorts))
        assertEquals(FlowTab.Home, exitTab(listOf(FlowTab.Shorts), FlowTab.Shorts))
        assertEquals(FlowTab.Home, exitTab(emptyList(), FlowTab.Shorts))
    }

    @Test
    fun backFromSearchAsTheOpeningTabLandsOnAVisibleTab() {
        val homeHidden =
            visibleFlowTabs(listOf(5, 0, 3, 4), NavigationVisibility(home = false, shorts = false, music = false, search = true))
        assertEquals(FlowTab.Search, homeHidden.first())
        assertEquals(FlowTab.Subscriptions, exitTab(homeHidden, FlowTab.Search))
    }

    @Test
    fun detailScreensAreNotTabRoots() {
        listOf("settings", "settings/content", "playlists", "playlist/{playlistId}", "onboarding", null).forEach { route ->
            assertNull(flowTabForDestination(route, shortsSourceArg = null))
        }
    }

    @Test
    fun routesFromOutsideTheGraphResolveToTheirTab() {
        assertEquals(FlowTab.Search, flowTabForRoute("search", downloadsTab = false))
        assertEquals(FlowTab.Music, flowTabForRoute("music", downloadsTab = false))
        listOf("downloads", "history", "musicRecognize", "musicPlayer/abc", "settings").forEach { route ->
            assertNull(flowTabForRoute(route, downloadsTab = false))
        }
    }

    @Test
    fun theDownloadsPageOpensAsTheTabOnlyWhenTheBarHasIt() {
        assertEquals(FlowTab.Downloads, flowTabForRoute("downloads", downloadsTab = true))
        assertNull(flowTabForRoute("downloads", downloadsTab = false))
        assertNull(flowTabForDestination("downloads", shortsSourceArg = null))
    }

    @Test
    fun searchIsTheOnlyTabWithoutTheBar() {
        FlowTab.entries.forEach { tab -> assertEquals(tab != FlowTab.Search, tab.showsNavigationBar()) }
        assertFalse((null as FlowTab?).showsNavigationBar())
        assertTrue(FlowTab.Home.showsNavigationBar())
    }

    @Test
    fun theSwitchPutsTheBarOnSearchOnly() {
        FlowTab.entries.forEach { tab -> assertTrue(tab.showsNavigationBar(onSearch = true)) }
        assertFalse((null as FlowTab?).showsNavigationBar(onSearch = true))
    }

    @Test
    fun onlyChannelIdsAreBrowsedDirectly() {
        assertEquals("UCXuqSBlHAE6Xw-yeJA0Tunw", youtubeChannelBrowseId("UCXuqSBlHAE6Xw-yeJA0Tunw"))
        assertEquals("UCXuqSBlHAE6Xw-yeJA0Tunw", youtubeChannelBrowseId("https://m.youtube.com/channel/UCXuqSBlHAE6Xw-yeJA0Tunw?si=abc"))
        assertNull(youtubeChannelBrowseId("@LinusTechTips"))
        assertNull(youtubeChannelBrowseId("https://www.youtube.com/@LinusTechTips"))
        assertNull(youtubeChannelBrowseId("https://www.youtube.com/c/LinusTechTips"))
        assertNull(youtubeChannelBrowseId(" "))
    }

    @Test
    fun channelHandlesUseHandleUrls() {
        assertEquals("https://www.youtube.com/@flow", youtubeChannelUrl("@flow"))
        assertEquals("https://www.youtube.com/@flow", youtubeChannelUrl("flow"))
        assertEquals(
            "https://www.youtube.com/channel/UC123",
            youtubeChannelUrl("UC123"),
        )
    }

    @Test
    fun malformedHandleChannelUrlsAreRepaired() {
        assertEquals(
            "https://www.youtube.com/@flow",
            youtubeChannelUrl("https://youtube.com/channel/@flow"),
        )
        assertEquals(
            "https://www.youtube.com/@flow",
            youtubeChannelUrl("https://m.youtube.com/@flow/videos"),
        )
    }

    @Test
    fun channelRoutesEncodeCanonicalUrls() {
        assertEquals(
            "channel?url=https%3A%2F%2Fwww.youtube.com%2F%40flow",
            youtubeChannelRoute("@flow"),
        )
    }

    @Test
    fun aTabTappedOnSearchOpenedAsAPageLeavesSearchFirst() {
        FlowTab.entries.filter { it != FlowTab.Search }.forEach { tapped ->
            assertEquals(
                TabTap.LEAVE_SEARCH,
                tabTapAction(tapped, FlowTab.Search, selectedTab = FlowTab.Home, searchIsTab = false, startTab = FlowTab.Home),
            )
        }
    }

    @Test
    fun withASearchTabInTheBarOtherTabsSwitchAndKeepItsResults() {
        assertEquals(
            TabTap.SWITCH,
            tabTapAction(FlowTab.Music, FlowTab.Search, selectedTab = FlowTab.Search, searchIsTab = true, startTab = FlowTab.Home),
        )
        assertEquals(
            TabTap.SCROLL_TO_TOP,
            tabTapAction(FlowTab.Search, FlowTab.Search, selectedTab = FlowTab.Search, searchIsTab = true, startTab = FlowTab.Home),
        )
    }

    @Test
    fun theStartTabLeavesTheSearchTabLikeBack() {
        assertEquals(
            TabTap.LEAVE_SEARCH,
            tabTapAction(FlowTab.Home, FlowTab.Search, selectedTab = FlowTab.Search, searchIsTab = true, startTab = FlowTab.Home),
        )
        assertEquals(
            TabTap.LEAVE_SEARCH,
            tabTapAction(FlowTab.Music, FlowTab.Search, selectedTab = FlowTab.Search, searchIsTab = true, startTab = FlowTab.Music),
        )
        assertEquals(
            TabTap.SCROLL_TO_TOP,
            tabTapAction(FlowTab.Search, FlowTab.Search, selectedTab = FlowTab.Search, searchIsTab = true, startTab = FlowTab.Search),
        )
    }

    @Test
    fun theTabAlreadyOnItsFirstScreenScrollsToTheTop() {
        assertEquals(
            TabTap.SCROLL_TO_TOP,
            tabTapAction(FlowTab.Home, FlowTab.Home, selectedTab = FlowTab.Home, searchIsTab = false, startTab = FlowTab.Home),
        )
        assertEquals(
            TabTap.SCROLL_TO_TOP,
            tabTapAction(FlowTab.Music, FlowTab.Music, selectedTab = FlowTab.Music, searchIsTab = true, startTab = FlowTab.Home),
        )
    }

    @Test
    fun theHighlightedTabOnAPageInsideItGoesBackToItsFirstScreen() {
        assertEquals(
            TabTap.POP_TO_ROOT,
            tabTapAction(FlowTab.Home, currentTab = null, selectedTab = FlowTab.Home, searchIsTab = false, startTab = FlowTab.Home),
        )
        assertEquals(
            TabTap.SWITCH,
            tabTapAction(FlowTab.Music, currentTab = null, selectedTab = FlowTab.Home, searchIsTab = false, startTab = FlowTab.Home),
        )
    }

    @Test
    fun anotherTabSwitches() {
        assertEquals(
            TabTap.SWITCH,
            tabTapAction(FlowTab.Music, FlowTab.Home, selectedTab = FlowTab.Home, searchIsTab = false, startTab = FlowTab.Home),
        )
    }

    @Test
    fun innerPagesKeepTheBarOnlyWhenAskedFor() {
        assertTrue(navigationBarShown(null, "channel?url={channelUrl}", onSearch = false, onInnerPages = true))
        assertTrue(navigationBarShown(null, MUSIC_ARTIST_ROUTE_PATTERN, onSearch = false, onInnerPages = true))
        assertFalse(navigationBarShown(null, "channel?url={channelUrl}", onSearch = false, onInnerPages = false))
    }

    @Test
    fun settingsPlayersAndFullScreenFlowsNeverGetTheBar() {
        listOf("settings?target={target}", EQUALIZER_ROUTE, UPDATE_ROUTE, MUSIC_PLAYER_ROUTE_PATTERN, "player/{videoId}", "onboarding")
            .forEach { route -> assertFalse(route, navigationBarShown(null, route, onSearch = true, onInnerPages = true)) }
    }

    @Test
    fun tabRootsAndSearchKeepTheirOwnRules() {
        assertTrue(navigationBarShown(FlowTab.Home, "home", onSearch = false, onInnerPages = false))
        assertFalse(navigationBarShown(FlowTab.Search, "search", onSearch = false, onInnerPages = true))
        assertTrue(navigationBarShown(FlowTab.Search, "search", onSearch = true, onInnerPages = false))
    }

    @Test
    fun everySearchPageIsTheSearchTab() {
        assertEquals(
            FlowTab.Search,
            flowTabForDestination(io.github.aedev.flow.ui.screens.search.SEARCH_ROUTE_PATTERN, shortsSourceArg = null),
        )
    }
}
