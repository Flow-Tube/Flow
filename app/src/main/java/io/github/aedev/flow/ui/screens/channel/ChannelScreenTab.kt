package io.github.aedev.flow.ui.screens.channel

import io.github.aedev.flow.innertube.pages.channel.ChannelHeader
import io.github.aedev.flow.innertube.pages.channel.ChannelTabDescriptor
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind

/** Search is already the magnifier in the filter bar; Store is a shop the app does not implement. */
private val HIDDEN_TABS = setOf(ChannelTabKind.Search, ChannelTabKind.Store)

internal data class ChannelScreenTab(
    val kind: ChannelTabKind,
    val title: String,
    val params: String?,
    val isAbout: Boolean = false,
)

/**
 * The tabs the screen renders, derived from the tabs the channel published.
 *
 * Nothing is added that the response did not carry, so a channel without a Podcasts tab has no
 * Podcasts tab rather than an empty one. The exceptions are [HIDDEN_TABS], and About, which is not an
 * InnerTube tab at all — it is appended only when the header has something to put in it.
 */
internal fun channelScreenTabs(
    descriptors: List<ChannelTabDescriptor>,
    header: ChannelHeader?,
    showShortsTab: Boolean,
    aboutTitle: String,
): List<ChannelScreenTab> {
    val tabs =
        descriptors.mapNotNull { descriptor ->
            if (descriptor.kind in HIDDEN_TABS) return@mapNotNull null
            if (descriptor.kind == ChannelTabKind.Shorts && !showShortsTab) return@mapNotNull null
            val params = descriptor.params ?: descriptor.kind.defaultParams ?: return@mapNotNull null
            val title = descriptor.title.takeIf(String::isNotBlank) ?: return@mapNotNull null
            ChannelScreenTab(kind = descriptor.kind, title = title, params = params)
        }
    return if (header.hasAboutContent()) {
        tabs + ChannelScreenTab(kind = ChannelTabKind.Unknown, title = aboutTitle, params = null, isAbout = true)
    } else {
        tabs
    }
}

/** The tab a freshly opened channel lands on: Videos when the user prefers it and the channel has one. */
internal fun initialChannelTab(
    tabs: List<ChannelTabDescriptor>,
    preferVideos: Boolean,
): ChannelTabKind? = tabs.firstOrNull { preferVideos && it.kind == ChannelTabKind.Videos }?.kind ?: tabs.firstOrNull()?.kind

private fun ChannelHeader?.hasAboutContent(): Boolean =
    this != null &&
        (
            !description.isNullOrBlank() ||
                links.isNotEmpty() ||
                !handle.isNullOrBlank() ||
                !subscriberCountText.isNullOrBlank() ||
                !videoCountText.isNullOrBlank() ||
                !viewCountText.isNullOrBlank() ||
                !joinedDateText.isNullOrBlank() ||
                !countryText.isNullOrBlank()
        )
