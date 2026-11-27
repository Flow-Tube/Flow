package io.github.aedev.flow.ui.screens.channel

import io.github.aedev.flow.innertube.pages.channel.ChannelHeader
import io.github.aedev.flow.innertube.pages.channel.ChannelLink
import io.github.aedev.flow.innertube.pages.channel.ChannelTabDescriptor
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen renders one tab per entry here, so this is where "only show what the channel has" is
 * actually enforced.
 */
class ChannelScreenTabTest {
    private fun descriptor(
        kind: ChannelTabKind,
        title: String = kind.name,
        params: String? = "PARAMS_${kind.name}",
    ) = ChannelTabDescriptor(kind = kind, title = title, params = params)

    private val bareHeader = ChannelHeader(id = "UCx", title = "Channel")

    private fun tabs(
        descriptors: List<ChannelTabDescriptor>,
        header: ChannelHeader? = bareHeader,
        showShortsTab: Boolean = true,
    ) = channelScreenTabs(descriptors, header, showShortsTab, aboutTitle = "About")

    @Test
    fun `a large channel keeps every tab it published, in order`() {
        val resolved =
            tabs(
                listOf(
                    descriptor(ChannelTabKind.Home),
                    descriptor(ChannelTabKind.Videos),
                    descriptor(ChannelTabKind.Shorts),
                    descriptor(ChannelTabKind.Live),
                    descriptor(ChannelTabKind.Shows),
                    descriptor(ChannelTabKind.Podcasts),
                    descriptor(ChannelTabKind.Playlists),
                    descriptor(ChannelTabKind.Posts),
                    descriptor(ChannelTabKind.Store),
                ),
            )

        assertEquals(
            listOf(
                ChannelTabKind.Home,
                ChannelTabKind.Videos,
                ChannelTabKind.Shorts,
                ChannelTabKind.Live,
                ChannelTabKind.Shows,
                ChannelTabKind.Podcasts,
                ChannelTabKind.Playlists,
                ChannelTabKind.Posts,
            ),
            resolved.filterNot { it.isAbout }.map { it.kind },
        )
    }

    @Test
    fun `a channel without a podcasts tab gets no podcasts tab`() {
        val resolved =
            tabs(listOf(descriptor(ChannelTabKind.Videos), descriptor(ChannelTabKind.Playlists)))

        assertTrue(resolved.none { it.kind == ChannelTabKind.Podcasts })
        assertTrue(resolved.none { it.kind == ChannelTabKind.Live })
    }

    @Test
    fun `the store tab is dropped even when the channel has one`() {
        val resolved = tabs(listOf(descriptor(ChannelTabKind.Videos), descriptor(ChannelTabKind.Store)))

        assertTrue(resolved.none { it.kind == ChannelTabKind.Store })
    }

    @Test
    fun `the tab label is YouTube's own, not the app's`() {
        val resolved = tabs(listOf(descriptor(ChannelTabKind.Videos, title = "Vídeos")))

        assertEquals("Vídeos", resolved.first().title)
    }

    @Test
    fun `the search tab is dropped because it is already the magnifier`() {
        val resolved = tabs(listOf(descriptor(ChannelTabKind.Videos), descriptor(ChannelTabKind.Search)))

        assertTrue(resolved.none { it.kind == ChannelTabKind.Search })
    }

    @Test
    fun `hiding the shorts tab hides only the shorts tab`() {
        val descriptors = listOf(descriptor(ChannelTabKind.Videos), descriptor(ChannelTabKind.Shorts))

        assertTrue(tabs(descriptors).any { it.kind == ChannelTabKind.Shorts })
        assertTrue(tabs(descriptors, showShortsTab = false).none { it.kind == ChannelTabKind.Shorts })
        assertTrue(tabs(descriptors, showShortsTab = false).any { it.kind == ChannelTabKind.Videos })
    }

    @Test
    fun `a shown shorts tab keeps the place the channel gave it`() {
        val descriptors =
            listOf(
                descriptor(ChannelTabKind.Videos),
                descriptor(ChannelTabKind.Shorts),
                descriptor(ChannelTabKind.Live),
                descriptor(ChannelTabKind.Playlists),
            )

        assertEquals(
            listOf(ChannelTabKind.Videos, ChannelTabKind.Shorts, ChannelTabKind.Live, ChannelTabKind.Playlists),
            tabs(descriptors, showShortsTab = true).map { it.kind },
        )
    }

    @Test
    fun `an unrecognised tab is still rendered`() {
        val resolved =
            tabs(listOf(descriptor(ChannelTabKind.Videos), descriptor(ChannelTabKind.Unknown, title = "Membership")))

        assertTrue(resolved.any { it.title == "Membership" && !it.isAbout })
    }

    @Test
    fun `a tab with no params and no default is dropped rather than shown empty`() {
        val resolved =
            tabs(listOf(descriptor(ChannelTabKind.Videos), descriptor(ChannelTabKind.Unknown, "Mystery", params = null)))

        assertTrue(resolved.none { it.title == "Mystery" })
    }

    @Test
    fun `a known tab with no params falls back to its own token`() {
        val resolved = tabs(listOf(descriptor(ChannelTabKind.Videos, params = null)))

        assertEquals(ChannelTabKind.Videos.defaultParams, resolved.first().params)
    }

    @Test
    fun `about is appended when the header has anything to show`() {
        val withDescription = bareHeader.copy(description = "A channel about things")
        val resolved = tabs(listOf(descriptor(ChannelTabKind.Videos)), header = withDescription)

        assertTrue(resolved.last().isAbout)
        assertEquals("About", resolved.last().title)
    }

    @Test
    fun `about is appended for links alone`() {
        val withLinks = bareHeader.copy(links = listOf(ChannelLink("Store", "example.test", "https://example.test")))

        assertTrue(tabs(listOf(descriptor(ChannelTabKind.Videos)), header = withLinks).any { it.isAbout })
    }

    @Test
    fun `about is absent when the header carries nothing`() {
        val resolved = tabs(listOf(descriptor(ChannelTabKind.Videos)))

        assertTrue(resolved.none { it.isAbout })
    }

    @Test
    fun `no header means no tabs at all`() {
        assertTrue(tabs(emptyList(), header = null).isEmpty())
    }

    @Test
    fun `a tab with a blank title is dropped`() {
        val resolved = tabs(listOf(descriptor(ChannelTabKind.Videos), descriptor(ChannelTabKind.Live, title = "  ")))

        assertFalse(resolved.any { it.kind == ChannelTabKind.Live })
    }

    private val homeVideosLive =
        listOf(descriptor(ChannelTabKind.Home), descriptor(ChannelTabKind.Videos), descriptor(ChannelTabKind.Live))

    @Test
    fun `a channel opens on Videos when preferred and present`() {
        assertEquals(ChannelTabKind.Videos, initialChannelTab(homeVideosLive, preferVideos = true))
    }

    @Test
    fun `a channel without Videos opens on its first tab even when Videos is preferred`() {
        val tabs = listOf(descriptor(ChannelTabKind.Home), descriptor(ChannelTabKind.Playlists))

        assertEquals(ChannelTabKind.Home, initialChannelTab(tabs, preferVideos = true))
    }

    @Test
    fun `a channel opens on its first tab when Videos is not preferred`() {
        assertEquals(ChannelTabKind.Home, initialChannelTab(homeVideosLive, preferVideos = false))
    }

    @Test
    fun `no tabs means no initial tab`() {
        assertNull(initialChannelTab(emptyList(), preferVideos = true))
    }
}
