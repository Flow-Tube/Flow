package io.github.aedev.flow.ui

import io.github.aedev.flow.ui.components.layout.navigation.DOWNLOADS_ROUTE
import io.github.aedev.flow.ui.components.layout.navigation.DOWNLOADS_TAB_ROUTE
import io.github.aedev.flow.ui.components.layout.navigation.FlowTab
import io.github.aedev.flow.ui.screens.recap.RecapRoutes
import io.github.aedev.flow.ui.screens.search.SEARCH_ROUTE_PATTERN
import io.github.aedev.flow.ui.screens.update.UPDATE_ROUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteKeysTest {
    @Test
    fun everyTabRootIsItsOwnKey() {
        FlowTab.entries.filter { it != FlowTab.Shorts && it != FlowTab.Search }.forEach { tab ->
            assertEquals(tab.route, routeKeyFor(tab.route))
        }
    }

    @Test
    fun patternsWithArgumentsUseTheirShortKey() {
        assertEquals(SHORTS_ROUTE_KEY, routeKeyFor(SHORTS_ROUTE_PATTERN))
        assertEquals("search", routeKeyFor(SEARCH_ROUTE_PATTERN))
        assertEquals("settings", routeKeyFor("settings?target={target}"))
        assertEquals("channel", routeKeyFor("channel?url={channelUrl}"))
        assertEquals("playlist", routeKeyFor("playlist/{playlistId}"))
        assertEquals("playlists", routeKeyFor("playlists?kind={kind}"))
        assertEquals("musicSearch", routeKeyFor("musicSearch?query={query}"))
        assertEquals("youtube_browse", routeKeyFor("youtube_browse/{browseId}?params={params}"))
        assertEquals("recap", routeKeyFor(RecapRoutes.STATS))
        assertEquals("recap_story", routeKeyFor(RecapRoutes.STORY))
        assertEquals(UPDATE_ROUTE, routeKeyFor(UPDATE_ROUTE))
    }

    @Test
    fun everyMusicPlayerEntryIsTheMusicPlayer() {
        listOf(MUSIC_PLAYER_ROUTE_PATTERN, ON_REPEAT_SHUFFLE_ROUTE, WIDGET_PLAYLIST_ROUTE).forEach { route ->
            assertEquals("musicPlayer", routeKeyFor(route))
        }
    }

    @Test
    fun downloadsKeepsWhichEntryWasOpened() {
        assertEquals(DOWNLOADS_ROUTE, routeKeyFor(DOWNLOADS_ROUTE))
        assertEquals(DOWNLOADS_TAB_ROUTE, routeKeyFor(DOWNLOADS_TAB_ROUTE))
    }

    @Test
    fun pagesWithoutAKeyKeepThePreviousOne() {
        assertNull(routeKeyFor(MUSIC_ARTIST_ROUTE_PATTERN))
        assertNull(routeKeyFor("player/{videoId}"))
        assertNull(routeKeyFor(null))
    }
}
